"""분석 응답 조각: 5.12 노트북 예시로 7.3 31·37·39~45번 payload와 문장을 검증한다."""

import importlib.util
import json
from pathlib import Path
from typing import Any

import numpy as np
import pytest

from app.engine.decision_analysis import (
    ChangeCondition,
    Stability,
    ValueCondition,
    change_conditions,
    stability,
    value_conditions,
)
from app.engine.hidden_preference import Mismatch, detect
from app.engine.likelihood import CriteriaABSpec, ScenarioSpec, SliderSpec
from app.engine.posterior import Answer, Posterior, Summary, fit, prior_particles, summary
from app.engine.question_selector import build_scenarios
from app.services import analysis_helpers as ah

_spec = importlib.util.spec_from_file_location("example_data", Path(__file__).parents[1] / "engine" / "example_data.py")
assert _spec is not None and _spec.loader is not None
example_data = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(example_data)

CRIT_IDS = ["price", "weight", "perf"]
CRIT_NAMES = ["가격", "무게", "성능"]
OPT_IDS = ["galaxy", "gram", "yoga"]
OPT_NAMES = ["갤럭시북", "LG 그램 16", "요가"]
UNITS = ["만 원", "kg", ""]
LEVELS: list[list[str]] = [[], [], ["매우 낮음", "낮음", "보통", "높음", "매우 높음"]]


@pytest.fixture(scope="module")
def ex() -> dict[str, Any]:
    scales, V = example_data.build_example()
    q1, q2 = Answer(SliderSpec(0, 2), -2), Answer(SliderSpec(2, 1), 1)
    post0 = fit(42, 3, [q1, q2])
    big = next(x for x in build_scenarios(summary(post0, V).mean, 0, 1, example_data.CRIT, scales) if x.raw_a == 20)
    answers = [q1, q2, Answer(big, "B")]
    post = fit(42, 3, answers)
    return {"V": V, "answers": answers, "post": post, "s": summary(post, V), "W0": prior_particles(42, 3)}


def _dumps(x: object) -> str:
    return json.dumps(x, ensure_ascii=False, allow_nan=False)


# ---------------------------------------------------------------- 조사
@pytest.mark.parametrize(
    ("word", "i_ga", "eul_reul", "eu_ro"),
    [
        ("가격", "가격이", "가격을", "가격으로"),
        ("무게", "무게가", "무게를", "무게로"),
        ("서울", "서울이", "서울을", "서울로"),  # ㄹ 받침은 (으)로에서 '로'
        ("LG 그램 16", "LG 그램 16이", "LG 그램 16을", "LG 그램 16으로"),  # 십육의 '육' ㄱ 받침
        ("갤럭시북5 프로", "갤럭시북5 프로가", "갤럭시북5 프로를", "갤럭시북5 프로로"),
        ("아이폰 15", "아이폰 15가", "아이폰 15를", "아이폰 15로"),  # 십오 → 받침 없음
        ("맥북 Pro", "맥북 Pro가", "맥북 Pro를", "맥북 Pro로"),
        ("서피스 L", "서피스 L이", "서피스 L을", "서피스 L로"),  # 엘 → ㄹ 받침
        ("카메라(광학)", "카메라(광학)이", "카메라(광학)을", "카메라(광학)으로"),  # 괄호는 건너뜀
        ("", "가", "를", "로"),
    ],
)
def test_particles(word: str, i_ga: str, eul_reul: str, eu_ro: str) -> None:
    assert ah.i_ga(word) == i_ga
    assert ah.eul_reul(word) == eul_reul
    assert ah.eu_ro(word) == eu_ro


# ---------------------------------------------------------------- 31번
def test_expected_range() -> None:
    assert ah.expected_range("initial", 3, 0, 6) == [3, 6]
    assert ah.expected_range("initial", 5, 0, 10) == [3, 10]
    assert ah.expected_range("initial", 8, 0, 12) == [3, 12]  # 상한 12
    assert ah.expected_range("extra", 3, 5, 8) == [6, 8]


