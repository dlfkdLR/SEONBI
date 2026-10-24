"""분석 서비스 39~46번: 질문 흐름을 끝까지 돌려 얻은 데이터로 응답 형식·상태 표·캐시·AI 대체·P1 계산을 검증한다."""

import asyncio
import re
from collections.abc import Awaitable, Callable
from dataclasses import replace
from typing import Any

import numpy as np
import pytest
from fakes import FakeAnalysisCache, FakeStores, career_snapshot, laptop_snapshot, make_stores
from test_question_flow import CAREER_W, LAPTOP_W, User, answer_n, start_and_play

from app.ai.errors import AIUnavailableError, SchemaViolationError
from app.ai.providers.fake import FakeAdapter
from app.ai.schemas import ExplainResultOutput, OptionProsCons, SummarizeOptionsOutput
from app.engine.decision_analysis import change_conditions, ranking
from app.engine.hidden_preference import Mismatch
from app.engine.posterior import fit, summary
from app.schemas.analysis import (
    AnalysisResponse,
    ChangeConditionsResponse,
    RankingsResponse,
    ScoresResponse,
    SensitivityResponse,
    SimulationResponse,
    StabilityResponse,
    WeightsResponse,
)
from app.services import analysis_flow as af
from app.services.engine_bridge import build_engine_input
from app.services.flow_errors import FlowError
from app.services.ports import ConditionRow, CriterionRow, DecisionSnapshot, ValueRow
from app.services.state_rules import Status

run = asyncio.run

D, Q, A, F = Status.DRAFT, Status.QUESTIONING, Status.ANALYZED, Status.FINALIZED


# ------------------------------------------------------------------ 도우미
async def _build(kind: str) -> FakeStores:
    if kind == "laptop":
        stores = make_stores(laptop_snapshot())
        await start_and_play(stores, User(LAPTOP_W, 1))
    else:
        stores = make_stores(career_snapshot())
        await start_and_play(stores, User(CAREER_W, 2))
    assert stores.snapshot().status is A
    return stores


_SHARED: dict[str, FakeStores] = {}


def shared(kind: str) -> FakeStores:
    """읽기만 하는 테스트용 ANALYZED 저장소 (질문 흐름을 한 번만 돌린다)."""
    if kind not in _SHARED:
        _SHARED[kind] = run(_build(kind))
    return _SHARED[kind]


def isolated(stores: FakeStores, snapshot: DecisionSnapshot | None = None) -> tuple[FakeStores, DecisionSnapshot]:
    """같은 질문·답변을 쓰되 분석 캐시는 새것인 저장소와, (바꾼) 스냅샷."""
    return replace(stores, analysis=FakeAnalysisCache()), snapshot or stores.snapshot()


def code_of(coro: Awaitable[Any]) -> tuple[int, str]:
    with pytest.raises(FlowError) as exc:
        run(coro)  # type: ignore[arg-type]
    return exc.value.http_status, exc.value.code


KINDS = ["laptop", "career"]


class CountingBase:
    """af._compute_base 호출 횟수를 센다."""

    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.n = 0
        original = af._compute_base

        def wrapped(snapshot: DecisionSnapshot, inp: Any) -> dict[str, Any]:
            self.n += 1
            return original(snapshot, inp)

        monkeypatch.setattr(af, "_compute_base", wrapped)


def option_ids(snapshot: DecisionSnapshot) -> list[str]:
    return [o.id for o in snapshot.options]


def ai_stub(snapshot: DecisionSnapshot, *, explain: str = "입력한 값으로 계산한 결과입니다.") -> FakeAdapter:
    """summarize_options → explain_result 순서로 정해진 응답을 주는 가짜 LLM (호출 횟수는 .calls)."""
    ad = FakeAdapter()
    ad.push(
        SummarizeOptionsOutput(
            options=[OptionProsCons(option_id=i, pros=["AI 장점"], cons=["AI 단점"]) for i in option_ids(snapshot)]
        ),
        ExplainResultOutput(summary=explain, reasons=["근거"], caveats=[]),
    )
    return ad


