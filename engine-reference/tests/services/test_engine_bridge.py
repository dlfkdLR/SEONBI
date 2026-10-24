"""engine_bridge: 행 → 엔진 입력, spec/response 변환, 응답 형식 검사."""

import importlib.util
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest
from fakes import career_snapshot, laptop_snapshot

from app.engine.likelihood import CriteriaABSpec, ScenarioSpec, SliderSpec
from app.engine.normalize import Scale
from app.services import engine_bridge as eb
from app.services.flow_errors import FlowError
from app.services.ports import AnswerRow, ConditionRow, CriterionRow, QuestionRow, ValueRow

_spec = importlib.util.spec_from_file_location("example_data", Path(__file__).parents[1] / "engine" / "example_data.py")
assert _spec is not None and _spec.loader is not None
example_data = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(example_data)

IDS = ["price", "weight", "perf"]


def _q(qid: str, seq: int, spec: dict, qtype: str = "slider_pair", origin: str = "engine") -> QuestionRow:
    return QuestionRow(qid, "d-laptop", seq, qtype, spec, {}, "template", origin)  # type: ignore[arg-type]


def _a(aid: str, qid: str, response: dict, *, valid: bool = True) -> AnswerRow:
    return AnswerRow(aid, qid, "d-laptop", response, "stated", valid)


# ------------------------------------------------------------------ 행 → 엔진 입력
def test_laptop_matches_the_engine_example():
    inp = eb.build_engine_input(laptop_snapshot(), [], [])
    scales, V = example_data.build_example()
    np.testing.assert_allclose(inp.V, V)
    assert inp.criterion_ids == tuple(IDS) and inp.option_ids == ("galaxy", "gram", "yoga")
    assert inp.option_names[1] == "LG 그램 16" and inp.criterion_names[0] == "가격"
    assert inp.scales == scales and set(inp.scales) == {"price", "weight"}  # 숫자형만
    assert inp.K == 3 and inp.seed == 42 and inp.excluded == {} and inp.category == "purchase"
    np.testing.assert_allclose(inp.raw[:, 0], [189, 165, 150])
    assert inp.criteria[2].levels == 5 and inp.criteria[0].direction == "lower_better"
    assert inp.answers == () and inp.history == () and inp.covered == frozenset()


def test_career_rank_values_and_range_names():
    inp = eb.build_engine_input(career_snapshot(), [], [])
    # 흥미(rank): 그룹 1,3,2,4 → v = (G−g)/(G−1) = 1, 1/3, 2/3, 0
    np.testing.assert_allclose(inp.V[:, 0], [1.0, 1 / 3, 2 / 3, 0.0])
    interest = inp.metas[0]
    assert (interest.low_name, interest.high_name) == ("교육학과", "컴퓨터공학과")  # 순위: 그룹 번호 작을수록 좋음
    fit = inp.metas[1]
    assert (fit.low_name, fit.high_name) == ("경영학과", "컴퓨터공학과")  # 동점이면 앞선 선택지
    assert inp.criteria[0].input_mode == "rank" and inp.criteria[0].levels is None
    assert np.isnan(inp.raw).sum() == 0


def test_required_tag_and_threshold_exclude_options():
    cond = ConditionRow("k1", "required_tag", satisfied_by=frozenset({"gram", "yoga"}))
    inp = eb.build_engine_input(laptop_snapshot(conditions=(cond,)), [], [])
    assert inp.option_ids == ("gram", "yoga") and inp.excluded == {"galaxy": ["k1"]}
    assert inp.V.shape == (2, 3)
    thr = ConditionRow("k2", "threshold", criterion_id="price", operator="<=", value=160.0)
    inp = eb.build_engine_input(laptop_snapshot(conditions=(thr,)), [], [])
    assert inp.option_ids == ("yoga",) and inp.excluded == {"galaxy": ["k2"], "gram": ["k2"]}
    # 척도는 제외되지 않은 선택지만으로 정한다
    assert inp.scales["price"] == Scale(150, 150)


def _replace_criterion(snap, cid, **kw):
    return replace(snap, criteria=tuple(replace(c, **kw) if c.id == cid else c for c in snap.criteria))


