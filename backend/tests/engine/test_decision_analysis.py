"""점수·순위·안정성·전환 조건·민감도 (개발기획서 5.7~5.9)."""

import numpy as np
import pytest
from example_data import CRIT, VALS
from hypothesis import given, settings
from hypothesis import strategies as st

from app.engine.decision_analysis import (
    _shift,
    change_conditions,
    ranking,
    scores,
    sensitivity,
    stability,
    value_conditions,
)
from app.engine.likelihood import SliderSpec
from app.engine.normalize import Criterion, Scale
from app.engine.posterior import Answer, fit


def test_scores_and_stable_ranking():
    V = np.array([[1.0, 0.0], [0.0, 1.0], [0.5, 0.5]])
    np.testing.assert_allclose(scores(np.array([0.5, 0.5]), V), [0.5, 0.5, 0.5])
    assert ranking(np.array([0.5, 0.5]), V) == [0, 1, 2]  # 동점이면 입력 순서
    assert ranking(np.array([0.9, 0.1]), V) == [0, 2, 1]


@settings(max_examples=60, deadline=None)
@given(st.integers(0, 10_000))
def test_change_condition_is_exact_crossing(seed):
    rng = np.random.default_rng(seed)
    V = rng.random((4, 4))
    mean = rng.dirichlet(np.ones(4))
    top = ranking(mean, V)[0]
    for c in change_conditions(mean, V):
        if c.threshold is None:
            assert c.new_top is None and c.direction is None
            continue
        U = V @ _shift(mean, c.criterion, c.threshold)
        assert abs(U[top] - U[c.new_top]) < 1e-9  # 전환점에서 두 점수가 같다
        assert (c.threshold > c.current) == (c.direction == "increase")
        # 5.8: 새 1위가 전체 1위인지는 전환점 ± 0.001에서 확인한다
        # (그 사이에 다른 선택지와 순위가 바뀔 수 있어 U.max() == U[top]까지는 요구하지 않는다)
        probe = c.threshold + (0.001 if c.direction == "increase" else -0.001)
        assert int(np.argmax(V @ _shift(mean, c.criterion, min(max(probe, 0.0), 1.0)))) == c.new_top


def test_no_change_when_one_option_dominates():
    V = np.array([[1.0, 1.0], [0.0, 0.0]])
    assert all(c.threshold is None for c in change_conditions(np.array([0.5, 0.5]), V))


def test_sensitivity_curve_shape():
    rng = np.random.default_rng(0)
    V, mean = rng.random((4, 4)), rng.dirichlet(np.ones(4))
    curve = sensitivity(mean, V, 1)
    assert curve.scores.shape == (21, 4) and curve.t[0] == 0 and curve.t[-1] == 1


def test_stability_grade_uses_p_best(example):
    _, V = example
    strong = [Answer(SliderSpec(2, 0), 5), Answer(SliderSpec(1, 0), 5), Answer(SliderSpec(1, 2), 5)] * 2
    st_ = stability(fit(1, 3, strong), V, seed=1)
    assert st_.grade == "high" and st_.p_best >= 0.8
    st_none = stability(fit(1, 3, []), V, seed=1)
    assert st_none.grade == "low" and st_none.p_best < 0.55


def _raw_example() -> np.ndarray:
    ids = ["galaxy", "gram", "yoga"]
    return np.array([[VALS[(o, c.id)] for c in CRIT] for o in ids], dtype=float)


def test_value_conditions_example(example):
    scales, V = example
    mean = np.array([0.215, 0.357, 0.428])  # 5.12 Q3 이후, 1위는 그램
    found = value_conditions(mean, V, CRIT, scales, _raw_example())
    assert found, "전환 조건이 하나는 있어야 한다"
    top = ranking(mean, V)[0]
    U = V @ mean
    for c in found:
        assert c.option != top
        v = V.copy()
        v[c.option, c.criterion] = c.needed_v
        U2 = v @ mean
        assert U2[c.option] == pytest.approx(U2[top], abs=1e-9) or U2[c.option] > U2[top]  # 필요한 값에서 1위가 된다
        assert V[c.option, c.criterion] <= c.needed_v <= 1 + 1e-9
        crit = CRIT[c.criterion]
        if crit.type == "numeric":
            span = scales[crit.id].span
            thr_v = (scales[crit.id].max - c.threshold) / span  # 둘 다 lower_better
            assert thr_v >= c.needed_v - 1e-9  # 반올림한 표시값으로도 충분하다
            assert c.direction == "decrease" and c.threshold < c.current
        else:
            assert c.threshold == int(c.threshold) and c.threshold > c.current and c.direction == "increase"
    assert [c.needed_v - V[c.option, c.criterion] for c in found] == sorted(
        c.needed_v - V[c.option, c.criterion] for c in found
    )  # 가까운 순
    assert U[top] >= U.max() - 1e-12


def test_value_conditions_skip_rank_boolean_and_unreachable():
    crit = [
        Criterion("a", "numeric", "higher_better"),
        Criterion("i", "rating", input_mode="rank"),
        Criterion("d", "boolean", "true_better"),
    ]
    scales = {"a": Scale(0, 100)}
    V = np.array([[1.0, 1.0, 1.0], [0.0, 0.0, 0.0], [0.5, 0.5, 0.5]])
    raw = np.array([[100.0, np.nan, np.nan], [0.0, np.nan, np.nan], [50.0, np.nan, np.nan]])
    assert value_conditions(np.array([0.5, 0.3, 0.2]), V, crit, scales, raw) == []  # 순위·예/아니요는 제외, 1위 고정
    # 1위(옵션0)는 a=0.6이지만 나머지 기준 점수가 높다. 가중치 a=0.9 → 점수 0.64 / 0.45 / 0
    V2 = np.array([[0.6, 1.0, 1.0], [0.5, 0.0, 0.0], [0.0, 0.0, 0.0]])
    raw2 = np.array([[60.0, np.nan, np.nan], [50.0, np.nan, np.nan], [0.0, np.nan, np.nan]])
    found = value_conditions(np.array([0.9, 0.05, 0.05]), V2, crit, scales, raw2)
    assert [(c.option, c.criterion) for c in found] == [(1, 0), (2, 0)]  # 순위·예/아니요 열은 나오지 않고, 가까운 순
    assert all(c.direction == "increase" for c in found)
    assert found[0].needed_v == pytest.approx(0.5 + 0.19 / 0.9) and found[0].threshold == 80  # 71.1을 10 단위로 올림
    # 높은 단계로도 못 이기면 만들지 않는다
    V3 = np.array([[1.0, 1.0], [0.0, 0.0]])
    assert value_conditions(np.array([0.1, 0.9]), V3, crit[:2], scales, np.array([[1.0, np.nan], [0.0, np.nan]])) == []
