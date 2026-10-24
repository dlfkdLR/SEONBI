"""DB 행(ports.py의 데이터 클래스) ↔ 엔진 입력 변환 (역할 문서 5.2, 개발기획서 5.1·5.5·8.1).

- 엔진은 기준·선택지를 인덱스로 가리키고, 저장소는 id로 가리킨다. 이 모듈이 그 사이를 맡는다.
- 질문 `spec`(jsonb)에는 기준 **id**를 저장한다(기준이 추가·삭제되면 인덱스가 바뀌기 때문).
- 답변 `response`(jsonb): `{"choice": "A"|"B"}`(ab_*), `{"value": -5..5}`(slider_pair), `{"skip": true}`.
- DB·FastAPI에 의존하지 않는다. 입력 검사 실패는 `FlowError(400, VALIDATION_ERROR)`.

명세의 모호한 부분 (가정):
- 값이 하나라도 비어 있는 기준(선택지 값이 모두 채워지지 않은 기준)은 정규화값을 전부 0.5로 두어 질문 후보에서
  빠지게 한다. 질문 도중 기준을 추가하면 값 입력 전까지 이런 상태가 되기 때문이다(명세에 없음).
- 지워진 기준을 가리키는 질문·답변은 엔진 입력에서 뺀다(답변은 저장소가 is_valid=false로 만든다).
- 단계형 범위 표기(`CriterionMeta.low_name/high_name`)는 값이 가장 나쁜/좋은 선택지 이름이다
  (score는 값이 큰 쪽, rank는 그룹 번호가 작은 쪽이 좋음). 동점이면 앞선 선택지.
- `{"skip": false}`와 알 수 없는 키는 형식 오류로 본다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np
from numpy.typing import NDArray

from app.engine.likelihood import (
    SLIDER_MAX,
    CriteriaABSpec,
    QuestionSpec,
    Response,
    ScenarioSpec,
    SliderSpec,
    preference_kind,
)
from app.engine.normalize import Condition, Criterion, RawValues, Scale, excluded_options, freeze_scales, normalize
from app.engine.posterior import Answer

from .flow_errors import validation_error
from .ports import AnswerRow, CriterionRow, DecisionSnapshot, PreferenceKind, QuestionRow, QuestionType
from .question_text import CriterionMeta

SLIDER_MIN = -SLIDER_MAX


@dataclass(frozen=True, eq=False)
class EngineInput:
    criterion_ids: tuple[str, ...]  # position 순 (엔진 열 순서)
    criterion_names: tuple[str, ...]
    option_ids: tuple[str, ...]  # 제외되지 않은 선택지만 (엔진 행 순서)
    option_names: tuple[str, ...]
    excluded: dict[str, list[str]]  # 선택지 id → 미충족 조건 id
    criteria: tuple[Criterion, ...]
    scales: dict[str, Scale]  # 숫자형 기준만
    V: NDArray[np.float64]  # (선택지, 기준) 정규화 행렬
    raw: NDArray[np.float64]  # (선택지, 기준) 원래 값. 숫자형·단계형(순위는 그룹 번호)만, 나머지는 NaN
    answers: tuple[Answer, ...]  # 유효 답변, seq 순 (가상 답변 포함, origin 표시)
    history: tuple[QuestionSpec, ...]  # origin=engine 질문의 스펙 (건너뛴 것 포함), seq 순
    covered: frozenset[int]  # 유효하고 건너뛰지 않은 답변(가상 포함)에 나온 기준 인덱스
    K: int
    seed: int
    metas: tuple[CriterionMeta, ...]  # 질문 문장 생성용
    category: str


# --------------------------------------------------------------------------------------------------
# id ↔ 인덱스
# --------------------------------------------------------------------------------------------------


def index_of(ids: Sequence[str], item_id: str) -> int:
    try:
        return list(ids).index(item_id)
    except ValueError:
        raise ValueError(f"알 수 없는 id: {item_id!r}") from None


def id_of(ids: Sequence[str], idx: int) -> str:
    return ids[idx]


# --------------------------------------------------------------------------------------------------
# 질문 spec ↔ dict
# --------------------------------------------------------------------------------------------------

_KIND_TO_TYPE: dict[str, QuestionType] = {
    "slider_pair": "slider_pair",
    "ab_criteria": "ab_criteria",
    "ab_scenario": "ab_scenario",
}


def question_type_of(spec: QuestionSpec) -> QuestionType:
    if isinstance(spec, SliderSpec):
        return "slider_pair"
    if isinstance(spec, CriteriaABSpec):
        return "ab_criteria"
    return "ab_scenario"


def spec_to_dict(spec: QuestionSpec, criterion_ids: Sequence[str]) -> dict[str, Any]:
    """엔진 스펙 → questions.spec (기준은 id). 상황형은 모든 기준의 정규화 Δ를 id로 담는다."""
    base: dict[str, Any] = {"kind": question_type_of(spec), "a": criterion_ids[spec.a], "b": criterion_ids[spec.b]}
    if isinstance(spec, ScenarioSpec):
        base["delta"] = {cid: float(d) for cid, d in zip(criterion_ids, spec.delta, strict=True)}
        base["rawA"] = float(spec.raw_a)
        base["rawB"] = float(spec.raw_b)
    return base


def spec_from_dict(d: Mapping[str, Any], criterion_ids: Sequence[str]) -> QuestionSpec:
    """questions.spec → 엔진 스펙. 삭제된 기준 id면 ValueError. 상황형 Δ에 없는 기준(나중에 추가된 기준)은 0."""
    kind = d.get("kind")
    if kind not in _KIND_TO_TYPE:
        raise ValueError(f"알 수 없는 질문 kind: {kind!r}")
    a, b = index_of(criterion_ids, d["a"]), index_of(criterion_ids, d["b"])
    if kind == "slider_pair":
        return SliderSpec(a, b)
    if kind == "ab_criteria":
        return CriteriaABSpec(a, b)
    delta_by_id: Mapping[str, float] = d["delta"]
    delta = tuple(float(delta_by_id.get(cid, 0.0)) for cid in criterion_ids)
    return ScenarioSpec(a, b, delta, float(d["rawA"]), float(d["rawB"]))


def preference_kind_for(spec: QuestionSpec) -> PreferenceKind:
    """answers.preference_kind: 슬라이더·기준 A/B는 stated, 상황형은 revealed."""
    return preference_kind(spec)


# --------------------------------------------------------------------------------------------------
# 응답 형식
# --------------------------------------------------------------------------------------------------


def parse_response(question_type: str, response: object) -> Response:
    """answers.response → 엔진 응답. "잘 모르겠어요"는 None. 형식·범위가 틀리면 400 VALIDATION_ERROR."""
    details = {"field": "response", "questionType": question_type}
    if not isinstance(response, Mapping):
        raise validation_error("response는 객체여야 합니다.", details)
    keys = set(response)
    if keys == {"skip"}:
        if response["skip"] is not True:
            raise validation_error("skip은 true만 보낼 수 있습니다.", details)
        return None
    if question_type == "slider_pair":
        if keys != {"value"}:
            raise validation_error('슬라이더 응답은 {"value": -5..5} 형식이어야 합니다.', details)
        v = response["value"]
        if isinstance(v, bool) or not isinstance(v, int) or not SLIDER_MIN <= v <= SLIDER_MAX:
            raise validation_error(f"value는 {SLIDER_MIN}~{SLIDER_MAX} 정수여야 합니다.", details)
        return v
    if question_type in ("ab_criteria", "ab_scenario"):
        if keys != {"choice"}:
            raise validation_error('A/B 응답은 {"choice": "A"|"B"} 형식이어야 합니다.', details)
        c = response["choice"]
        if c == "A":
            return "A"
        if c == "B":
            return "B"
        raise validation_error("choice는 A 또는 B여야 합니다.", details)
    raise validation_error("이 질문 유형에는 답변할 수 없습니다.", details)


def response_to_dict(question_type: str, parsed: Response) -> dict[str, Any]:
    """엔진 응답 → answers.response (정규화된 저장 형식)."""
    if parsed is None:
        return {"skip": True}
    if question_type == "slider_pair":
        return {"value": parsed}
    return {"choice": parsed}


# --------------------------------------------------------------------------------------------------
# 행 → 엔진 입력
# --------------------------------------------------------------------------------------------------


def _engine_criterion(c: CriterionRow) -> Criterion:
    return Criterion(
        id=c.id,
        type=c.type,
        direction=c.direction or "higher_better",
        levels=len(c.levels) if c.levels else None,
        input_mode=c.input_mode,
        scale_min=c.scale_min,
        scale_max=c.scale_max,
    )


def _raw_values(snapshot: DecisionSnapshot) -> dict[tuple[str, str], float | int | bool]:
    by_id = {c.id: c for c in snapshot.criteria}
    option_ids = {o.id for o in snapshot.options}
    out: dict[tuple[str, str], float | int | bool] = {}
    for v in snapshot.values:
        c = by_id.get(v.criterion_id)
        if c is None or v.option_id not in option_ids:
            continue
        x: float | int | bool | None
        if c.type == "boolean":
            x = v.value_bool
        elif c.type == "rating" and c.input_mode == "rank":
            x = v.value_rank
        else:
            x = v.value_num
        if x is not None:
            out[(v.option_id, c.id)] = x
    return out


def _conditions(snapshot: DecisionSnapshot) -> list[Condition]:
    return [
        Condition(c.id, c.kind, c.criterion_id, c.operator, c.value, frozenset(c.satisfied_by))
        for c in snapshot.conditions
    ]


def _kept_options(snapshot: DecisionSnapshot, excluded: Mapping[str, Any]) -> list[Any]:
    return [o for o in snapshot.options if o.id not in excluded]


def _complete_criteria(snapshot: DecisionSnapshot, values: RawValues, kept_ids: Sequence[str]) -> list[CriterionRow]:
    """모든 (제외되지 않은) 선택지의 값이 채워진 기준."""
    return [c for c in snapshot.criteria if all((oid, c.id) in values for oid in kept_ids)]


def compute_scales(snapshot: DecisionSnapshot) -> dict[str, Scale]:
    """숫자형 기준의 척도. 고정된 기준은 저장된 값, 아니면 제외되지 않은 선택지 값의 min~max(사용자 지정 우선)."""
    values = _raw_values(snapshot)
    excluded = excluded_options([o.id for o in snapshot.options], values, _conditions(snapshot))
    scales: dict[str, Scale] = {}
    live: list[Criterion] = []
    for c in snapshot.criteria:
        if c.type != "numeric":
            continue
        if c.scale_frozen and c.scale_min is not None and c.scale_max is not None:
            scales[c.id] = Scale(c.scale_min, c.scale_max)
        else:
            live.append(_engine_criterion(c))
    scales.update(freeze_scales(live, values, excluded))
    return scales


def pending_scale_freeze(snapshot: DecisionSnapshot) -> dict[str, Scale]:
    """아직 고정되지 않은 숫자형 기준 중 값이 모두 채워져 지금 고정할 수 있는 것의 척도."""
    values = _raw_values(snapshot)
    excluded = excluded_options([o.id for o in snapshot.options], values, _conditions(snapshot))
    kept_ids = [o.id for o in snapshot.options if o.id not in excluded]
    complete = {c.id for c in _complete_criteria(snapshot, values, kept_ids)}
    live = [_engine_criterion(c) for c in snapshot.criteria if c.type == "numeric" and not c.scale_frozen]
    scales = freeze_scales([c for c in live if c.id in complete], values, excluded)
    return scales


def criterion_metas(snapshot: DecisionSnapshot) -> tuple[CriterionMeta, ...]:
    """질문 문장용 기준 정보. 단계형은 양 끝 선택지 이름을 범위 표기에 쓴다(5.1)."""
    values = _raw_values(snapshot)
    excluded = excluded_options([o.id for o in snapshot.options], values, _conditions(snapshot))
    kept = _kept_options(snapshot, excluded)
    metas: list[CriterionMeta] = []
    for c in snapshot.criteria:
        low = high = None
        if c.type == "rating":
            sign = -1.0 if c.input_mode == "rank" else 1.0  # rank는 그룹 번호가 작을수록 좋음
            scored = [(sign * float(values[(o.id, c.id)]), o.name) for o in kept if (o.id, c.id) in values]
            if scored:
                low = min(scored, key=lambda t: t[0])[1]
                high = max(scored, key=lambda t: t[0])[1]
        metas.append(
            CriterionMeta(
                id=c.id,
                name=c.name,
                type=c.type,
                direction=c.direction or "higher_better",
                unit=c.unit,
                levels=list(c.levels) if c.levels else None,
                input_mode=c.input_mode,
                low_name=low,
                high_name=high,
            )
        )
    return tuple(metas)


def build_engine_input(
    snapshot: DecisionSnapshot, questions: Sequence[QuestionRow], answers: Sequence[AnswerRow]
) -> EngineInput:
    """스냅샷·질문·답변 행 → 엔진 입력. questions·answers는 저장소에서 읽은 그대로(무효 답변 포함)."""
    criterion_ids = tuple(c.id for c in snapshot.criteria)
    criteria = tuple(_engine_criterion(c) for c in snapshot.criteria)
    values = _raw_values(snapshot)
    conditions = _conditions(snapshot)
    scales = compute_scales(snapshot)

    excluded = excluded_options([o.id for o in snapshot.options], values, conditions)
    kept = _kept_options(snapshot, excluded)
    kept_ids = [o.id for o in kept]
    complete_rows = _complete_criteria(snapshot, values, kept_ids)
    complete_ids = {c.id for c in complete_rows}

    V = np.full((len(kept), len(criteria)), 0.5)
    if complete_rows:
        matrix, _ = normalize(
            values,
            [c for c in criteria if c.id in complete_ids],
            scales,
            option_ids=[o.id for o in snapshot.options],
            conditions=conditions,
        )
        cols = [k for k, cid in enumerate(criterion_ids) if cid in complete_ids]
        V[:, cols] = matrix.values
    raw = np.full((len(kept), len(criteria)), np.nan)
    for k, c in enumerate(snapshot.criteria):
        if c.type == "boolean":
            continue
        for i, o in enumerate(kept):
            x = values.get((o.id, c.id))
            if x is not None:
                raw[i, k] = float(x)

    qmap = {q.id: q for q in questions}
    specs: dict[str, QuestionSpec] = {}
    for q in questions:
        try:
            specs[q.id] = spec_from_dict(q.spec, criterion_ids)
        except (ValueError, KeyError):
            continue  # 지워진 기준을 가리키는 질문

    history = tuple(
        specs[q.id] for q in sorted(questions, key=lambda q: q.seq) if q.origin == "engine" and q.id in specs
    )

    engine_answers: list[Answer] = []
    covered: set[int] = set()
    ordered = sorted(
        (a for a in answers if a.is_valid and a.question_id in qmap), key=lambda a: qmap[a.question_id].seq
    )
    for a in ordered:
        q = qmap[a.question_id]
        spec = specs.get(q.id)
        if spec is None:
            continue
        resp = parse_response(q.type, a.response)
        engine_answers.append(Answer(spec, resp, q.origin))
        if resp is not None:
            covered.update((spec.a, spec.b))

    return EngineInput(
        criterion_ids=criterion_ids,
        criterion_names=tuple(c.name for c in snapshot.criteria),
        option_ids=tuple(kept_ids),
        option_names=tuple(o.name for o in kept),
        excluded=excluded,
        criteria=criteria,
        scales=scales,
        V=V,
        raw=raw,
        answers=tuple(engine_answers),
        history=history,
        covered=frozenset(covered),
        K=len(criteria),
        seed=snapshot.rng_seed,
        metas=criterion_metas(snapshot),
        category=snapshot.category,
    )
