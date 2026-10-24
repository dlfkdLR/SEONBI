"""질문 흐름 서비스: 7.3의 31~38번 로직 (역할 문서 5.1·5.2, 개발기획서 4.2·4.3·5.5·5.10·5.14).

- 라우터·DB·FastAPI에 의존하지 않는다. 이미 읽은 `DecisionSnapshot`과 `ports.Stores`만 받는다.
- 응답은 7.3 키 그대로의 일반 dict(camelCase). 오류는 `FlowError`로 낸다(라우터가 7.1 JSON으로 바꾼다).
- 스냅샷은 호출 시점의 값이다. 이 모듈이 상태·상한·척도를 바꿔도 넘겨 받은 스냅샷 객체는 바뀌지 않는다.
  호출하는 쪽은 다음 요청에서 다시 읽은 스냅샷을 넘긴다.

결정 사항 (사용자 지시):
- `decisions.question_limit`: 첫 라운드는 NULL(`max_questions(K)`는 서비스가 계산), 추가 라운드는
  `extra_round_limit(답변 수)`를 저장하고 `round_start = question_limit − EXTRA_ROUND_QUESTIONS`.
  둘 다 `should_stop`에 그대로 넘긴다.
- 질문 `spec`은 기준 id로 저장한다(engine_bridge).

명세의 모호한 부분 (가정):
- 31번: READINESS는 호출하는 쪽이 `missing`으로 알려 준다. DRAFT·ANALYZED에서 비어 있지 않으면 422 NOT_READY.
  QUESTIONING(멱등)에서는 보지 않는다. 물어볼 기준 쌍이 하나도 없으면(모든 기준 값이 같음 등) 상태를 바꾸지 않고
  422 NOT_READY(`missing: ["NO_QUESTION_CANDIDATES"]`).
- 31번 `round`: QUESTIONING 재호출은 `question_limit`이 NULL이 아니면 "extra"다(첫 라운드 끝무렵에 기준 추가로 상한이
  올라간 경우도 "extra"로 보인다).
- 31번 첫 호출에서만 상한을 쓴다. 상태 전이·척도 고정은 `bump_version`을 부르지 않는다(답변이 바뀌지 않음).
- 32번 ANALYZED·FINALIZED의 `reason`: 종료 이유를 저장하는 컬럼이 없어, 지금 상태로 `should_stop`을 다시 계산한
  이유를 쓰고 종료 조건이 안 걸리면(사용자 조기 종료 등) "USER_STOPPED"를 쓴다. 33번의 ANALYZED·FINALIZED
  `shouldStop`도 같은 값이다.
- 엔진이 더 낼 질문이 없을 때(`next_question`이 None) 32·37번은 "LOW_INFO_GAIN"으로 끝낸다.
- 33번 `remainingUncertainty`: 10~90% 구간 폭이 REMAINING_MIN_WIDTH 이상인 기준을 폭 내림차순으로 최대
  REMAINING_MAX_ITEMS개(명세에 규칙이 없어 상수로 둠).
- 34번 `expectedRange`: 첫 라운드 [3, max_questions(K)], 추가 라운드 [답변 수 + 1, question_limit]
  (하한이 상한보다 크면 상한으로 맞춘다). ANALYZED·FINALIZED에서도 저장된 `question_limit`으로 같은 식을 쓴다.
- 38번 응답: 37번의 `estimate`와 같은 값을 `{"estimate": {...}}`로 감싸 돌려준다.
- 지워진 기준을 가리키는 미답변 질문은 건너뛴다(저장소에 질문 삭제 수단이 없어 행은 남는다).
- 기준 추가(`on_criterion_added`)의 스냅샷은 추가 후 상태(새 기준 포함)로 가정한다. 값이 비어 있는 새 기준은 값이
  채워질 때까지 질문 후보에서 빠지고, 척도는 값이 채워진 뒤 질문 흐름이 처음 도는 때(31·32·37·38번) 고정한다.
- 기준 추가는 `bump_version`을 부르지 않는다(구성 수정은 B의 몫). 삭제는 부른다.
- AI 문장(`phraser`)은 질문 객체(id·seq 포함) 복사본을 받는다.
"""

from __future__ import annotations

import os
import time
import uuid
from collections.abc import Awaitable, Callable, Sequence
from copy import deepcopy
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any