# ------------------------------------------------------------------ 형식 검증
@pytest.mark.parametrize("kind", KINDS)
def test_every_payload_validates_against_models(kind: str) -> None:
    stores = shared(kind)
    sn = stores.snapshot()
    analysis = run(af.get_analysis(sn, stores))
    model = AnalysisResponse.model_validate(analysis)
    assert model.provisional is False and model.explanation.source == "template"
    assert [r.rank for r in model.rankings] == list(range(1, len(model.rankings) + 1))
    assert {s.option_id for s in model.option_summaries} == set(option_ids(sn))
    WeightsResponse.model_validate(run(af.get_weights(sn, stores)))
    RankingsResponse.model_validate(run(af.get_rankings(sn, stores)))
    ScoresResponse.model_validate(run(af.get_scores(sn, stores)))
    SensitivityResponse.model_validate(run(af.get_sensitivity(sn, stores)))
    StabilityResponse.model_validate(run(af.get_stability(sn, stores)))
    ChangeConditionsResponse.model_validate(run(af.get_change_conditions(sn, stores)))
    ids = [c.id for c in sn.criteria]
    SimulationResponse.model_validate(run(af.simulate(sn, stores, [{"criterionId": c, "weight": 1} for c in ids])))


# ------------------------------------------------------------------ 상태 표
CALLS: dict[str, Callable[[DecisionSnapshot, FakeStores], Awaitable[Any]]] = {
    "39": lambda sn, st: af.get_analysis(sn, st),
    "40": lambda sn, st: af.get_weights(sn, st),
    "41": lambda sn, st: af.get_rankings(sn, st),
    "42": lambda sn, st: af.get_scores(sn, st),
    "43": lambda sn, st: af.get_sensitivity(sn, st),
    "44": lambda sn, st: af.get_stability(sn, st),
    "45": lambda sn, st: af.get_change_conditions(sn, st),
    "46": lambda sn, st: af.simulate(sn, st, [{"criterionId": c.id, "weight": 1} for c in sn.criteria]),
}


@pytest.mark.parametrize("api", list(CALLS))
def test_state_table_draft_and_questioning_without_answers(api: str) -> None:
    stores = make_stores(laptop_snapshot())
    assert code_of(CALLS[api](stores.snapshot(), stores)) == (422, "NOT_READY")  # DRAFT
    run(_start(stores))
    assert stores.snapshot().status is Q
    assert code_of(CALLS[api](stores.snapshot(), stores)) == (422, "NOT_READY")  # 답변 0개


async def _start(stores: FakeStores) -> None:
    from app.services import question_flow as qf

    await qf.start_questions(stores.snapshot(), stores)


@pytest.mark.parametrize("api", list(CALLS))
def test_state_table_questioning_with_answer_and_analyzed(api: str) -> None:
    stores = make_stores(laptop_snapshot())
    run(answer_n(stores, 1))
    assert stores.snapshot().status is Q
    run(CALLS[api](stores.snapshot(), stores))  # 답변 1개: 허용
    done = shared("laptop")
    run(CALLS[api](done.snapshot(), done))  # ANALYZED


def test_provisional_flag_follows_status_not_cache() -> None:
    stores = make_stores(laptop_snapshot())
    run(answer_n(stores, 2))
    first = run(af.get_analysis(stores.snapshot(), stores))
    assert first["provisional"] is True
    AnalysisResponse.model_validate(first)
    run(stores.decisions.set_status("d-laptop", A))  # 버전은 그대로 → 캐시 적중, provisional만 바뀐다
    second = run(af.get_analysis(stores.snapshot(), stores))
    assert second["provisional"] is False and second["computedAt"] == first["computedAt"]


