"""분석 서비스 39~46번 (개발기획서 4.2, 5.4~5.9, 6.2, 7.3, 12.2). 일반 camelCase dict를 돌려준다.

- 모든 함수는 읽기 전용이다. 쓰기는 분석 캐시(`stores.analysis`)뿐이다. 상태 검사는 `state_rules.check`(ANALYSIS_READ /
  SIMULATE)를 `flow_errors.raise_if_violation`으로 올린다.
- 캐시(`CachedAnalysis`): `decision_version == snapshot.version`일 때만 유효하다.
  `payload`는 결정적으로 계산한 39번 본문에서 `provisional`·`optionSummaries`·`explanation`을 뺀 것이고(상태는 버전을
  올리지 않으므로 provisional은 읽을 때 붙인다), AI가 만든 문장(optionSummaries·explanation)은 `explanation`에 같은
  버전 동안 한 번만 만들어 둔다(12.2). 40·41·44·45번은 같은 본문에서 꺼내므로 숫자가 39번과 항상 같다.
- FINALIZED: 확정 시점에 저장한 `recommendation_snapshot`(39번 전체)을 그대로 돌려준다. 40·41·44·45번도
  그 안에서 꺼낸다.
  42·43·46번(P1)은 확정 후 입력이 바뀔 수 없으므로 시드+답변으로 다시 계산한다.

명세의 모호한 부분 (가정):
- 분석 조회의 "답변 수"는 유효 답변 전체(잘 모르겠어요·자유 입력 가상 답변 포함)로 센다 (state_rules.check의 가정).
- 제외되지 않은 선택지가 하나도 없으면 422 NOT_READY(추천할 선택지가 없다; 명세에 없음).
- AI 문장은 라우터가 있을 때만 시도하고, 시도했으면(실패해 템플릿이 되어도) 그 버전의 `explanation`에
  저장해 다시 부르지 않는다.
  라우터가 없으면 템플릿을 매번 계산만 하고 저장하지 않는다(나중에 AI가 붙으면 그때 만든다).
- 42번: 값이 비어 있는 칸(값 입력 전인 기준)은 셀을 만들지 않는다. rawValue는 숫자·단계형은 입력값, 순위 입력은
  그룹 번호, 예/아니요는 1/0이다.
- 43번: crossovers는 기준마다 5.8의 "가장 가까운 전환점 1개"만 담는다(곡선의 다른 교차는 담지 않는다).
  알 수 없는 criterionId는 404가 아니라 400 VALIDATION_ERROR(쿼리 값 오류)로 본다.
- 46번: 가중치는 모든 기준을 정확히 한 번씩 담아야 하고(누락·중복·알 수 없는 id는 400), 음수·비유한수·합 0도 400이다.
  normalizedWeights는 소수 넷째 자리, 점수는 둘째 자리 반올림. changedFromCurrent는
  순위 순서(선택지 id 목록)가 달라졌는지만 본다.
- 템플릿 optionSummaries: 기준별로 값이 가장 좋은(정규화 값 최대) 선택지에 pros, 가장 나쁜 선택지에 cons를 붙인다
  (모든 선택지가 같으면 건너뜀). 중요도(평균 가중치)가 큰 기준부터 최대 3개씩.
"""

from __future__ import annotations

import contextlib
import copy
import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import numpy as np
from numpy.typing import NDArray

from app.ai.calls import LLMSource, explain_result, summarize_options
from app.ai.client import UsageCallback
from app.ai.errors import LLMConfigError, LLMError
from app.engine.decision_analysis import (
    ChangeCondition,
    ValueCondition,
    change_conditions,
    ranking,
    scores,
    sensitivity,
    stability,
    value_conditions,
)
from app.engine.hidden_preference import detect
from app.engine.posterior import Posterior, Summary, fit, prior_particles, summary

from . import analysis_helpers as ah
from .engine_bridge import EngineInput, build_engine_input
from .flow_errors import FlowError, raise_if_violation, validation_error
from .ports import CachedAnalysis, ConditionRow, CriterionRow, DecisionSnapshot, Stores, ValueRow
from .state_rules import Action, Status, check, is_provisional

_P = ah.PROB_DIGITS
_WEIGHT_DIGITS = 4  # 46번 normalizedWeights, 43번 crossover 가중치
_MAX_BULLETS = 3  # 템플릿 장단점·설명 이유의 최대 개수


def _p(x: float) -> float:
    return round(float(x), _P)