from app.engine.decision_analysis import ranking
from app.engine.likelihood import SliderSpec
from app.engine.posterior import Posterior, Summary, fit, summary
from app.engine.question_selector import best_info_gain, score_candidates
from app.engine.question_selector import next_question as engine_next_question
from app.engine.stopping import (
    EXTRA_ROUND_QUESTIONS,
    StopReason,
    counted_answers,
    extra_round_limit,
    max_questions,
    raised_limit,
    should_stop,
)

from . import analysis_helpers as ah
from .engine_bridge import (
    EngineInput,
    build_engine_input,
    compute_scales,
    criterion_metas,
    parse_response,
    pending_scale_freeze,
    preference_kind_for,
    question_type_of,
    response_to_dict,
    spec_from_dict,
    spec_to_dict,
)
from .flow_errors import FlowError, not_found, raise_if_violation
from .ports import AnswerRow, DecisionSnapshot, PhrasingSource, QuestionRow, Stores
from .question_text import build_question
from .state_rules import Action, Status, after_criterion_added, after_question_start, check

if TYPE_CHECKING:
    from app.ai.calls import PhrasedQuestion

Phraser = Callable[[dict[str, Any]], "Awaitable[PhrasedQuestion | None]"]

REMAINING_MIN_WIDTH = 0.2  # 33번 remainingUncertainty에 올리는 최소 구간 폭 (명세에 없음)
REMAINING_MAX_ITEMS = 3
EIG_DIGITS = 3  # 33번 bestNextInfoGain 반올림 (종료 기준 0.03 bit과 구별되도록 확률보다 한 자리 더)
FALLBACK_REASON: StopReason = "USER_STOPPED"
NO_CANDIDATE_REASON: StopReason = "LOW_INFO_GAIN"


# --------------------------------------------------------------------------------------------------
# 공통
# --------------------------------------------------------------------------------------------------


def new_id() -> str:
    """UUID v7 문자열 (7.1). 파이썬 3.13에는 uuid7이 없어 직접 만든다."""
    ms = time.time_ns() // 1_000_000
    rand = int.from_bytes(os.urandom(10), "big")
    value = ((ms & (2**48 - 1)) << 80) | (0x7 << 76) | ((rand >> 68) << 64) | (0b10 << 62) | (rand & (2**62 - 1))
    return str(uuid.UUID(int=value))


def _now() -> str:
    return datetime.now(UTC).isoformat()


@dataclass
class _Ctx:
    snapshot: DecisionSnapshot
    questions: list[QuestionRow]
    answers: list[AnswerRow]
    inp: EngineInput

    @property
    def answered(self) -> int:
        return ah.answered_count(self.inp.answers)


def _apply_freeze(snapshot: DecisionSnapshot, scales: dict[str, Any]) -> DecisionSnapshot:
    if not scales:
        return snapshot
    criteria = tuple(
        replace(c, scale_min=scales[c.id].min, scale_max=scales[c.id].max, scale_frozen=True) if c.id in scales else c
        for c in snapshot.criteria
    )
    return replace(snapshot, criteria=criteria)


async def _persist_freeze(stores: Stores, snapshot: DecisionSnapshot, scales: dict[str, Any]) -> None:
    if scales:
        await stores.decisions.freeze_scales(snapshot.id, {cid: (s.min, s.max) for cid, s in scales.items()})


async def _read(snapshot: DecisionSnapshot, stores: Stores) -> _Ctx:
    questions = list(await stores.questions.list_questions(snapshot.id))
    answers = list(await stores.questions.list_answers(snapshot.id))
    return _Ctx(snapshot, questions, answers, build_engine_input(snapshot, questions, answers))


async def _load(snapshot: DecisionSnapshot, stores: Stores, *, freeze: bool = False) -> _Ctx:
    """질문·답변을 읽어 엔진 입력을 만든다. freeze=True면 아직 안 고정된 척도를 (가능하면) 고정해 저장한다."""
    if freeze and snapshot.status is not Status.DRAFT:
        scales = pending_scale_freeze(snapshot)
        await _persist_freeze(stores, snapshot, scales)
        snapshot = _apply_freeze(snapshot, scales)
    return await _read(snapshot, stores)