# ------------------------------------------------------------------ 엔진과의 일치
@pytest.mark.parametrize("kind", KINDS)
def test_consistency_with_engine(kind: str) -> None:
    stores = shared(kind)
    sn = stores.snapshot()
    inp = build_engine_input(sn, run(stores.questions.list_questions(sn.id)), run(stores.questions.list_answers(sn.id)))
    post = fit(inp.seed, inp.K, inp.answers)
    s = summary(post, inp.V)
    top = inp.option_ids[ranking(s.mean, inp.V)[0]]

    rankings = run(af.get_rankings(sn, stores))["items"]
    assert rankings[0]["optionId"] == top
    assert [r["optionId"] for r in rankings] == [inp.option_ids[i] for i in ranking(s.mean, inp.V)]
    assert run(af.get_stability(sn, stores))["optionId"] == top
    assert run(af.get_change_conditions(sn, stores))["currentTop"] == top
    analysis = run(af.get_analysis(sn, stores))
    assert analysis["recommendation"]["optionId"] == top
    assert analysis["recommendation"]["topProbability"] == rankings[0]["topProbability"]  # 결정 (a)

    weights = run(af.get_weights(sn, stores))
    assert weights["intervalLevel"] == 0.8
    assert [w["criterionId"] for w in weights["items"]] == list(inp.criterion_ids)
    assert [w["mean"] for w in weights["items"]] == [round(float(m), 2) for m in s.mean]
    assert weights["items"] == analysis["weights"] and rankings == analysis["rankings"]

    st = run(af.get_stability(sn, stores))
    assert st == analysis["stability"]
    assert st["level"] in ("HIGH", "MEDIUM", "LOW") and st["score"] == analysis["recommendation"]["topProbability"]
    assert analysis["lowConfidence"] is (st["score"] < 0.55)

    conds = change_conditions(s.mean, inp.V)
    weight_items = [i for i in run(af.get_change_conditions(sn, stores))["items"] if i["type"] == "weight"]
    assert [i["criterionId"] for i in weight_items] == list(inp.criterion_ids)
    for item, c in zip(weight_items, conds, strict=True):
        if c.threshold is None:
            assert item["threshold"] is None
        else:
            assert (
                item["threshold"] == pytest.approx(c.threshold, abs=1e-4)
                and item["newTop"] == inp.option_ids[c.new_top or 0]
            )
    assert analysis["changeConditions"] == run(af.get_change_conditions(sn, stores))["items"]


def test_value_conditions_included_for_numeric_and_rating_only() -> None:
    items = run(af.get_change_conditions(shared("career").snapshot(), shared("career")))["items"]
    values = [i for i in items if i["type"] == "value"]
    assert values and all(i["criterionId"] != "interest" for i in values)  # 순위 입력은 값 전환 조건이 없다


# ------------------------------------------------------------------ 캐시
def test_cache_hit_and_version_miss(monkeypatch: pytest.MonkeyPatch) -> None:
    counter = CountingBase(monkeypatch)
    stores, sn = isolated(shared("laptop"))
    a1 = run(af.get_analysis(sn, stores))
    a2 = run(af.get_analysis(sn, stores))
    run(af.get_weights(sn, stores))
    assert counter.n == 1 and a1 == a2
    cached = run(stores.analysis.get(sn.id))
    assert cached is not None and cached.decision_version == sn.version
    assert "provisional" not in cached.payload and "explanation" not in cached.payload

    run(stores.decisions.bump_version(sn.id))  # 답변·값이 바뀐 것과 같다
    a3 = run(af.get_analysis(stores.snapshot(), stores))
    assert counter.n == 2 and a3["computedAt"] != a1["computedAt"]
    assert run(stores.analysis.get(sn.id)).decision_version == sn.version + 1  # type: ignore[union-attr]


def test_ai_texts_generated_once_per_version(monkeypatch: pytest.MonkeyPatch) -> None:
    counter = CountingBase(monkeypatch)
    stores, sn = isolated(shared("laptop"))
    llm = ai_stub(sn)
    first = run(af.get_analysis(sn, stores, llm=llm))
    assert (
        first["explanation"]["source"] == "ai" and first["explanation"]["summary"] == "입력한 값으로 계산한 결과입니다."
    )
    assert first["optionSummaries"][0]["pros"] == ["AI 장점"]
    AnalysisResponse.model_validate(first)
    assert len(llm.calls) == 2
    again = run(af.get_analysis(sn, stores, llm=llm))
    run(af.get_analysis(sn, stores))  # llm 없이 읽어도 저장된 AI 문장을 쓴다
    assert len(llm.calls) == 2 and again == first and counter.n == 1

    run(stores.decisions.bump_version(sn.id))
    # 새 버전: 스크립트가 비었으니 기본(canned) 응답으로 다시 만든다
    new = run(af.get_analysis(stores.snapshot(), stores, llm=llm))
    assert len(llm.calls) == 4 and new["explanation"]["source"] == "ai"


def test_no_llm_uses_template_and_stores_nothing_then_ai_fills_in() -> None:
    stores, sn = isolated(shared("laptop"))
    t = run(af.get_analysis(sn, stores))
    assert t["explanation"]["source"] == "template"
    assert run(stores.analysis.get(sn.id)).explanation is None  # type: ignore[union-attr]
    llm = ai_stub(sn)
    assert run(af.get_analysis(sn, stores, llm=llm))["explanation"]["source"] == "ai"
    assert run(stores.analysis.get(sn.id)).explanation is not None  # type: ignore[union-attr]