def _pct(x: float) -> int:
    return int(math.floor(float(x) * 100 + 0.5))


def _fmt(x: float) -> str:
    r = round(float(x), 4)
    return str(int(r)) if r == math.floor(r) else str(r)


def _now() -> str:
    return datetime.now(UTC).isoformat()


# --------------------------------------------------------------------------------------------------
# 읽기 준비
# --------------------------------------------------------------------------------------------------


@dataclass
class _Ctx:
    snapshot: DecisionSnapshot
    inp: EngineInput
    answered: int

    def fit(self) -> tuple[Posterior, Summary]:
        post = fit(self.inp.seed, self.inp.K, self.inp.answers)
        return post, summary(post, self.inp.V)


async def _ctx(snapshot: DecisionSnapshot, stores: Stores, action: Action) -> _Ctx:
    questions = list(await stores.questions.list_questions(snapshot.id))
    answers = list(await stores.questions.list_answers(snapshot.id))
    inp = build_engine_input(snapshot, questions, answers)
    answered = len(inp.answers)
    raise_if_violation(check(action, snapshot.status, answered=answered), {"decisionId": snapshot.id})
    if not inp.option_ids:
        raise FlowError(
            422,
            "NOT_READY",
            "필수 조건을 만족하는 선택지가 없어 결과를 계산할 수 없습니다.",
            {"decisionId": snapshot.id},
        )
    return _Ctx(snapshot, inp, answered)


async def _finalized_snapshot(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any] | None:
    """FINALIZED면 확정 시점의 39번 응답(없으면 None → 다시 계산)."""
    if snapshot.status is not Status.FINALIZED:
        return None
    row = await stores.final_choices.get(snapshot.id)
    if row is None or not row.recommendation_snapshot:
        return None
    return copy.deepcopy(row.recommendation_snapshot)


# --------------------------------------------------------------------------------------------------
# 값 표기 (장단점·AI 입력용)
# --------------------------------------------------------------------------------------------------


def _value_map(snapshot: DecisionSnapshot) -> dict[tuple[str, str], ValueRow]:
    return {(v.option_id, v.criterion_id): v for v in snapshot.values}


def _value_label(c: CriterionRow, v: ValueRow | None) -> str | None:
    if v is None:
        return None
    if c.type == "boolean":
        return None if v.value_bool is None else ("예" if v.value_bool else "아니요")
    if c.type == "rating" and c.input_mode == "rank":
        return None if v.value_rank is None else f"{v.value_rank}순위"
    if v.value_num is None:
        return None
    if c.type == "rating":
        idx = int(round(v.value_num))
        return c.levels[idx - 1] if c.levels and 1 <= idx <= len(c.levels) else f"{idx}단계"
    return f"{_fmt(v.value_num)}{c.unit or ''}"


def _raw_value(c: CriterionRow, v: ValueRow | None) -> float | None:
    if v is None:
        return None
    x: float | int | None
    if c.type == "boolean":
        x = None if v.value_bool is None else int(v.value_bool)
    elif c.type == "rating" and c.input_mode == "rank":
        x = v.value_rank
    else:
        x = v.value_num
    return None if x is None else float(x)


# --------------------------------------------------------------------------------------------------
# 제외 선택지
# --------------------------------------------------------------------------------------------------


def _condition_reason(cond: ConditionRow, criteria: Mapping[str, CriterionRow]) -> str:
    """미충족 조건 → 짧은 사유. 임계값 조건은 '가격 200만 원 초과', 필수 태그는 'Windows 지원 미충족'."""
    if cond.kind == "required_tag":
        return f"{cond.label or '필수 조건'} 미충족"
    c = criteria.get(cond.criterion_id or "")
    name = c.name if c else (cond.label or "조건")
    if cond.value is None or cond.operator is None:
        return f"{name} 조건 미충족"
    amount = f"{_fmt(cond.value)}{(c.unit or '') if c else ''}"
    if cond.operator == "<=":
        return f"{name} {amount} 초과"
    if cond.operator == ">=":
        return f"{name} {amount} 미만"
    return f"{name} {amount}이(가) 아님"  # "==" 조건


def _excluded_payload(snapshot: DecisionSnapshot, inp: EngineInput) -> list[dict[str, Any]]:
    conds = {c.id: c for c in snapshot.conditions}
    crit = {c.id: c for c in snapshot.criteria}
    out: list[dict[str, Any]] = []
    for o in snapshot.options:
        failed = inp.excluded.get(o.id)
        if failed:
            out.append({"optionId": o.id, "reasons": [_condition_reason(conds[f], crit) for f in failed if f in conds]})
    return out