async def _reload(ctx: _Ctx, stores: Stores) -> _Ctx:
    return await _read(ctx.snapshot, stores)


def _posterior(inp: EngineInput) -> tuple[Posterior, Summary]:
    post = fit(inp.seed, inp.K, inp.answers)
    return post, summary(post, inp.V)


def _limits(snapshot: DecisionSnapshot) -> tuple[int | None, int | None]:
    """(question_limit, round_start). NULL이면 둘 다 None (첫 라운드)."""
    ql = snapshot.question_limit
    return ql, None if ql is None else ql - EXTRA_ROUND_QUESTIONS


def _stop_decision(ctx: _Ctx, post: Posterior) -> tuple[bool, StopReason | None, float]:
    inp = ctx.inp
    eig = best_info_gain(post, inp.V, inp.criteria, scales=inp.scales)
    limit, start = _limits(ctx.snapshot)
    stop, reason = should_stop(post, inp.V, inp.answers, eig, question_limit=limit, round_start=start)
    return stop, reason, eig


def _qobj(row: QuestionRow) -> dict[str, Any]:
    return {"id": row.id, "seq": row.seq, **row.display}


def _answer_obj(row: AnswerRow) -> dict[str, Any]:
    return {
        "id": row.id,
        "questionId": row.question_id,
        "response": row.response,
        "isValid": row.is_valid,
        "createdAt": row.created_at,
    }


def _unanswered(ctx: _Ctx) -> QuestionRow | None:
    """답변 행이 없는 가장 앞선 질문. 지워진 기준을 가리키는 질문은 건너뛴다."""
    answered_ids = {a.question_id for a in ctx.answers}
    for q in sorted(ctx.questions, key=lambda q: q.seq):
        if q.id in answered_ids:
            continue
        try:
            spec_from_dict(q.spec, ctx.inp.criterion_ids)
        except (ValueError, KeyError):
            continue
        return q
    return None


def _expected_range(ctx: _Ctx) -> list[int]:
    ql = ctx.snapshot.question_limit
    K, answered = ctx.inp.K, ctx.answered
    if ql is None:
        return ah.expected_range("initial", K, answered, max_questions(K))
    lo, hi = ah.expected_range("extra", K, answered, ql)
    return [min(lo, hi), hi]


def _check(action: Action, snapshot: DecisionSnapshot, **details: Any) -> None:
    raise_if_violation(check(action, snapshot.status), {"decisionId": snapshot.id, **details})


async def _final_reason(ctx: _Ctx) -> StopReason:
    post, _ = _posterior(ctx.inp)
    _, reason, _ = _stop_decision(ctx, post)
    return reason or FALLBACK_REASON


# --------------------------------------------------------------------------------------------------
# 질문 만들기
# --------------------------------------------------------------------------------------------------


async def _phrase(
    obj: dict[str, Any], display: dict[str, Any], phraser: Phraser | None
) -> tuple[dict[str, Any], PhrasingSource]:
    """AI 문장을 덮어쓴다 (6.2: title, optionA/B.description, left/right.label만). 실패·None이면 템플릿."""
    if phraser is None:
        return display, "template"
    try:
        result = await phraser(deepcopy(obj))
    except Exception:  # LLM 계층의 어떤 실패도 질문 흐름을 막지 않는다 (6.3)
        return display, "template"
    if result is None:
        return display, "template"
    new = deepcopy(display)
    payload = new["payload"]
    if result.title:
        new["title"] = result.title
    for key, text in (("optionA", result.option_a_description), ("optionB", result.option_b_description)):
        if text and "description" in payload.get(key, {}):
            payload[key]["description"] = text
    for key, text in (("left", result.left_label), ("right", result.right_label)):
        if text and key in payload:
            payload[key]["label"] = text
    return new, result.source