def test_answered_count_excludes_free_text_but_counts_skips() -> None:
    answers = [
        Answer(SliderSpec(0, 1), 2),
        Answer(SliderSpec(0, 2), None),  # 잘 모르겠어요도 센다
        Answer(SliderSpec(1, 2), -3, origin="free_text"),
    ]
    assert ah.answered_count(answers) == 2
    assert ah.answered_count([]) == 0


def test_low_confidence() -> None:
    assert ah.low_confidence(0.54)
    assert not ah.low_confidence(0.55)
    assert not ah.low_confidence(0.9)


# ---------------------------------------------------------------- 37번
def test_estimate_payload(ex: dict[str, Any]) -> None:
    out = ah.estimate_payload(ex["s"], ex["V"], CRIT_IDS)
    assert set(out) == {"weights", "topProbability"}
    assert [w["criterionId"] for w in out["weights"]] == CRIT_IDS
    for w, m in zip(out["weights"], [0.215, 0.357, 0.428], strict=True):
        assert set(w) == {"criterionId", "mean", "interval"}
        assert w["mean"] == pytest.approx(m, abs=0.011)
        assert len(w["interval"]) == 2 and w["interval"][0] <= w["mean"] <= w["interval"][1]
    assert out["topProbability"] == pytest.approx(0.73, abs=0.011)  # gram의 P_best
    _dumps(out)


# ---------------------------------------------------------------- 40번
def test_weights_items_shape_and_ranks(ex: dict[str, Any]) -> None:
    items = ah.weights_items(ex["s"], CRIT_IDS, CRIT_NAMES, ex["answers"], ex["W0"])
    assert [set(i) for i in items] == [{"criterionId", "name", "mean", "interval", "statedRank", "revealedRank"}] * 3
    assert [i["name"] for i in items] == CRIT_NAMES
    # 진술: 슬라이더 -2(성능 > 가격), +1(성능 > 무게 약간) → 성능이 1위. 가격은 쓴 답변이 가장 적은 쪽
    assert [i["statedRank"] for i in items][2] == 1
    assert sorted(i["statedRank"] for i in items) == [1, 2, 3]
    # 드러난 답변은 상황형 하나뿐 (가격·무게 쌍, B 선택 → 무게 쪽): 무게가 가격보다 높다
    assert items[1]["revealedRank"] < items[0]["revealedRank"]
    assert ah.INTERVAL_LEVEL == 0.8
    _dumps(items)


def test_stated_and_revealed_rank_disagree() -> None:
    """진술은 A(0) > B(1), 드러난 선택은 B > A."""
    W0 = prior_particles(7, 3)
    scenario = ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 10, 0.1)
    answers = [Answer(SliderSpec(0, 1), 4), Answer(CriteriaABSpec(0, 1), "A"), Answer(scenario, "B")]
    post = fit(7, 3, answers)
    s = summary(post, np.eye(3))
    items = ah.weights_items(s, CRIT_IDS, CRIT_NAMES, answers, W0)
    assert items[0]["statedRank"] < items[1]["statedRank"]
    assert items[0]["revealedRank"] > items[1]["revealedRank"]
    assert {i["statedRank"] for i in items} <= {1, 2, 3}


def test_ranks_none_without_that_kind() -> None:
    W0 = prior_particles(7, 3)
    s = summary(fit(7, 3, []), np.eye(3))
    scenario = ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 10, 0.1)
    only_stated = [Answer(SliderSpec(0, 1), 3)]
    only_revealed = [Answer(scenario, "A")]
    skipped = [Answer(SliderSpec(0, 1), None), Answer(scenario, None)]  # 잘 모르겠어요만 있으면 근거 없음
    for answers, st_none, rv_none in [
        ([], True, True),
        (only_stated, False, True),
        (only_revealed, True, False),
        (skipped, True, True),
    ]:
        items = ah.weights_items(s, CRIT_IDS, CRIT_NAMES, answers, W0)
        assert all((i["statedRank"] is None) is st_none for i in items)
        assert all((i["revealedRank"] is None) is rv_none for i in items)


def test_free_text_answer_counts_as_stated() -> None:
    W0 = prior_particles(7, 3)
    s = summary(fit(7, 3, []), np.eye(3))
    items = ah.weights_items(s, CRIT_IDS, CRIT_NAMES, [Answer(SliderSpec(1, 0), 5, origin="free_text")], W0)
    assert items[1]["statedRank"] == 1 and items[0]["revealedRank"] is None