@pytest.mark.parametrize("failure", [AIUnavailableError("x"), SchemaViolationError("x")])
def test_failing_ai_falls_back_to_template_and_is_not_retried(failure: Exception) -> None:
    stores, sn = isolated(shared("laptop"))
    llm = FakeAdapter(script=[failure, failure])
    out = run(af.get_analysis(sn, stores, llm=llm))
    AnalysisResponse.model_validate(out)
    assert out["explanation"]["source"] == "template" and out["optionSummaries"]
    assert len(llm.calls) == 2
    run(af.get_analysis(sn, stores, llm=llm))
    assert len(llm.calls) == 2  # 같은 버전에서는 다시 부르지 않는다 (12.2)


def test_ai_explanation_with_foreign_number_is_replaced_by_template() -> None:
    stores, sn = isolated(shared("laptop"))
    llm = ai_stub(sn, explain="이 선택지는 12345% 확률로 1위예요.")
    out = run(af.get_analysis(sn, stores, llm=llm))
    assert out["explanation"]["source"] == "template" and "12345" not in out["explanation"]["summary"]
    assert out["optionSummaries"][0]["pros"] == ["AI 장점"]  # 요약은 AI 것을 그대로 쓴다


def test_ai_summaries_missing_option_filled_from_template() -> None:
    stores, sn = isolated(shared("laptop"))
    llm = FakeAdapter()
    llm.push(SummarizeOptionsOutput(options=[OptionProsCons(option_id="gram", pros=["AI"], cons=[])]))
    out = run(af.get_analysis(sn, stores, llm=llm))
    by_id = {s["optionId"]: s for s in out["optionSummaries"]}
    assert by_id["gram"]["pros"] == ["AI"] and by_id["galaxy"]["pros"] != ["AI"]
    assert set(by_id) == {"galaxy", "gram", "yoga"}


def test_template_explanation_uses_only_payload_numbers() -> None:
    out = run(af.get_analysis(shared("laptop").snapshot(), shared("laptop")))
    ex = out["explanation"]
    pct_top = round(out["recommendation"]["topProbability"] * 100)
    assert f"{pct_top}%" in ex["summary"] and out["recommendation"]["name"] in ex["summary"]
    assert 1 <= len(ex["reasons"]) <= 3
    allowed = {round(w["mean"] * 100) for w in out["weights"]} | {pct_top}
    for text in [ex["summary"], *ex["reasons"], *ex["caveats"]]:
        assert {int(n) for n in re.findall(r"\d+(?=%)", text)} <= allowed


# ------------------------------------------------------------------ 제외 선택지
def _laptop_with_conditions() -> tuple[FakeStores, DecisionSnapshot]:
    base = shared("laptop")
    conds = (
        ConditionRow("c-price", "threshold", criterion_id="price", operator="<=", value=170.0),
        ConditionRow("c-os", "required_tag", label="Windows 지원", satisfied_by=frozenset({"gram", "yoga"})),
    )
    return isolated(base, replace(base.snapshot(), conditions=conds))


def test_excluded_options_reasons_and_rankings() -> None:
    stores, sn = _laptop_with_conditions()
    out = run(af.get_analysis(sn, stores))
    AnalysisResponse.model_validate(out)
    assert out["excludedOptions"] == [{"optionId": "galaxy", "reasons": ["가격 170만 원 초과", "Windows 지원 미충족"]}]
    assert {r["optionId"] for r in out["rankings"]} == {"gram", "yoga"}
    assert all(s["optionId"] != "galaxy" for s in out["optionSummaries"])
    assert any("갤럭시북" in c and "제외" in c for c in out["explanation"]["caveats"])


def test_all_options_excluded_is_not_ready() -> None:
    base = shared("laptop")
    cond = ConditionRow("c-price", "threshold", criterion_id="price", operator="<=", value=100.0)
    stores, sn = isolated(base, replace(base.snapshot(), conditions=(cond,)))
    assert code_of(af.get_analysis(sn, stores)) == (422, "NOT_READY")


def test_condition_reason_operators() -> None:
    crit = {"price": CriterionRow("price", "가격", "numeric", "lower_better", 0, unit="만 원")}
    mk = lambda op: ConditionRow("c", "threshold", criterion_id="price", operator=op, value=200.0)  # noqa: E731
    assert af._condition_reason(mk(">="), crit) == "가격 200만 원 미만"
    assert af._condition_reason(mk("=="), crit) == "가격 200만 원이(가) 아님"
    assert af._condition_reason(ConditionRow("c", "required_tag"), crit) == "필수 조건 미충족"


