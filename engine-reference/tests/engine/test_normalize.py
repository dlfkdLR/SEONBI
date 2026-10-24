"""정규화·척도 고정·필수 조건 (개발기획서 5.1, 3.4, 3.5)."""

import numpy as np
import pytest
from example_data import CRIT, OPTS, VALS
from hypothesis import given, settings
from hypothesis import strategies as st

from app.engine.normalize import Condition, Criterion, Scale, freeze_scales, normalize


def test_example_matrix(example):
    scales, V = example
    assert scales["price"] == Scale(150, 189)
    np.testing.assert_allclose(V, [[0, 0.846, 0.75], [0.615, 1, 0.5], [1, 0, 0.75]], atol=1e-3)


def test_rank_ties_and_exclusion():
    crit = [Criterion("int", "rating", input_mode="rank"), Criterion("dorm", "boolean", "true_better")]
    vals = {
        ("a", "int"): 1,
        ("b", "int"): 2,
        ("c", "int"): 2,
        ("d", "int"): 3,
        ("a", "dorm"): True,
        ("b", "dorm"): True,
        ("c", "dorm"): False,
        ("d", "dorm"): True,
    }
    cond = Condition("k1", "required_tag", satisfied_by=frozenset({"a", "b", "d"}))
    m, excluded = normalize(vals, crit, {}, option_ids=["a", "b", "c", "d"], conditions=[cond])
    assert excluded == {"c": ["k1"]} and m.option_ids == ("a", "b", "d")
    np.testing.assert_allclose(m.values[:, 0], [1, 0.5, 0])  # 제외된 선택지를 빼고 순위를 다시 매김
    m2, _ = normalize({("a", "int"): 2, ("b", "int"): 2}, crit[:1], {}, option_ids=["a", "b"])
    np.testing.assert_allclose(m2.values[:, 0], [0.5, 0.5])  # 모두 동순위


def test_rating_levels_threshold_and_constant_column():
    crit = [Criterion("fit", "rating", levels=4), Criterion("x", "numeric"), Criterion("p", "numeric", "lower_better")]
    vals = {("a", "fit"): 1, ("b", "fit"): 4, ("a", "x"): 5, ("b", "x"): 5, ("a", "p"): 100, ("b", "p"): 300}
    scales = freeze_scales(crit, vals)
    m, _ = normalize(vals, crit, scales, option_ids=["a", "b"])
    np.testing.assert_allclose(m.values, [[0, 0.5, 1], [1, 0.5, 0]])  # 값이 모두 같은 열은 0.5
    _, excluded = normalize(
        vals, crit, scales, option_ids=["a", "b"], conditions=[Condition("k", "threshold", "p", "<=", 200)]
    )
    assert excluded == {"b": ["k"]}
    assert freeze_scales(crit, vals, excluded={"b"})["p"] == Scale(100, 100)  # 제외된 선택지는 척도에서 뺀다
    with pytest.raises(ValueError):
        normalize({("a", "fit"): 1}, crit[:1], {}, option_ids=["a", "b"])  # 값이 빠짐


def test_user_scale_wins_and_clips():
    crit = [Criterion("p", "numeric", "lower_better", scale_min=100, scale_max=300)]
    vals = {("a", "p"): 100, ("b", "p"): 200, ("c", "p"): 400}
    scales = freeze_scales(crit, vals)
    assert scales["p"] == Scale(100, 300)
    m, _ = normalize(vals, crit, scales, option_ids=["a", "b", "c"])
    np.testing.assert_allclose(m.values[:, 0], [1, 0.5, 0])  # 범위 밖(400)은 0으로 자른다


def test_all_options_excluded_gives_empty_matrix():
    cond = Condition("k", "threshold", "price", "<=", 10)
    m, excluded = normalize(VALS, CRIT, freeze_scales(CRIT, VALS), option_ids=OPTS, conditions=[cond])
    assert m.values.shape == (0, 3) and set(excluded) == set(OPTS)


@settings(max_examples=60, deadline=None)
@given(
    st.lists(st.floats(0, 1000, allow_nan=False), min_size=2, max_size=8, unique=True),
    st.sampled_from(["higher_better", "lower_better"]),
)
def test_numeric_direction_is_monotone(xs, direction):
    crit = [Criterion("c", "numeric", direction)]
    vals = {(str(i), "c"): x for i, x in enumerate(xs)}
    ids = [str(i) for i in range(len(xs))]
    m, _ = normalize(vals, crit, freeze_scales(crit, vals), option_ids=ids)
    v = m.values[:, 0]
    assert np.all((v >= 0) & (v <= 1))
    order = np.argsort(xs)
    diffs = np.diff(v[order])
    assert np.all(diffs >= -1e-12) if direction == "higher_better" else np.all(diffs <= 1e-12)


@settings(max_examples=40, deadline=None)
@given(st.integers(3, 7))
def test_rating_levels_cover_zero_to_one(levels):
    crit = [Criterion("r", "rating", levels=levels)]
    vals = {(str(r), "r"): r for r in range(1, levels + 1)}
    m, _ = normalize(vals, crit, {}, option_ids=[str(r) for r in range(1, levels + 1)])
    np.testing.assert_allclose(m.values[:, 0], np.arange(levels) / (levels - 1))


@settings(max_examples=60, deadline=None)
@given(st.lists(st.integers(1, 6), min_size=2, max_size=8))
def test_rank_groups_are_ordered_and_tied(groups):
    crit = [Criterion("i", "rating", input_mode="rank")]
    vals = {(str(i), "i"): g for i, g in enumerate(groups)}
    m, _ = normalize(vals, crit, {}, option_ids=[str(i) for i in range(len(groups))])
    v = m.values[:, 0]
    for i, gi in enumerate(groups):
        for j, gj in enumerate(groups):
            if gi == gj:
                assert v[i] == pytest.approx(v[j])
            elif gi < gj:
                assert v[i] > v[j]  # 그룹 번호가 작을수록(1=가장 좋음) 값이 크다