# ---------------------------------------------------------------- 41번
def test_rankings_items(ex: dict[str, Any]) -> None:
    s: Summary = ex["s"]
    items = ah.rankings_items(s.mean, ex["V"], s.p_best, OPT_IDS, OPT_NAMES)
    assert [set(i) for i in items] == [{"rank", "optionId", "name", "score", "topProbability"}] * 3
    assert [i["rank"] for i in items] == [1, 2, 3]
    assert items[0]["optionId"] == "gram" and items[0]["name"] == "LG 그램 16"
    assert items[0]["topProbability"] == pytest.approx(0.73, abs=0.011)
    assert [i["score"] for i in items] == sorted((i["score"] for i in items), reverse=True)
    assert all(0 <= i["score"] <= 1 for i in items)
    assert sum(i["topProbability"] for i in items) == pytest.approx(1.0, abs=0.02)
    _dumps(items)


# ---------------------------------------------------------------- 44번
def test_stability_payload_medium(ex: dict[str, Any]) -> None:
    s: Summary = ex["s"]
    st = stability(ex["post"], ex["V"], seed=1)
    cc = change_conditions(s.mean, ex["V"])
    out = ah.stability_payload(st, s.p_best, cc, OPT_IDS, OPT_NAMES, CRIT_NAMES)
    assert set(out) == {"optionId", "score", "level", "localPerturbationKeepRate", "competitors", "text"}
    assert out["optionId"] == "gram" and out["level"] == "MEDIUM"
    assert out["score"] == pytest.approx(0.73, abs=0.011)
    assert out["localPerturbationKeepRate"] > 0.95
    # 경쟁자: yoga(0.167)와 galaxy(0.104) — 확률 내림차순
    assert [c["optionId"] for c in out["competitors"]] == ["yoga", "galaxy"]
    assert all(set(c) == {"optionId", "probability"} for c in out["competitors"])
    # 임계값이 현재에 가장 가까운 기준 = 가격(0.215 → 0.097, 차 0.118 < 무게 0.13) → 1순위 경쟁 yoga
    assert out["text"] == "가격을 얼마나 중시하는지에 따라 요가로 바뀔 수 있어요."
    _dumps(out)


def _stab(grade: str, p: float, top: int = 0) -> Stability:
    return Stability(top, p, grade, 0.9)  # type: ignore[arg-type]


def test_stability_levels_and_competitor_filter() -> None:
    p_best = np.array([0.90, 0.049, 0.05, 0.001])
    cc = [ChangeCondition(0, 0.4, 0.6, "increase", 2)]
    high = ah.stability_payload(_stab("high", 0.9), p_best, cc, list("abcd"), list("ABCD"), ["가격"])
    assert high["level"] == "HIGH" and high["text"] == ah.STABILITY_TEXT_HIGH
    assert [c["optionId"] for c in high["competitors"]] == ["c"]  # 0.05 이상만, 0.049는 제외
    low = ah.stability_payload(
        _stab("low", 0.4, 1), np.array([0.3, 0.4, 0.3]), cc, list("abc"), list("가나다"), ["가격"]
    )
    assert low["level"] == "LOW"
    assert [c["optionId"] for c in low["competitors"]] == ["a", "c"]  # 동률은 입력 순서
    assert low["text"] == "가격을 얼마나 중시하는지에 따라 가로 바뀔 수 있어요."


def test_stability_text_fallback_without_conditions_or_competitors() -> None:
    no_thr = [ChangeCondition(0, 0.4, None, None, None)]
    a = ah.stability_payload(_stab("medium", 0.6), np.array([0.6, 0.4]), no_thr, ["a", "b"], ["A", "B"], ["가격"])
    assert a["text"] == ah.STABILITY_TEXT_FALLBACK
    thr = [ChangeCondition(0, 0.4, 0.6, "increase", 1)]
    b = ah.stability_payload(_stab("medium", 0.6), np.array([0.96, 0.04]), thr, ["a", "b"], ["A", "B"], ["가격"])
    assert b["competitors"] == [] and b["text"] == ah.STABILITY_TEXT_FALLBACK