# --------------------------------------------------------------------------------------------------
# 템플릿 문장
# --------------------------------------------------------------------------------------------------


def _template_summaries(snapshot: DecisionSnapshot, inp: EngineInput, mean: Sequence[float]) -> list[dict[str, Any]]:
    """값 매트릭스로 만든 장단점. 정규화 값이 가장 큰(작은) 선택지에 pros(cons)를 붙인다."""
    vals = _value_map(snapshot)
    V = inp.V
    order = sorted(range(inp.K), key=lambda k: -mean[k])
    items: list[dict[str, Any]] = []
    for i, oid in enumerate(inp.option_ids):
        pros: list[str] = []
        cons: list[str] = []
        for k in order:
            col = V[:, k]
            if col.max() - col.min() < 1e-12:
                continue
            c = snapshot.criteria[k]
            label = _value_label(c, vals.get((oid, c.id)))
            tail = f" ({label})" if label else ""
            if V[i, k] >= col.max() - 1e-12 and len(pros) < _MAX_BULLETS:
                pros.append(f"{ah.i_ga(c.name)} 가장 유리해요{tail}")
            if V[i, k] <= col.min() + 1e-12 and len(cons) < _MAX_BULLETS:
                cons.append(f"{ah.i_ga(c.name)} 가장 불리해요{tail}")
        items.append({"optionId": oid, "pros": pros, "cons": cons})
    return items


def _template_explanation(snapshot: DecisionSnapshot, inp: EngineInput, base: Mapping[str, Any]) -> dict[str, Any]:
    """분석 결과에 있는 숫자만 쓰는 템플릿 설명 (source=template)."""
    rec = base["recommendation"]
    top = inp.option_ids.index(rec["optionId"])
    name = rec["name"]
    summary_text = f"{ah.eu_ro(name)} 추천해요. 1위일 확률은 {_pct(rec['topProbability'])}%예요."
    means = {w["criterionId"]: w["mean"] for w in base["weights"]}
    contribs = sorted(
        ((means[cid] * float(inp.V[top, k]), k) for k, cid in enumerate(inp.criterion_ids)), key=lambda t: -t[0]
    )
    reasons: list[str] = []
    for _, k in contribs:
        if len(reasons) >= _MAX_BULLETS:
            break
        if inp.V[top, k] >= 0.5 and means[inp.criterion_ids[k]] > 0:
            reasons.append(
                f"{inp.criterion_names[k]} 기준(중요도 {_pct(means[inp.criterion_ids[k]])}%)에서 "
                f"{ah.i_ga(name)} 유리해서 점수에 크게 기여했어요."
            )
    if not reasons and contribs:
        reasons.append(f"점수에 가장 크게 기여한 기준은 {inp.criterion_names[contribs[0][1]]}이에요.")
    caveats: list[str] = []
    if base["stability"]["level"] != "HIGH":
        caveats.append(base["stability"]["text"])
    if base["lowConfidence"]:
        caveats.append(
            f"1위 확률이 {_pct(rec['topProbability'])}%로 낮아요. 질문을 더 하면 결과가 더 분명해질 수 있어요."
        )
    if base["hiddenPreferences"]:
        caveats.append(f"말로 답한 중요도와 실제 선택이 엇갈린 항목이 있어요. {ah.HIDDEN_PREFERENCE_NOTICE}.")
    names_by_id = {o.id: o.name for o in snapshot.options}
    excluded = [names_by_id[e["optionId"]] for e in base["excludedOptions"] if e["optionId"] in names_by_id]
    if excluded:
        caveats.append(f"필수 조건을 만족하지 못해 {', '.join(excluded)}은(는) 비교에서 제외됐어요.")
    return {"summary": summary_text, "reasons": reasons, "caveats": caveats, "source": "template"}


# --------------------------------------------------------------------------------------------------
# 39번 본문 계산 (결정적)
# --------------------------------------------------------------------------------------------------


def _usable_value_conditions(s: Summary, inp: EngineInput) -> list[ValueCondition]:
    """값이 비어 있는 기준(정규화 값이 임의로 0.5인 열)은 값 전환 조건에서 뺀다."""
    return [
        v
        for v in value_conditions(s.mean, inp.V, inp.criteria, inp.scales, inp.raw)
        if not np.isnan(inp.raw[:, v.criterion]).any()
    ]