async def _make_question_row(ctx: _Ctx, post: Posterior, phraser: Phraser | None) -> QuestionRow | None:
    """엔진이 다음 질문을 골라 행으로 만든다(저장은 호출하는 쪽). 낼 질문이 없으면 None."""
    inp = ctx.inp
    spec = engine_next_question(post, inp.V, inp.criteria, inp.history, scales=inp.scales, covered=inp.covered)
    if spec is None:
        return None
    candidates = score_candidates(post, inp.V, inp.criteria, inp.history, scales=inp.scales)
    gain = next((float(c.eig) for c in candidates if c.spec == spec), None)
    seq = max((q.seq for q in ctx.questions), default=0) + 1
    qid = new_id()
    display = build_question(spec, inp.metas, inp.scales, inp.category)
    display, source = await _phrase({"id": qid, "seq": seq, **display}, display, phraser)
    return QuestionRow(
        id=qid,
        decision_id=ctx.snapshot.id,
        seq=seq,
        type=question_type_of(spec),
        spec=spec_to_dict(spec, inp.criterion_ids),
        display=display,
        phrasing_source=source,
        origin="engine",
        info_gain=gain,
    )


async def _next_payload(ctx: _Ctx, stores: Stores, phraser: Phraser | None) -> dict[str, Any]:
    """QUESTIONING에서 32번·37번 `next`: 미답변 질문 → 종료 판단 → 새 질문. 종료면 ANALYZED로 바꾼다."""
    pending = _unanswered(ctx)
    if pending is not None:
        return {"done": False, "question": _qobj(pending)}
    post, _ = _posterior(ctx.inp)
    stop, reason, _ = _stop_decision(ctx, post)
    if not stop:
        row = await _make_question_row(ctx, post, phraser)
        if row is not None:
            await stores.questions.add_question(row)
            return {"done": False, "question": _qobj(row)}
        reason = NO_CANDIDATE_REASON
    await stores.decisions.set_status(ctx.snapshot.id, Status.ANALYZED)
    return {"done": True, "reason": reason}


# --------------------------------------------------------------------------------------------------
# 31 · 32
# --------------------------------------------------------------------------------------------------


async def start_questions(
    snapshot: DecisionSnapshot, stores: Stores, *, missing: Sequence[str] = (), phraser: Phraser | None = None
) -> dict[str, Any]:
    """31번 POST /questions/start."""
    status = snapshot.status
    _check(Action.QUESTION_START, snapshot)
    if status is not Status.QUESTIONING and missing:
        raise FlowError(422, "NOT_READY", "질문을 시작할 수 없습니다.", {"missing": list(missing)})

    if status is Status.QUESTIONING:  # 멱등: 현재 질문을 그대로
        ctx = await _load(snapshot, stores, freeze=True)
        extra = ctx.snapshot.question_limit is not None
        row = _unanswered(ctx)
        if row is None:
            post, _ = _posterior(ctx.inp)
            row = await _make_question_row(ctx, post, phraser)
            if row is None:
                raise _no_candidates(snapshot)
            await stores.questions.add_question(row)
        return _start_payload(ctx, row, extra)

    # DRAFT → 첫 라운드, ANALYZED → 추가 라운드. 저장은 질문을 만들 수 있다고 확인한 뒤에 한다.
    scales = pending_scale_freeze(snapshot)
    ctx = await _read(_apply_freeze(snapshot, scales), stores)
    extra = status is Status.ANALYZED
    limit = extra_round_limit(ctx.answered) if extra else None
    row = _unanswered(ctx)
    created = False
    if row is None:
        post, _ = _posterior(ctx.inp)
        row = await _make_question_row(ctx, post, phraser)
        if row is None:
            raise _no_candidates(snapshot)
        created = True
    await _persist_freeze(stores, snapshot, scales)
    if limit != snapshot.question_limit:
        await stores.decisions.set_question_limit(snapshot.id, limit)
    await stores.decisions.set_status(snapshot.id, after_question_start(status))
    if created:
        await stores.questions.add_question(row)
    ctx.snapshot = replace(ctx.snapshot, question_limit=limit, status=Status.QUESTIONING)
    return _start_payload(ctx, row, extra)


def _no_candidates(snapshot: DecisionSnapshot) -> FlowError:
    return FlowError(
        422,
        "NOT_READY",
        "물어볼 수 있는 기준 쌍이 없습니다.",
        {"missing": ["NO_QUESTION_CANDIDATES"], "decisionId": snapshot.id},
    )


def _start_payload(ctx: _Ctx, row: QuestionRow, extra: bool) -> dict[str, Any]:
    return {
        "status": "QUESTIONING",
        "round": "extra" if extra else "initial",
        "question": _qobj(row),
        "progress": {"answered": ctx.answered, "expectedRange": _expected_range(ctx)},
    }