def test_frozen_scale_wins_user_scale_is_used_otherwise():
    snap = _replace_criterion(laptop_snapshot(), "price", scale_min=100.0, scale_max=250.0)
    assert eb.build_engine_input(snap, [], []).scales["price"] == Scale(100, 250)  # 사용자 지정
    assert eb.compute_scales(snap)["weight"] == Scale(1.19, 1.45)
    frozen = _replace_criterion(laptop_snapshot(), "price", scale_min=140.0, scale_max=200.0, scale_frozen=True)
    inp = eb.build_engine_input(frozen, [], [])
    assert inp.scales["price"] == Scale(140, 200)
    np.testing.assert_allclose(inp.V[:, 0], 1 - (np.array([189, 165, 150]) - 140) / 60)
    assert eb.pending_scale_freeze(frozen).keys() == {"weight"}  # 이미 고정된 기준은 다시 고정하지 않는다
    assert eb.pending_scale_freeze(laptop_snapshot()).keys() == {"price", "weight"}


def test_incomplete_criterion_is_flat_and_not_asked():
    snap = laptop_snapshot()
    snap = replace(
        snap, values=tuple(v for v in snap.values if not (v.criterion_id == "perf" and v.option_id == "gram"))
    )
    inp = eb.build_engine_input(snap, [], [])
    np.testing.assert_allclose(inp.V[:, 2], 0.5)
    assert inp.V[:, 0].min() == 0 and inp.V[:, 0].max() == 1  # 나머지 기준은 정상
    assert eb.pending_scale_freeze(snap).keys() == {"price", "weight"}
    extra = CriterionRow("battery", "배터리", "numeric", "higher_better", 3, unit="시간")
    snap2 = replace(
        snap, criteria=(*snap.criteria, extra), values=(*snap.values, ValueRow("galaxy", "battery", value_num=9.0))
    )
    assert "battery" not in eb.pending_scale_freeze(snap2)  # 값이 덜 채워졌으면 고정하지 않는다


def test_boolean_and_nan_raw():
    snap = laptop_snapshot()
    snap = replace(
        snap,
        criteria=(*snap.criteria, CriterionRow("touch", "터치", "boolean", "true_better", 3)),
        values=(*snap.values, *(ValueRow(o, "touch", value_bool=o == "yoga") for o in ("galaxy", "gram", "yoga"))),
    )
    inp = eb.build_engine_input(snap, [], [])
    np.testing.assert_allclose(inp.V[:, 3], [0, 0, 1])
    assert np.isnan(inp.raw[:, 3]).all()


# ------------------------------------------------------------------ 질문·답변 → 엔진
def test_answers_history_and_covered():
    snap = laptop_snapshot()
    qs = [
        _q("q1", 1, {"kind": "slider_pair", "a": "price", "b": "weight"}),
        _q("q0", 0, {"kind": "slider_pair", "a": "weight", "b": "perf"}, origin="free_text"),
        _q("q2", 2, {"kind": "ab_criteria", "a": "price", "b": "perf"}, "ab_criteria"),
        _q("q3", 3, {"kind": "slider_pair", "a": "perf", "b": "weight"}),  # 건너뛴 질문
        _q("q4", 4, {"kind": "slider_pair", "a": "price", "b": "ghost"}),  # 지워진 기준
        _q("q5", 5, {"kind": "slider_pair", "a": "price", "b": "perf"}),  # 무효 답변
    ]
    ans = [
        _a("a2", "q2", {"choice": "B"}),
        _a("a1", "q1", {"value": -2}),
        _a("a0", "q0", {"value": 3}),
        _a("a3", "q3", {"skip": True}),
        _a("a5", "q5", {"value": 1}, valid=False),
    ]
    inp = eb.build_engine_input(snap, qs, ans)
    assert [(a.spec, a.response, a.origin) for a in inp.answers] == [
        (SliderSpec(1, 2), 3, "free_text"),
        (SliderSpec(0, 1), -2, "engine"),
        (CriteriaABSpec(0, 2), "B", "engine"),
        (SliderSpec(2, 1), None, "engine"),
    ]  # seq 순, 건너뛰기는 None
    # history: 엔진 질문만 seq 순, 건너뛴 것·무효 답변의 질문 포함, 가상·지워진 기준 질문은 제외
    assert inp.history == (SliderSpec(0, 1), CriteriaABSpec(0, 2), SliderSpec(2, 1), SliderSpec(0, 2))
    assert inp.covered == frozenset({0, 1, 2})  # 건너뛰기(q3)는 안 세지만 다른 답변이 다 덮는다
    only_skip = eb.build_engine_input(snap, qs[:1] + qs[3:4], [_a("a3", "q3", {"skip": True})])
    assert only_skip.covered == frozenset()