def _compute_base(snapshot: DecisionSnapshot, inp: EngineInput) -> dict[str, Any]:
    post = fit(inp.seed, inp.K, inp.answers)
    s = summary(post, inp.V)
    V = inp.V
    w0 = prior_particles(inp.seed, inp.K)
    st = stability(post, V, inp.seed)
    conds = change_conditions(s.mean, V)
    top = ranking(s.mean, V)[0]
    top_p = ah.top_probability(s, V)
    changes = ah.change_conditions_payload(
        conds,
        top,
        inp.criterion_ids,
        inp.criterion_names,
        inp.option_ids,
        inp.option_names,
        _usable_value_conditions(s, inp),
        [c.unit or "" for c in snapshot.criteria],
        [list(c.levels or ()) for c in snapshot.criteria],
    )
    return {
        "recommendation": {"optionId": inp.option_ids[top], "name": inp.option_names[top], "topProbability": _p(top_p)},
        "rankings": ah.rankings_items(s.mean, V, s.p_best, inp.option_ids, inp.option_names),
        "weights": ah.weights_items(s, inp.criterion_ids, inp.criterion_names, inp.answers, w0),
        "stability": ah.stability_payload(st, s.p_best, conds, inp.option_ids, inp.option_names, inp.criterion_names),
        "changeConditions": changes["items"],
        "hiddenPreferences": ah.hidden_preferences_payload(
            detect(w0, inp.answers), inp.criterion_ids, inp.criterion_names
        ),
        "excludedOptions": _excluded_payload(snapshot, inp),
        "lowConfidence": ah.low_confidence(top_p),
        "computedAt": _now(),
    }


async def _base(ctx: _Ctx, stores: Stores) -> tuple[dict[str, Any], CachedAnalysis]:
    """캐시가 유효하면 그 본문, 아니면 계산해서 캐시에 넣는다."""
    snap = ctx.snapshot
    cached = await stores.analysis.get(snap.id)
    if cached is not None and cached.decision_version == snap.version:
        return cached.payload, cached
    fresh = CachedAnalysis(snap.version, _compute_base(snap, ctx.inp), None)
    await stores.analysis.put(snap.id, fresh)
    return fresh.payload, fresh


# --------------------------------------------------------------------------------------------------
# AI 문장 (optionSummaries·explanation)
# --------------------------------------------------------------------------------------------------


def _matrix_for_ai(snapshot: DecisionSnapshot, inp: EngineInput) -> dict[str, Any]:
    vals = _value_map(snapshot)
    return {
        "options": [
            {
                "optionId": oid,
                "name": inp.option_names[i],
                "values": [
                    {"criterion": c.name, "value": _value_label(c, vals.get((oid, c.id)))} for c in snapshot.criteria
                ],
            }
            for i, oid in enumerate(inp.option_ids)
        ]
    }


def _template_texts(ctx: _Ctx, base: Mapping[str, Any]) -> dict[str, Any]:
    mean = [w["mean"] for w in base["weights"]]
    return {
        "optionSummaries": _template_summaries(ctx.snapshot, ctx.inp, mean),
        "explanation": _template_explanation(ctx.snapshot, ctx.inp, base),
    }


async def _ai_texts(
    ctx: _Ctx, base: Mapping[str, Any], llm: LLMSource, on_usage: UsageCallback | None
) -> dict[str, Any]:
    """AI로 만들되 실패·검증 탈락은 각각 템플릿으로 대체한다 (AI는 선택 사항)."""
    texts = _template_texts(ctx, base)
    with contextlib.suppress(LLMError, LLMConfigError):
        got = await summarize_options(llm, _matrix_for_ai(ctx.snapshot, ctx.inp), on_usage=on_usage)
        if got is not None:
            by_id = {i.option_id: i for i in got.items}
            texts["optionSummaries"] = [
                {
                    "optionId": s["optionId"],
                    "pros": list(by_id[s["optionId"]].pros),
                    "cons": list(by_id[s["optionId"]].cons),
                }
                if s["optionId"] in by_id
                else s
                for s in texts["optionSummaries"]
            ]
    with contextlib.suppress(LLMError, LLMConfigError):
        ex = await explain_result(llm, {k: v for k, v in base.items() if k != "computedAt"}, on_usage=on_usage)
        if ex is not None:
            texts["explanation"] = {
                "summary": ex.summary,
                "reasons": list(ex.reasons),
                "caveats": list(ex.caveats),
                "source": ex.source,
            }
    return texts


