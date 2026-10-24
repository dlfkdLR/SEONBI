"""최종 선택 확정(47번 로직): ANALYZED에서만, 제외·모르는 선택지 거부, 확정 시점 결과 보존."""

import asyncio
from dataclasses import replace
from typing import Any

import pytest
from fakes import FakeStores, laptop_snapshot, make_stores
from test_question_flow import LAPTOP_W, User, answer_n, start_and_play

from app.ai.providers.fake import FakeAdapter
from app.ai.schemas import OptionProsCons, SummarizeOptionsOutput
from app.schemas.analysis import AnalysisResponse
from app.services import analysis_flow as af
from app.services.finalize import finalize_decision
from app.services.flow_errors import FlowError
from app.services.ports import ConditionRow, DecisionSnapshot
from app.services.state_rules import Status

run = asyncio.run


async def analyzed(conditions: tuple[ConditionRow, ...] = ()) -> FakeStores:
    stores = make_stores(laptop_snapshot())
    await start_and_play(stores, User(LAPTOP_W, 1))
    if conditions:
        stores.state.base = replace(stores.state.base, conditions=conditions)
    return stores


def fin(stores: FakeStores, option: str, memo: str | None = "메모", **kw: Any) -> dict[str, Any]:
    return run(finalize_decision(stores.snapshot(), stores, option, memo, **kw))


def err(stores: FakeStores, option: str) -> tuple[int, str]:
    with pytest.raises(FlowError) as exc:
        fin(stores, option)
    return exc.value.http_status, exc.value.code


def test_happy_path_matches_recommendation() -> None:
    stores = run(analyzed())
    before = run(af.get_analysis(stores.snapshot(), stores))
    rec = before["recommendation"]["optionId"]
    out = fin(stores, rec)
    assert set(out) == {"decisionId", "optionId", "matchedRecommendation", "finalizedAt", "status"}
    assert (out["decisionId"], out["optionId"], out["matchedRecommendation"], out["status"]) == (
        "d-laptop",
        rec,
        True,
        "FINALIZED",
    )
    assert stores.snapshot().status is Status.FINALIZED
    row = run(stores.final_choices.get("d-laptop"))
    assert row is not None and (row.option_id, row.memo, row.matched_recommendation) == (rec, "메모", True)
    assert row.finalized_at == out["finalizedAt"]
    AnalysisResponse.model_validate(row.recommendation_snapshot)
    assert row.recommendation_snapshot["provisional"] is False
    assert row.recommendation_snapshot["recommendation"] == before["recommendation"]
    assert row.recommendation_snapshot["computedAt"] == before["computedAt"]  # 방금 캐시된 결과 그대로


def test_choosing_other_option_is_allowed_and_not_matched() -> None:
    stores = run(analyzed())
    rec = run(af.get_analysis(stores.snapshot(), stores))["recommendation"]["optionId"]
    other = next(o.id for o in stores.snapshot().options if o.id != rec)
    out = fin(stores, other, None)
    assert out["matchedRecommendation"] is False
    row = run(stores.final_choices.get("d-laptop"))
    assert row is not None and row.memo is None and row.matched_recommendation is False


def test_state_constraints() -> None:
    draft = make_stores(laptop_snapshot())
    assert err(draft, "gram") == (409, "INVALID_STATE")
    questioning = make_stores(laptop_snapshot())
    run(answer_n(questioning, 1))
    assert err(questioning, "gram") == (409, "INVALID_STATE")
    assert (
        questioning.snapshot().status is Status.QUESTIONING and run(questioning.final_choices.get("d-laptop")) is None
    )

    stores = run(analyzed())
    fin(stores, "gram")
    assert err(stores, "gram") == (409, "DECISION_FINALIZED")
    assert err(stores, "nope") == (409, "DECISION_FINALIZED")  # 상태 검사가 먼저


def test_unknown_option_is_404_and_writes_nothing() -> None:
    stores = run(analyzed())
    calls = list(stores.state.calls)
    assert err(stores, "nope") == (404, "NOT_FOUND")
    assert stores.snapshot().status is Status.ANALYZED and stores.state.calls == calls


def test_excluded_option_is_409() -> None:
    cond = ConditionRow("c-price", "threshold", criterion_id="price", operator="<=", value=170.0)
    stores = run(analyzed((cond,)))
    assert err(stores, "galaxy") == (409, "OPTION_EXCLUDED")
    assert stores.snapshot().status is Status.ANALYZED and run(stores.final_choices.get("d-laptop")) is None
    assert fin(stores, "gram")["status"] == "FINALIZED"  # 제외되지 않은 선택지는 된다


def test_finalized_analysis_is_the_snapshot_even_after_later_input_changes() -> None:
    stores = run(analyzed())
    out = fin(stores, "yoga")
    snap_row = run(stores.final_choices.get("d-laptop"))
    assert snap_row is not None
    final_view = run(af.get_analysis(stores.snapshot(), stores))
    assert final_view == snap_row.recommendation_snapshot

    # 확정 뒤 값·버전이 바뀌어도 (예: B 쪽 버그나 직접 수정) 확정 시점 결과가 나온다
    changed = tuple(
        replace(v, value_num=(v.value_num or 0) * 3) if v.criterion_id == "price" else v
        for v in stores.state.base.values
    )
    stores.state.base = replace(stores.state.base, values=changed)
    run(stores.decisions.bump_version("d-laptop"))
    sn = stores.snapshot()
    assert run(af.get_analysis(sn, stores)) == final_view
    assert run(af.get_rankings(sn, stores))["items"] == final_view["rankings"]
    assert run(af.get_weights(sn, stores))["items"] == final_view["weights"]
    assert run(af.get_stability(sn, stores)) == final_view["stability"]
    assert run(af.get_change_conditions(sn, stores))["items"] == final_view["changeConditions"]
    assert out["optionId"] == "yoga"


def test_finalized_without_stored_choice_falls_back_to_recompute() -> None:
    stores = run(analyzed())
    run(stores.decisions.set_status("d-laptop", Status.FINALIZED))  # 확정 기록 없이 상태만 FINALIZED
    out = run(af.get_analysis(stores.snapshot(), stores))
    AnalysisResponse.model_validate(out)


def test_finalize_stores_ai_explanation_text() -> None:
    stores = run(analyzed())
    sn: DecisionSnapshot = stores.snapshot()
    llm = FakeAdapter()
    llm.push(SummarizeOptionsOutput(options=[OptionProsCons(option_id=o.id, pros=["AI"], cons=[]) for o in sn.options]))
    fin(stores, "gram", llm=llm)
    row = run(stores.final_choices.get("d-laptop"))
    assert row is not None
    assert row.recommendation_snapshot["explanation"]["source"] == "ai"
    assert row.recommendation_snapshot["optionSummaries"][0]["pros"] == ["AI"]
    assert len(llm.calls) == 2
