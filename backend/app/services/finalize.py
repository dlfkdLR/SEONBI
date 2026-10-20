"""최종 선택 확정 로직 (개발기획서 4.2, 7.3 47번). API는 C가 소유하고 상태 로직은 A가 맡는다.

- ANALYZED에서만 된다: DRAFT·QUESTIONING은 409 INVALID_STATE, FINALIZED는 409 DECISION_FINALIZED
  (`state_rules.check(FINALIZE)`).
- 상태 검사가 먼저, 그다음 선택지 검사: 모르는 선택지 404 NOT_FOUND, 제외된 선택지 409 OPTION_EXCLUDED.
- `recommendation_snapshot`에는 확정 시점의 39번 응답 전체(`provisional=false`, `computedAt` 포함)를 저장한다.
  이후 39번은 이 스냅샷을 그대로 돌려준다 (`analysis_flow.get_analysis`).
- `matched_recommendation` = 고른 선택지가 39번의 추천(1위)과 같은지.
- 쓰는 순서: final_choices.add → decisions.set_status(FINALIZED). 트랜잭션 경계는 호출하는 쪽(B/C)이 잡는다.
- memo 길이 제한은 명세에 없어 두지 않는다(라우터의 Pydantic 몫).
- `FlowErrorCode`에 OPTION_EXCLUDED가 아직 없어 `cast`로 넘긴다(flow_errors.py 수정 필요, 보고서 참고).
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.ai.calls import LLMSource
from app.ai.client import UsageCallback

from .analysis_flow import get_analysis
from .flow_errors import FlowError, not_found, raise_if_violation
from .ports import DecisionSnapshot, FinalChoiceRow, Stores
from .state_rules import Action, after_finalize, check


async def finalize_decision(
    snapshot: DecisionSnapshot,
    stores: Stores,
    option_id: str,
    memo: str | None,
    *,
    llm: LLMSource | None = None,
    on_usage: UsageCallback | None = None,
) -> dict[str, Any]:
    """47번. 7.3의 201 응답 본문을 돌려준다."""
    details = {"decisionId": snapshot.id}
    raise_if_violation(check(Action.FINALIZE, snapshot.status), details)
    if option_id not in {o.id for o in snapshot.options}:
        raise not_found("알 수 없는 선택지입니다.", {**details, "optionId": option_id})

    analysis = await get_analysis(snapshot, stores, llm=llm, on_usage=on_usage)
    if any(e["optionId"] == option_id for e in analysis["excludedOptions"]):
        raise FlowError(
            409,
            "OPTION_EXCLUDED",
            "필수 조건을 만족하지 못해 제외된 선택지는 고를 수 없습니다.",
            {**details, "optionId": option_id},
        )

    matched = option_id == analysis["recommendation"]["optionId"]
    finalized_at = datetime.now(UTC).isoformat()
    await stores.final_choices.add(
        FinalChoiceRow(
            decision_id=snapshot.id,
            option_id=option_id,
            memo=memo,
            matched_recommendation=matched,
            recommendation_snapshot=analysis,
            finalized_at=finalized_at,
        )
    )
    new_status = after_finalize(snapshot.status)
    await stores.decisions.set_status(snapshot.id, new_status)
    return {
        "decisionId": snapshot.id,
        "optionId": option_id,
        "matchedRecommendation": matched,
        "finalizedAt": finalized_at,
        "status": new_status.value,
    }