async def _texts(
    ctx: _Ctx,
    stores: Stores,
    base: Mapping[str, Any],
    cached: CachedAnalysis,
    llm: LLMSource | None,
    on_usage: UsageCallback | None,
) -> dict[str, Any]:
    if cached.explanation is not None:
        return cached.explanation
    if llm is None:
        return _template_texts(ctx, base)
    texts = await _ai_texts(ctx, base, llm, on_usage)
    await stores.analysis.put(ctx.snapshot.id, CachedAnalysis(cached.decision_version, dict(base), texts))
    return texts


# --------------------------------------------------------------------------------------------------
# 39 ~ 45
# --------------------------------------------------------------------------------------------------


async def get_analysis(
    snapshot: DecisionSnapshot, stores: Stores, *, llm: LLMSource | None = None, on_usage: UsageCallback | None = None
) -> dict[str, Any]:
    """39번. FINALIZED면 확정 시점 결과, 아니면 (캐시된) 계산 결과. llm이 없으면 템플릿 설명."""
    ctx = await _ctx(snapshot, stores, Action.ANALYSIS_READ)
    stored = await _finalized_snapshot(snapshot, stores)
    if stored is not None:
        return stored
    base, cached = await _base(ctx, stores)
    texts = await _texts(ctx, stores, base, cached, llm, on_usage)
    return {
        "provisional": is_provisional(snapshot.status, ctx.answered),
        "recommendation": copy.deepcopy(base["recommendation"]),
        "rankings": copy.deepcopy(base["rankings"]),
        "weights": copy.deepcopy(base["weights"]),
        "stability": copy.deepcopy(base["stability"]),
        "changeConditions": copy.deepcopy(base["changeConditions"]),
        "hiddenPreferences": copy.deepcopy(base["hiddenPreferences"]),
        "excludedOptions": copy.deepcopy(base["excludedOptions"]),
        "optionSummaries": copy.deepcopy(texts["optionSummaries"]),
        "explanation": copy.deepcopy(texts["explanation"]),
        "lowConfidence": base["lowConfidence"],
        "computedAt": base["computedAt"],
    }


