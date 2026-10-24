"""질문 후보·상황형 구성·선택 규칙 (개발기획서 5.5)."""

import numpy as np
import pytest
from example_data import CRIT
from hypothesis import given, settings
from hypothesis import strategies as st

from app.engine.likelihood import CriteriaABSpec, ScenarioSpec, SliderSpec
from app.engine.normalize import Criterion, Scale
from app.engine.posterior import fit
from app.engine.question_selector import active_criteria, build_scenarios, next_question, nice_step, score_candidates


def test_nice_step():
    assert nice_step(39) == 2 and nice_step(0.26) == pytest.approx(0.02) and nice_step(100) == 10


def test_scenarios_use_rounded_units_and_skip_rank():
    crit = [
        Criterion("int", "rating", input_mode="rank"),
        Criterion("fit", "rating", levels=4),
        Criterion("t", "numeric", "lower_better"),
        Criterion("dorm", "boolean", "true_better"),
    ]
    scales = {"t": Scale(20, 90)}
    mean = np.array([0.25, 0.25, 0.25, 0.25])
    assert build_scenarios(mean, 0, 1, crit, scales) == []  # 순위 입력 기준은 상황형에 쓰지 않는다
    s = build_scenarios(mean, 1, 2, crit, scales)
    assert s and all(x.raw_a == int(x.raw_a) for x in s) and all(x.raw_b % 5 == 0 for x in s)
    for x in s:  # 정규화 Δ는 화면에 보이는 반올림 값에서 다시 계산된다
        assert x.delta[1] == pytest.approx(x.raw_a / 3) and x.delta[2] == pytest.approx(-x.raw_b / 70)
    assert build_scenarios(mean, 2, 3, crit, scales)[0].delta[3] == -1.0  # 예/아니요는 1단위


@settings(max_examples=40, deadline=None)
@given(st.permutations([0, 1, 2, 3]), st.integers(0, 3))
def test_rank_criteria_never_in_scenario_candidates(order, rank_pos):
    crit = [Criterion(f"c{i}", "numeric", "lower_better") for i in range(4)]
    crit[rank_pos] = Criterion(f"c{rank_pos}", "rating", input_mode="rank")
    scales = {c.id: Scale(0, 10) for c in crit if c.type == "numeric"}
    mean = np.full(4, 0.25)
    for a, b in [(order[0], order[1]), (order[2], order[3])]:
        found = build_scenarios(mean, a, b, crit, scales)
        if rank_pos in (a, b):
            assert found == []


def test_initial_chain_quota_and_edges(example):
    scales, V = example
    post = fit(1, 3, [])
    assert next_question(post, V, CRIT, [], scales=scales) == SliderSpec(0, 1)
    assert next_question(post, V, CRIT, [SliderSpec(0, 1)], scales=scales) == SliderSpec(1, 2)
    hist = [SliderSpec(0, 1), SliderSpec(1, 2), SliderSpec(0, 2)]
    q4 = next_question(post, V, CRIT, hist, scales=scales)
    assert isinstance(q4, ScenarioSpec)  # 4번째 질문부터 짝수 번째는 상황형
    assert next_question(post, V, CRIT, [*hist, q4], scales=scales) is not None


def test_no_question_when_nothing_to_ask(example):
    scales, V = example
    post = fit(1, 3, [])
    Vc = V.copy()
    Vc[:, 1] = 0.5
    Vc[:, 2] = 0.5
    assert active_criteria(Vc) == [0] and next_question(post, Vc, CRIT, [], scales=scales) is None
    single = V[:1]  # 선택지 1개 → 모든 기준이 값이 같아 물을 것이 없다
    assert active_criteria(single) == [] and next_question(post, single, CRIT, [], scales=scales) is None


def test_two_criteria_may_repeat_the_same_pair(example):
    scales, V = example
    post2, V2, history = fit(1, 2, []), V[:, :2], []
    for _ in range(6):
        q = next_question(post2, V2, CRIT[:2], history, scales=scales)
        assert q is not None and {q.a, q.b} == {0, 1}
        history.append(q)