# ---------------------------------------------------------------- 45번
def test_change_conditions_payload_example(ex: dict[str, Any]) -> None:
    s: Summary = ex["s"]
    cc = change_conditions(s.mean, ex["V"])
    out = ah.change_conditions_payload(cc, 1, CRIT_IDS, CRIT_NAMES, OPT_IDS, OPT_NAMES)
    assert set(out) == {"currentTop", "items"} and out["currentTop"] == "gram"
    price, weight, perf = out["items"]
    assert set(price) == {"type", "criterionId", "currentWeight", "threshold", "direction", "newTop", "text"}
    assert price["type"] == "weight" and price["direction"] == "decrease" and price["newTop"] == "galaxy"
    assert price["threshold"] == pytest.approx(0.097, abs=0.002)
    assert price["text"] == "가격 중요도가 21% → 10% 이하가 되면 갤럭시북이 1위가 됩니다."
    assert weight["direction"] == "decrease" and weight["newTop"] == "yoga"
    assert weight["text"] == "무게 중요도가 36% → 23% 이하가 되면 요가가 1위가 됩니다."
    assert perf["direction"] == "increase" and perf["newTop"] == "galaxy"
    assert perf["text"] == "성능 중요도가 43% → 57% 이상이 되면 갤럭시북이 1위가 됩니다."
    _dumps(out)


def test_change_conditions_payload_spec_wording() -> None:
    cc = [
        ChangeCondition(0, 0.25, 0.31, "increase", 1),
        ChangeCondition(1, 0.20, None, None, None),
    ]
    out = ah.change_conditions_payload(
        cc, 0, ["c1", "c3"], ["가격", "배터리"], ["o2", "o3"], ["갤럭시북5 프로", "LG 그램 16"]
    )
    assert out["items"][0] == {
        "type": "weight",
        "criterionId": "c1",
        "currentWeight": 0.25,
        "threshold": 0.31,
        "direction": "increase",
        "newTop": "o3",
        "text": "가격 중요도가 25% → 31% 이상이 되면 LG 그램 16이 1위가 됩니다.",
    }
    assert out["items"][1] == {
        "type": "weight",
        "criterionId": "c3",
        "threshold": None,
        "text": "배터리 중요도가 어떻게 바뀌어도 1위가 유지됩니다.",
    }


def test_value_conditions_texts() -> None:
    conds = [
        ValueCondition(1, 0, 210.0, 198.0, "decrease", 0.5),
        ValueCondition(0, 1, 1.5, 1.65, "increase", 0.4),
        ValueCondition(1, 2, 2.0, 4.0, "increase", 0.7),
    ]
    out = ah.change_conditions_payload(
        [],
        0,
        ["c1", "c4", "c5"],
        ["가격", "무게", "적성"],
        ["o2", "o3"],
        ["갤럭시북", "LG 그램 16"],
        value_conds=conds,
        criterion_units=["만 원", "kg", ""],
        criterion_levels=[[], [], ["별로", "그럭저럭", "끌림", "매우 끌림"]],
    )
    price, weight, rating = out["items"]
    assert price == {
        "type": "value",
        "optionId": "o3",
        "criterionId": "c1",
        "currentValue": 210,
        "threshold": 198,
        "text": "LG 그램 16의 가격이 12만 원 이상 내려가면 1위가 됩니다.",
    }
    assert isinstance(price["currentValue"], int)
    assert weight["text"] == "갤럭시북의 무게가 0.15kg 이상 올라가면 1위가 됩니다."
    assert rating["text"] == "LG 그램 16의 적성이 '그럭저럭'에서 '매우 끌림'이 되면 1위가 됩니다."
    assert rating["threshold"] == 4 and rating["currentValue"] == 2
    _dumps(out)