# ------------------------------------------------------------------ spec ↔ dict
def test_spec_round_trip_including_scenario():
    specs = [
        SliderSpec(0, 2),
        CriteriaABSpec(1, 2),
        ScenarioSpec(0, 1, (0.5, -0.3, 0.0), 20.0, 0.1),
    ]
    for s in specs:
        assert eb.spec_from_dict(eb.spec_to_dict(s, IDS), IDS) == s
    d = eb.spec_to_dict(specs[2], IDS)
    assert d == {
        "kind": "ab_scenario",
        "a": "price",
        "b": "weight",
        "delta": {"price": 0.5, "weight": -0.3, "perf": 0.0},
        "rawA": 20.0,
        "rawB": 0.1,
    }
    assert eb.spec_to_dict(specs[0], IDS) == {"kind": "slider_pair", "a": "price", "b": "perf"}
    assert [eb.question_type_of(s) for s in specs] == ["slider_pair", "ab_criteria", "ab_scenario"]


def test_spec_from_dict_follows_criterion_changes():
    d = eb.spec_to_dict(ScenarioSpec(0, 1, (0.5, -0.3, 0.0), 20.0, 0.1), IDS)
    added = [*IDS, "battery"]  # 기준 추가: 새 기준의 Δ는 0
    assert eb.spec_from_dict(d, added) == ScenarioSpec(0, 1, (0.5, -0.3, 0.0, 0.0), 20.0, 0.1)
    reordered = ["perf", "price", "weight"]  # 인덱스는 바뀌어도 id로 같은 기준을 가리킨다
    assert eb.spec_from_dict(d, reordered) == ScenarioSpec(1, 2, (0.0, 0.5, -0.3), 20.0, 0.1)
    with pytest.raises(ValueError):
        eb.spec_from_dict(d, ["price", "perf"])  # weight가 지워짐
    with pytest.raises(ValueError):
        eb.spec_from_dict({"kind": "numeric", "a": "price", "b": "weight"}, IDS)


def test_index_helpers_and_preference_kind():
    assert eb.index_of(IDS, "weight") == 1 and eb.id_of(IDS, 2) == "perf"
    with pytest.raises(ValueError):
        eb.index_of(IDS, "x")
    assert eb.preference_kind_for(SliderSpec(0, 1)) == "stated"
    assert eb.preference_kind_for(CriteriaABSpec(0, 1)) == "stated"
    assert eb.preference_kind_for(ScenarioSpec(0, 1, (1, -1, 0), 1, 1)) == "revealed"


# ------------------------------------------------------------------ 응답 형식
@pytest.mark.parametrize(
    ("qtype", "response", "expected"),
    [
        ("slider_pair", {"value": -5}, -5),
        ("slider_pair", {"value": 0}, 0),
        ("slider_pair", {"value": 5}, 5),
        ("ab_criteria", {"choice": "A"}, "A"),
        ("ab_scenario", {"choice": "B"}, "B"),
        ("slider_pair", {"skip": True}, None),
        ("ab_scenario", {"skip": True}, None),
    ],
)
def test_parse_response_ok(qtype, response, expected):
    assert eb.parse_response(qtype, response) == expected


@pytest.mark.parametrize(
    ("qtype", "response"),
    [
        ("slider_pair", {"value": 6}),
        ("slider_pair", {"value": -6}),
        ("slider_pair", {"value": 1.5}),
        ("slider_pair", {"value": "1"}),
        ("slider_pair", {"value": True}),
        ("slider_pair", {"value": None}),
        ("slider_pair", {"choice": "A"}),
        ("slider_pair", {}),
        ("slider_pair", {"value": 1, "extra": 1}),
        ("ab_criteria", {"value": 1}),
        ("ab_criteria", {"choice": "C"}),
        ("ab_scenario", {"choice": "a"}),
        ("ab_scenario", {"choice": None}),
        ("ab_scenario", {"choice": "A", "skip": True}),
        ("slider_pair", {"skip": False}),
        ("slider_pair", {"skip": 1}),
        ("slider_pair", "A"),
        ("slider_pair", None),
        ("slider_pair", [1]),
        ("numeric", {"value": 1}),
        ("free_text", {"choice": "A"}),
    ],
)
def test_parse_response_rejects(qtype, response):
    with pytest.raises(FlowError) as exc:
        eb.parse_response(qtype, response)
    assert (exc.value.http_status, exc.value.code) == (400, "VALIDATION_ERROR")
    assert exc.value.details is not None and exc.value.details["field"] == "response"


def test_response_to_dict_round_trip():
    for qtype, resp in [
        ("slider_pair", {"value": -3}),
        ("ab_scenario", {"choice": "A"}),
        ("ab_criteria", {"skip": True}),
    ]:
        assert eb.response_to_dict(qtype, eb.parse_response(qtype, resp)) == resp