def _five_criteria():
    crit = [Criterion(f"c{i}", "numeric", "lower_better") for i in range(5)]
    scales = {c.id: Scale(0, 100) for c in crit}
    rng = np.random.default_rng(4)
    V = rng.random((3, 5))
    return crit, scales, V


def test_uncovered_criterion_is_asked_first():
    """5.5 선택 규칙 2: 답변에 한 번도 나오지 않은 기준이 있으면 그 기준이 들어간 후보에서 고른다."""
    crit, scales, V = _five_criteria()
    post = fit(5, 5, [])
    chain = [SliderSpec(0, 1), SliderSpec(1, 2), SliderSpec(2, 3)]  # 기준 4는 아직 안 나옴
    q4 = next_question(post, V, crit, chain, scales=scales)  # 4번째 = 상황형 차례
    assert isinstance(q4, ScenarioSpec) and 4 in (q4.a, q4.b)
    # 5번째 = 일반 차례. q4에 "잘 모르겠어요"로 답해 기준 4가 아직 유효 답변에 없는 경우
    q5 = next_question(post, V, crit, [*chain, q4], scales=scales, covered={0, 1, 2, 3})
    assert q5 is not None and 4 in (q5.a, q5.b)
    # 기준 4에 실제로 답했다면(기본값: 낸 질문에 나온 기준) 더 이상 우선하지 않는다
    assert next_question(post, V, crit, [*chain, q4], scales=scales) is not None
    # 기준 4가 이미 나왔다고(유효 답변에 있다고) 알려 주면 규칙 2는 적용되지 않는다
    assert next_question(post, V, crit, chain, scales=scales, covered={0, 1, 2, 3, 4}) is not None


def test_skipped_answers_do_not_cover_a_criterion():
    crit, scales, V = _five_criteria()
    post = fit(5, 5, [])
    chain = [SliderSpec(0, 1), SliderSpec(1, 2), SliderSpec(2, 3)]
    q = next_question(post, V, crit, chain, scales=scales, covered={0, 1, 2, 3})  # 4는 "잘 모르겠어요"만 있었음
    assert q is not None and 4 in (q.a, q.b)


def test_rank_uncovered_criterion_falls_back_to_non_scenario():
    """5.5 선택 규칙 5: 아직 안 나온 기준이 순위 입력이면 상황형 후보가 없으므로 규칙 2(일반 후보)를 따른다."""
    crit, scales, V = _five_criteria()
    crit[4] = Criterion("c4", "rating", input_mode="rank")
    post = fit(5, 5, [])
    chain = [SliderSpec(0, 1), SliderSpec(1, 2), SliderSpec(2, 3)]
    q4 = next_question(post, V, crit, chain, scales=scales)  # 상황형 차례지만 기준 4로는 상황형을 못 만든다
    assert isinstance(q4, (SliderSpec, CriteriaABSpec)) and 4 in (q4.a, q4.b)


def test_new_criterion_added_mid_questioning_is_asked_next(example):
    scales, V = example
    crit = [*CRIT, Criterion("battery", "numeric")]
    scales = {**scales, "battery": Scale(5, 15)}
    V4 = np.column_stack([V, [0.2, 0.9, 0.6]])
    post = fit(1, 4, [])
    hist = [SliderSpec(0, 1), SliderSpec(1, 2), SliderSpec(0, 2), CriteriaABSpec(1, 2), SliderSpec(0, 1)]
    q = next_question(post, V4, crit, hist, scales=scales, covered={0, 1, 2})  # 새 기준 3
    assert 3 in (q.a, q.b) if q else False


def test_candidate_count(example):
    scales, V = example
    post = fit(1, 3, [])
    cands = score_candidates(post, V, CRIT, [], scales=scales)
    assert len(cands) == 3 * (2 + 3)  # 쌍 3개 × (슬라이더 1 + A/B 1 + 상황형 최대 3)