def test_value_conditions_from_engine(ex: dict[str, Any]) -> None:
    s: Summary = ex["s"]
    raw = np.array([[189, 1.23, 4], [165, 1.19, 3], [150, 1.45, 4]], dtype=float)
    scales, _ = example_data.build_example()
    vc = value_conditions(s.mean, ex["V"], example_data.CRIT, scales, raw)
    assert vc
    out = ah.change_conditions_payload(
        change_conditions(s.mean, ex["V"]),
        1,
        CRIT_IDS,
        CRIT_NAMES,
        OPT_IDS,
        OPT_NAMES,
        value_conds=vc,
        criterion_units=UNITS,
        criterion_levels=LEVELS,
    )
    values = [i for i in out["items"] if i["type"] == "value"]
    assert len(values) == len(vc)
    assert all(set(v) == {"type", "optionId", "criterionId", "currentValue", "threshold", "text"} for v in values)
    assert all(v["text"].endswith("1위가 됩니다.") for v in values)
    _dumps(out)


# ---------------------------------------------------------------- 39번 hiddenPreferences
def test_hidden_preferences_payload_from_detect() -> None:
    W0 = prior_particles(42, 3)
    scenario = ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 10, 0.1)
    answers = [
        Answer(SliderSpec(0, 1), 5),
        Answer(SliderSpec(0, 1), 5),
        Answer(scenario, "B"),
        Answer(scenario, "B"),
    ]
    mismatches = detect(W0, answers)
    assert len(mismatches) == 1
    out = ah.hidden_preferences_payload(mismatches, CRIT_IDS, CRIT_NAMES)
    assert out == [
        {
            "criterionA": "price",
            "criterionB": "weight",
            "stated": "A_MORE_IMPORTANT",
            "revealed": "B_MORE_IMPORTANT",
            "strength": out[0]["strength"],
            "text": "가격이 더 중요하다고 답했지만, 실제 선택에서는 무게를 위해 가격을 양보하는 경향이 보였어요.",
        }
    ]
    m = mismatches[0]
    assert out[0]["strength"] == round(min(m.stated_p, 1 - m.revealed_p), 2) >= 0.75
    _dumps(out)


def test_hidden_preferences_a_is_stated_more_important_even_when_reversed() -> None:
    """detect가 (stated_over=1, revealed_over=0)을 줘도 A는 진술에서 더 중요하다고 한 기준이다."""
    out = ah.hidden_preferences_payload([Mismatch(1, 0, 0.9, 0.1, 2)], CRIT_IDS, CRIT_NAMES)
    assert out[0]["criterionA"] == "weight" and out[0]["criterionB"] == "price"
    assert out[0]["strength"] == 0.9
    assert out[0]["text"].startswith("무게가 더 중요하다고 답했지만, 실제 선택에서는 가격을 위해 무게를 양보")


def test_hidden_preferences_strength_is_min_and_empty() -> None:
    out = ah.hidden_preferences_payload([Mismatch(0, 2, 0.95, 0.20, 3)], CRIT_IDS, CRIT_NAMES)
    assert out[0]["strength"] == 0.8  # min(0.95, 1 − 0.20)
    assert ah.hidden_preferences_payload([], CRIT_IDS, CRIT_NAMES) == []
    assert "단정하지 않습니다" in ah.HIDDEN_PREFERENCE_NOTICE
    assert ah.HIDDEN_PREFERENCE_NOTICE not in out[0]["text"]


def test_payloads_are_plain_python_types(ex: dict[str, Any]) -> None:
    """numpy 값이 섞이면 json 직렬화가 깨지므로 타입까지 확인한다."""
    post: Posterior = ex["post"]
    s: Summary = ex["s"]
    payloads = [
        ah.estimate_payload(s, ex["V"], CRIT_IDS),
        ah.weights_items(s, CRIT_IDS, CRIT_NAMES, ex["answers"], ex["W0"]),
        ah.rankings_items(s.mean, ex["V"], s.p_best, OPT_IDS, OPT_NAMES),
        ah.stability_payload(
            stability(post, ex["V"], seed=1),
            s.p_best,
            change_conditions(s.mean, ex["V"]),
            OPT_IDS,
            OPT_NAMES,
            CRIT_NAMES,
        ),
    ]

    def walk(x: object) -> None:
        if isinstance(x, dict):
            for k, v in x.items():
                assert isinstance(k, str)
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)
        else:
            assert x is None or type(x) in (int, float, str, bool), type(x)

    for p in payloads:
        walk(p)
        assert json.loads(_dumps(p)) == p