async def _part(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """40·41·44·45번이 꺼내 쓰는 39번 본문 조각 (FINALIZED는 저장된 확정 결과)."""
    ctx = await _ctx(snapshot, stores, Action.ANALYSIS_READ)
    stored = await _finalized_snapshot(snapshot, stores)
    if stored is not None:
        return stored
    base, _ = await _base(ctx, stores)
    return base


async def get_weights(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """40번."""
    part = await _part(snapshot, stores)
    return {"items": copy.deepcopy(part["weights"]), "intervalLevel": ah.INTERVAL_LEVEL}


async def get_rankings(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """41번."""
    part = await _part(snapshot, stores)
    return {"items": copy.deepcopy(part["rankings"])}


async def get_stability(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """44번."""
    part = await _part(snapshot, stores)
    out: dict[str, Any] = copy.deepcopy(part["stability"])
    return out


async def get_change_conditions(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """45번. 가중치 항목 + (P1) 값 항목."""
    part = await _part(snapshot, stores)
    return {"currentTop": part["recommendation"]["optionId"], "items": copy.deepcopy(part["changeConditions"])}


# --------------------------------------------------------------------------------------------------
# 42 · 43 · 46 (P1)
# --------------------------------------------------------------------------------------------------


async def get_scores(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """42번. contribution = 평균 가중치 × 정규화 값."""
    ctx = await _ctx(snapshot, stores, Action.ANALYSIS_READ)
    inp = ctx.inp
    _, s = ctx.fit()
    vals = _value_map(snapshot)
    items: list[dict[str, Any]] = []
    for i, oid in enumerate(inp.option_ids):
        cells: list[dict[str, Any]] = []
        for k, c in enumerate(snapshot.criteria):
            raw = _raw_value(c, vals.get((oid, c.id)))
            if raw is None:  # 값 입력 전인 칸은 셀을 만들지 않는다
                continue
            v = float(inp.V[i, k])
            cells.append(
                {
                    "criterionId": c.id,
                    "rawValue": round(raw, 4),
                    "normalized": _p(v),
                    "contribution": _p(float(s.mean[k]) * v),
                }
            )
        items.append({"optionId": oid, "cells": cells})
    return {"criteria": list(inp.criterion_ids), "items": items}


async def get_sensitivity(
    snapshot: DecisionSnapshot, stores: Stores, criterion_id: str | None = None
) -> dict[str, Any]:
    """43번. 기준 하나(생략하면 전체)의 가중치를 0~1로 움직일 때 선택지별 점수와 1위가 바뀌는 지점."""
    ctx = await _ctx(snapshot, stores, Action.ANALYSIS_READ)
    inp = ctx.inp
    if criterion_id is not None and criterion_id not in inp.criterion_ids:
        raise validation_error("알 수 없는 criterionId입니다.", {"field": "criterionId", "value": criterion_id})
    _, s = ctx.fit()
    conds = change_conditions(s.mean, inp.V)
    top = ranking(s.mean, inp.V)[0]
    items: list[dict[str, Any]] = []
    for k, cid in enumerate(inp.criterion_ids):
        if criterion_id is not None and cid != criterion_id:
            continue
        curve = sensitivity(s.mean, inp.V, k)
        c: ChangeCondition = conds[k]
        crossovers: list[dict[str, Any]] = []
        if c.threshold is not None and c.new_top is not None:
            crossovers.append(
                {
                    "weight": round(c.threshold, _WEIGHT_DIGITS),
                    "from": inp.option_ids[top],
                    "to": inp.option_ids[c.new_top],
                }
            )
        items.append(
            {
                "criterionId": cid,
                "currentWeight": _p(float(s.mean[k])),
                "points": [
                    {
                        "weight": round(float(t), _P),
                        "scores": {oid: _p(float(curve.scores[j, i])) for i, oid in enumerate(inp.option_ids)},
                    }
                    for j, t in enumerate(curve.t)
                ],
                "crossovers": crossovers,
            }
        )
    return {"items": items}


def _parse_weights(weights: object, criterion_ids: Sequence[str]) -> NDArray[np.float64]:
    """요청 가중치 → 기준 순서의 정규화된 배열. 형식·범위가 틀리면 400 VALIDATION_ERROR."""
    if not isinstance(weights, Sequence) or isinstance(weights, str | bytes):
        raise validation_error("weights는 목록이어야 합니다.", {"field": "weights"})
    given: dict[str, float] = {}
    for item in weights:
        if not isinstance(item, Mapping) or "criterionId" not in item or "weight" not in item:
            raise validation_error("weights 항목은 {criterionId, weight} 형식이어야 합니다.", {"field": "weights"})
        cid, w = item["criterionId"], item["weight"]
        if not isinstance(cid, str) or cid not in criterion_ids:
            raise validation_error("알 수 없는 criterionId입니다.", {"field": "weights.criterionId", "value": str(cid)})
        if cid in given:
            raise validation_error("criterionId가 중복되었습니다.", {"field": "weights.criterionId", "value": cid})
        if isinstance(w, bool) or not isinstance(w, int | float) or not math.isfinite(w) or w < 0:
            raise validation_error("weight는 0 이상의 유한한 숫자여야 합니다.", {"field": "weights.weight"})
        given[cid] = float(w)
    missing = [c for c in criterion_ids if c not in given]
    if missing:
        raise validation_error("모든 기준의 가중치가 필요합니다.", {"field": "weights", "missing": missing})
    arr = np.array([given[c] for c in criterion_ids])
    total = float(arr.sum())
    if total <= 0:
        raise validation_error("가중치의 합이 0보다 커야 합니다.", {"field": "weights"})
    out: NDArray[np.float64] = arr / total
    return out


async def simulate(snapshot: DecisionSnapshot, stores: Stores, weights: object) -> dict[str, Any]:
    """46번. 저장하지 않는 순수 계산. weights는 [{"criterionId", "weight"}, ...]."""
    ctx = await _ctx(snapshot, stores, Action.SIMULATE)
    inp = ctx.inp
    w = _parse_weights(weights, inp.criterion_ids)
    _, s = ctx.fit()
    order = ranking(w, inp.V)
    u = scores(w, inp.V)
    return {
        "normalizedWeights": [
            {"criterionId": cid, "weight": round(float(w[k]), _WEIGHT_DIGITS)}
            for k, cid in enumerate(inp.criterion_ids)
        ],
        "rankings": [
            {"rank": r, "optionId": inp.option_ids[i], "score": _p(float(u[i]))} for r, i in enumerate(order, start=1)
        ],
        "changedFromCurrent": order != ranking(s.mean, inp.V),
    }