async def next_question(
    snapshot: DecisionSnapshot, stores: Stores, *, phraser: Phraser | None = None
) -> dict[str, Any]:
    """32번 GET /questions/next."""
    _check(Action.QUESTION_READ, snapshot)
    if snapshot.status in (Status.ANALYZED, Status.FINALIZED):
        ctx = await _load(snapshot, stores)
        return {"done": True, "reason": await _final_reason(ctx)}
    ctx = await _load(snapshot, stores, freeze=True)
    return await _next_payload(ctx, stores, phraser)


# --------------------------------------------------------------------------------------------------
# 33 · 34 · 35 · 36
# --------------------------------------------------------------------------------------------------


async def question_status(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """33번 GET /questions/status. 조회만 한다."""
    _check(Action.QUESTION_READ, snapshot)
    ctx = await _load(snapshot, stores)
    inp = ctx.inp
    post, s = _posterior(inp)
    stop, reason, eig = _stop_decision(ctx, post)
    if snapshot.status in (Status.ANALYZED, Status.FINALIZED):
        stop, reason = True, reason or FALLBACK_REASON
    top = ranking(s.mean, inp.V)[0]
    uncertain = sorted(
        (
            (float(s.intervals[k, 1] - s.intervals[k, 0]), k)
            for k in range(inp.K)
            if s.intervals[k, 1] - s.intervals[k, 0] >= REMAINING_MIN_WIDTH
        ),
        key=lambda t: -t[0],
    )[:REMAINING_MAX_ITEMS]
    return {
        "shouldStop": stop,
        "reason": reason,
        "metrics": {
            "answered": ctx.answered,
            "topOption": inp.option_ids[top],
            "topProbability": round(ah.top_probability(s, inp.V), ah.PROB_DIGITS),
            "decisionEntropy": round(s.entropy, ah.PROB_DIGITS),
            "bestNextInfoGain": round(float(eig), EIG_DIGITS),
        },
        "remainingUncertainty": [
            {
                "criterionId": inp.criterion_ids[k],
                "interval": [
                    round(float(s.intervals[k, 0]), ah.PROB_DIGITS),
                    round(float(s.intervals[k, 1]), ah.PROB_DIGITS),
                ],
            }
            for _, k in uncertain
        ],
    }


async def question_progress(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """34번 GET /questions/progress."""
    _check(Action.QUESTION_READ, snapshot)
    ctx = await _load(snapshot, stores)
    _, s = _posterior(ctx.inp)
    return {
        "answered": ctx.answered,
        "expectedRange": _expected_range(ctx),
        "confidence": round(ah.top_probability(s, ctx.inp.V), ah.PROB_DIGITS),
    }


async def stop_questions(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """35번 POST /questions/stop. QUESTIONING에서만, ANALYZED로 바꾼다."""
    _check(Action.ANSWER_SUBMIT_OR_STOP, snapshot)
    ctx = await _load(snapshot, stores)
    _, s = _posterior(ctx.inp)
    low = ah.low_confidence(ah.top_probability(s, ctx.inp.V))
    await stores.decisions.set_status(snapshot.id, Status.ANALYZED)
    return {"reason": "USER_STOPPED", "lowConfidence": low}


async def list_answers(snapshot: DecisionSnapshot, stores: Stores) -> dict[str, Any]:
    """36번 GET /answers. 모든 상태에서 된다. 무효 답변도 isValid=false로 포함한다."""
    questions = {q.id: q for q in await stores.questions.list_questions(snapshot.id)}
    rows = [a for a in await stores.questions.list_answers(snapshot.id) if a.question_id in questions]
    rows.sort(key=lambda a: questions[a.question_id].seq)
    return {
        "items": [
            {
                "id": a.id,
                "questionId": a.question_id,
                "question": {**_qobj(questions[a.question_id]), "origin": questions[a.question_id].origin},
                "response": a.response,
                "isValid": a.is_valid,
                "createdAt": a.created_at,
            }
            for a in rows
        ]
    }


# --------------------------------------------------------------------------------------------------
# 37 · 38
# --------------------------------------------------------------------------------------------------


async def submit_answer(
    snapshot: DecisionSnapshot,
    stores: Stores,
    question_id: str,
    response: object,
    *,
    phraser: Phraser | None = None,
) -> dict[str, Any]:
    """37번 POST /answers → {answer, estimate, next}."""
    _check(Action.ANSWER_SUBMIT_OR_STOP, snapshot)
    ctx = await _load(snapshot, stores, freeze=True)
    question = next((q for q in ctx.questions if q.id == question_id), None)
    if question is None:
        raise not_found("질문을 찾을 수 없습니다.", {"questionId": question_id})
    if any(a.question_id == question_id for a in ctx.answers):
        raise FlowError(
            409,
            "QUESTION_ALREADY_ANSWERED",
            "이미 답한 질문입니다. 수정은 PATCH를 쓰세요.",
            {"questionId": question_id},
        )
    spec = _spec_of(question, ctx)
    parsed = parse_response(question.type, response)
    now = _now()
    row = AnswerRow(
        id=new_id(),
        question_id=question.id,
        decision_id=snapshot.id,
        response=response_to_dict(question.type, parsed),
        preference_kind=preference_kind_for(spec),
        is_valid=True,
        created_at=now,
        updated_at=now,
    )
    await stores.questions.add_answer(row)
    await stores.decisions.bump_version(snapshot.id)
    ctx = await _reload(ctx, stores)
    _, s = _posterior(ctx.inp)
    estimate = ah.estimate_payload(s, ctx.inp.V, ctx.inp.criterion_ids)
    await _record_snapshot(stores, snapshot.id, ctx, s)
    nxt = await _next_payload(ctx, stores, phraser)
    return {"answer": _answer_obj(row), "estimate": estimate, "next": nxt}


async def edit_answer(snapshot: DecisionSnapshot, stores: Stores, answer_id: str, response: object) -> dict[str, Any]:
    """38번 PATCH /answers/{answerId} → {estimate}. QUESTIONING·ANALYZED에서 되고 상태는 그대로다."""
    _check(Action.ANSWER_EDIT, snapshot)
    ctx = await _load(snapshot, stores, freeze=True)
    answer = next((a for a in ctx.answers if a.id == answer_id), None)
    if answer is None:
        raise not_found("답변을 찾을 수 없습니다.", {"answerId": answer_id})
    if not answer.is_valid:
        raise FlowError(409, "INVALID_STATE", "무효가 된 답변은 수정할 수 없습니다.", {"answerId": answer_id})
    question = next(q for q in ctx.questions if q.id == answer.question_id)
    spec = _spec_of(question, ctx)
    parsed = parse_response(question.type, response)
    await stores.questions.update_answer(answer.id, response_to_dict(question.type, parsed), preference_kind_for(spec))
    await stores.decisions.bump_version(snapshot.id)
    ctx = await _reload(ctx, stores)
    _, s = _posterior(ctx.inp)
    await _record_snapshot(stores, snapshot.id, ctx, s)
    return {"estimate": ah.estimate_payload(s, ctx.inp.V, ctx.inp.criterion_ids)}


async def _record_snapshot(stores: Stores, decision_id: str, ctx: _Ctx, s: Summary) -> None:
    """estimate_snapshots(P1)에 이번 추정을 남긴다. 저장소가 없으면 건너뛴다."""
    if stores.estimate_snapshots is None:
        return
    await stores.estimate_snapshots.add(
        decision_id,
        counted_answers(ctx.inp.answers),
        {cid: float(w) for cid, w in zip(ctx.inp.criterion_ids, s.mean, strict=True)},
        ah.top_probability(s, ctx.inp.V),
        float(s.entropy),
    )


def _spec_of(question: QuestionRow, ctx: _Ctx) -> Any:
    try:
        return spec_from_dict(question.spec, ctx.inp.criterion_ids)
    except (ValueError, KeyError):
        raise FlowError(409, "INVALID_STATE", "삭제된 기준이 들어간 질문입니다.", {"questionId": question.id}) from None


# --------------------------------------------------------------------------------------------------
# 자유 입력(29번)·기준 추가/삭제(19·22번)가 부르는 함수
# --------------------------------------------------------------------------------------------------


async def add_stated_preference(
    snapshot: DecisionSnapshot, stores: Stores, criterion_a_id: str, criterion_b_id: str, value: int
) -> dict[str, str]:
    """29번 `type: preference`: 가상 slider_pair 질문(origin=free_text)과 답변(stated)을 만든다. DRAFT에서도 된다."""
    _check(Action.FREE_INPUT, snapshot)
    ids = [c.id for c in snapshot.criteria]
    for cid in (criterion_a_id, criterion_b_id):
        if cid not in ids:
            raise not_found("기준을 찾을 수 없습니다.", {"criterionId": cid})
    if criterion_a_id == criterion_b_id:
        raise FlowError(400, "VALIDATION_ERROR", "서로 다른 두 기준이어야 합니다.", {"field": "criterionB"})
    parsed = parse_response("slider_pair", {"value": value})  # −5..5 정수 검사
    spec = SliderSpec(ids.index(criterion_a_id), ids.index(criterion_b_id))
    try:
        display = build_question(spec, criterion_metas(snapshot), compute_scales(snapshot), snapshot.category)
    except ValueError as exc:
        raise FlowError(
            422, "NOT_READY", "질문 범위를 만들 수 없습니다.", {"missing": ["CRITERION_RANGE"], "reason": str(exc)}
        ) from None
    questions = await stores.questions.list_questions(snapshot.id)
    now = _now()
    question = QuestionRow(
        id=new_id(),
        decision_id=snapshot.id,
        seq=max((q.seq for q in questions), default=0) + 1,
        type="slider_pair",
        spec=spec_to_dict(spec, ids),
        display=display,
        phrasing_source="template",
        origin="free_text",
        info_gain=None,
    )
    answer = AnswerRow(
        id=new_id(),
        question_id=question.id,
        decision_id=snapshot.id,
        response=response_to_dict("slider_pair", parsed),
        preference_kind="stated",
        is_valid=True,
        created_at=now,
        updated_at=now,
    )
    await stores.questions.add_question(question)
    await stores.questions.add_answer(answer)
    await stores.decisions.bump_version(snapshot.id)
    return {"questionId": question.id, "answerId": answer.id}


async def on_criterion_added(snapshot: DecisionSnapshot, stores: Stores, criterion_id: str) -> str | None:
    """19·29번: 기준 추가 후 상태·상한 처리. 상태가 바뀌었으면 "QUESTIONING"(statusChanged), 아니면 None.

    ANALYZED → QUESTIONING(추가 라운드, 상한 = 답변 수 + 3). QUESTIONING이면 답변 수 + 3이 현재 상한보다 클 때만
    상한을 `raised_limit`으로 올린다(그러면 NULL이던 상한도 값이 생긴다). DRAFT는 아무것도 하지 않는다.
    """
    _check(Action.CONFIG_EDIT, snapshot, criterionId=criterion_id)
    status = snapshot.status
    if status is Status.DRAFT:
        return None
    ctx = await _load(snapshot, stores)
    answered = ctx.answered
    if status is Status.ANALYZED:
        await stores.decisions.set_question_limit(snapshot.id, extra_round_limit(answered))
        await stores.decisions.set_status(snapshot.id, after_criterion_added(status))
        return "QUESTIONING"
    effective = snapshot.question_limit if snapshot.question_limit is not None else max_questions(ctx.inp.K)
    if answered + EXTRA_ROUND_QUESTIONS > effective:
        await stores.decisions.set_question_limit(snapshot.id, raised_limit(effective, answered))
    return None


async def on_criterion_deleted(snapshot: DecisionSnapshot, stores: Stores, criterion_id: str) -> int:
    """22번: 그 기준이 들어간 답변을 무효로 하고 버전을 올린다. 무효가 된 답변 수(invalidatedAnswers)를 돌려준다."""
    _check(Action.CONFIG_EDIT, snapshot, criterionId=criterion_id)
    count = await stores.questions.invalidate_answers_of_criterion(snapshot.id, criterion_id)
    await stores.decisions.bump_version(snapshot.id)
    return count


__all__ = [
    "Phraser",
    "add_stated_preference",
    "edit_answer",
    "list_answers",
    "new_id",
    "next_question",
    "on_criterion_added",
    "on_criterion_deleted",
    "question_progress",
    "question_status",
    "start_questions",
    "stop_questions",
    "submit_answer",
]