# ------------------------------------------------------------------ 숨은 선호
def test_hidden_preferences_payload(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(af, "detect", lambda w, a: [Mismatch(0, 1, 0.9, 0.1, 2)])
    stores, sn = isolated(shared("laptop"))
    out = run(af.get_analysis(sn, stores))
    AnalysisResponse.model_validate(out)
    assert out["hiddenPreferences"][0]["criterionA"] == "price" and out["hiddenPreferences"][0]["strength"] == 0.9
    assert any("엇갈린" in c for c in out["explanation"]["caveats"])


# ------------------------------------------------------------------ 42 · 43 (P1)
def test_scores_cells_match_weights_and_raw_values() -> None:
    stores = shared("laptop")
    sn = stores.snapshot()
    sc = run(af.get_scores(sn, stores))
    means = {w["criterionId"]: w["mean"] for w in run(af.get_weights(sn, stores))["items"]}
    assert sc["criteria"] == ["price", "weight", "perf"]
    galaxy = next(i for i in sc["items"] if i["optionId"] == "galaxy")
    assert [c["criterionId"] for c in galaxy["cells"]] == sc["criteria"]
    price = galaxy["cells"][0]
    assert price["rawValue"] == 189 and price["normalized"] == 0.0
    assert galaxy["cells"][2]["rawValue"] == 4
    for item in sc["items"]:
        for cell in item["cells"]:
            assert cell["contribution"] == pytest.approx(means[cell["criterionId"]] * cell["normalized"], abs=0.011)
    scores_by_id = {r["optionId"]: r["score"] for r in run(af.get_rankings(sn, stores))["items"]}
    for item in sc["items"]:
        assert sum(c["contribution"] for c in item["cells"]) == pytest.approx(scores_by_id[item["optionId"]], abs=0.03)


def test_scores_rank_and_boolean_raw_values() -> None:
    career = shared("career")
    sc = run(af.get_scores(career.snapshot(), career))
    cs = next(i for i in sc["items"] if i["optionId"] == "cs")
    assert cs["cells"][0] == {**cs["cells"][0], "criterionId": "interest", "rawValue": 1, "normalized": 1.0}

    async def with_boolean() -> tuple[FakeStores, DecisionSnapshot]:
        stores = make_stores(laptop_snapshot())
        vals = [ValueRow(o, "touch", value_bool=o != "gram") for o in ("galaxy", "gram", "yoga")]
        stores.state.add_criterion(CriterionRow("touch", "터치", "boolean", "true_better", 0), vals)
        await start_and_play(stores, User([0.2, 0.3, 0.3, 0.2], 3))
        return stores, stores.snapshot()

    stores, sn = run(with_boolean())
    sc = run(af.get_scores(sn, stores))
    cells = {i["optionId"]: i["cells"][3] for i in sc["items"]}
    assert (cells["galaxy"]["rawValue"], cells["gram"]["rawValue"], cells["gram"]["normalized"]) == (1, 0, 0.0)
    out = run(af.get_analysis(sn, stores))
    AnalysisResponse.model_validate(out)
    gram = next(x for x in out["optionSummaries"] if x["optionId"] == "gram")
    assert any("터치" in c and "(아니요)" in c for c in gram["cons"])


def test_sensitivity_curves_and_crossovers() -> None:
    stores = shared("laptop")
    sn = stores.snapshot()
    allc = run(af.get_sensitivity(sn, stores))["items"]
    assert [i["criterionId"] for i in allc] == ["price", "weight", "perf"]
    one = run(af.get_sensitivity(sn, stores, "weight"))["items"]
    assert len(one) == 1 and one[0] == allc[1]
    item = allc[0]
    assert len(item["points"]) == 21 and item["points"][0]["weight"] == 0.0 and item["points"][-1]["weight"] == 1.0
    assert set(item["points"][0]["scores"]) == {"galaxy", "gram", "yoga"}
    means = {w["criterionId"]: w["mean"] for w in run(af.get_weights(sn, stores))["items"]}
    assert item["currentWeight"] == means["price"]

    top = run(af.get_rankings(sn, stores))["items"][0]["optionId"]
    conds = run(af.get_change_conditions(sn, stores))["items"]
    found = 0
    for it in allc:
        cc = next(c for c in conds if c["type"] == "weight" and c["criterionId"] == it["criterionId"])
        if cc["threshold"] is None:
            assert it["crossovers"] == []
            continue
        (x,) = it["crossovers"]
        assert (x["from"], x["to"], x["weight"]) == (top, cc["newTop"], cc["threshold"])
        # 점수는 가중치의 1차식이라 교차 지점에서 두 점수가 같다 (21개 점 사이 선형 보간)
        pts = it["points"]
        j = max(i for i in range(21) if pts[i]["weight"] <= x["weight"] and i < 20)
        frac = (x["weight"] - pts[j]["weight"]) / (pts[j + 1]["weight"] - pts[j]["weight"])
        interp = {
            o: pts[j]["scores"][o] + frac * (pts[j + 1]["scores"][o] - pts[j]["scores"][o])
            for o in (x["from"], x["to"])
        }
        assert interp[x["from"]] == pytest.approx(interp[x["to"]], abs=0.02)
        found += 1
    assert found >= 1


def test_sensitivity_unknown_criterion_is_validation_error() -> None:
    stores = shared("laptop")
    assert code_of(af.get_sensitivity(stores.snapshot(), stores, "nope")) == (400, "VALIDATION_ERROR")


# ------------------------------------------------------------------ 46 (P1)
def _weights(snapshot: DecisionSnapshot, values: list[float]) -> list[dict[str, Any]]:
    return [{"criterionId": c.id, "weight": w} for c, w in zip(snapshot.criteria, values, strict=True)]


def test_simulation_normalizes_and_detects_change_both_ways() -> None:
    stores = shared("laptop")
    sn = stores.snapshot()
    current = run(af.get_weights(sn, stores))["items"]
    same = run(af.simulate(sn, stores, [{"criterionId": w["criterionId"], "weight": w["mean"] * 10} for w in current]))
    assert same["changedFromCurrent"] is False  # 합이 10이어도 정규화한다
    assert sum(w["weight"] for w in same["normalizedWeights"]) == pytest.approx(1.0, abs=2e-4)
    assert [r["optionId"] for r in same["rankings"]] == [
        r["optionId"] for r in run(af.get_rankings(sn, stores))["items"]
    ]

    flipped = run(af.simulate(sn, stores, _weights(sn, [100, 0, 0])))  # 가격만 본다 → 가장 싼 요가가 1위
    assert flipped["changedFromCurrent"] is True
    assert flipped["rankings"][0] == {"rank": 1, "optionId": "yoga", "score": 1.0}
    assert flipped["normalizedWeights"] == _weights(sn, [1.0, 0.0, 0.0])
    SimulationResponse.model_validate(flipped)

    equal = run(af.simulate(sn, stores, _weights(sn, [2, 2, 2])))
    assert [w["weight"] for w in equal["normalizedWeights"]] == [0.3333] * 3


def test_simulation_does_not_write() -> None:
    stores, sn = isolated(shared("laptop"))
    calls_before = list(stores.state.calls)
    run(af.simulate(sn, stores, _weights(sn, [1, 2, 3])))
    assert stores.analysis.items == {} and stores.state.calls == calls_before  # type: ignore[attr-defined]


@pytest.mark.parametrize(
    "bad",
    [
        [-1, 1, 1],
        [0, 0, 0],
        [float("nan"), 1, 1],
        [float("inf"), 1, 1],
        [True, 1, 1],
        ["1", 1, 1],
    ],
)
def test_simulation_rejects_bad_weights(bad: list[Any]) -> None:
    stores = shared("laptop")
    sn = stores.snapshot()
    assert code_of(af.simulate(sn, stores, _weights(sn, bad))) == (400, "VALIDATION_ERROR")


def test_simulation_rejects_bad_structure() -> None:
    stores = shared("laptop")
    sn = stores.snapshot()
    ok = _weights(sn, [1, 1, 1])
    for bad in (ok[:2], [*ok, ok[0]], [*ok[:2], {"criterionId": "zzz", "weight": 1}], "x", [1, 2, 3], [{"weight": 1}]):
        assert code_of(af.simulate(sn, stores, bad)) == (400, "VALIDATION_ERROR")


def test_simulation_state_constraints() -> None:
    assert code_of(CALLS["46"](laptop_snapshot(), make_stores(laptop_snapshot()))) == (422, "NOT_READY")


# ------------------------------------------------------------------ 데이터 형태
def test_json_safe() -> None:
    import json

    stores = shared("career")
    sn = stores.snapshot()
    for call in CALLS.values():
        json.dumps(run(call(sn, stores)), allow_nan=False)
    assert np.isfinite(run(af.get_stability(sn, stores))["score"])
