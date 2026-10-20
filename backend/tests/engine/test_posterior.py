"""입자 샘플링·재가중·재샘플링 (개발기획서 5.2, 5.4, 5.14)."""

import numpy as np
import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from app.engine.likelihood import CriteriaABSpec, SliderSpec
from app.engine.posterior import Answer, best_shares, fit, prior_particles, reweight, summary


def test_deterministic_and_resampling_happens():
    answers = [Answer(SliderSpec(0, 1), 5), Answer(SliderSpec(1, 2), 5), Answer(CriteriaABSpec(0, 2), "A")] * 4
    p1, p2 = fit(7, 3, answers), fit(7, 3, answers)
    np.testing.assert_array_equal(p1.W, p2.W)
    np.testing.assert_array_equal(p1.p, p2.p)
    assert abs(p1.p.sum() - 1) < 1e-9 and np.allclose(p1.W.sum(1), 1)
    m = p1.mean
    assert m[0] > m[1] > m[2]
    pw = reweight(prior_particles(7, 3), answers)
    assert 1 / np.sum(pw**2) < 400  # 재샘플링 없이 가중만 하면 ESS가 400 미만이라 재샘플링이 필요한 경우
    np.testing.assert_allclose(p1.mean, pw @ prior_particles(7, 3), atol=0.03)  # 재샘플링 결과가 같은 분포를 가리킴


def test_all_skipped_equals_prior():
    prior = prior_particles(3, 4)
    post = fit(3, 4, [Answer(SliderSpec(0, 1), None)] * 6)
    np.testing.assert_array_equal(post.W, prior)
    np.testing.assert_allclose(post.p, 1 / len(prior))


def test_adding_a_criterion_keeps_old_answers_valid():
    """5.14: 질문 중 기준 추가 → K+1차원으로 같은 시드에서 다시 뽑고 기존 답변을 다시 적용한다."""
    answers = [Answer(SliderSpec(0, 1), 5), Answer(SliderSpec(1, 2), 3)]
    old, new = fit(42, 3, answers), fit(42, 4, answers)
    assert new.W.shape == (4000, 4) and np.allclose(new.W.sum(1), 1)
    np.testing.assert_array_equal(new.W, fit(42, 4, answers).W)  # 같은 시드 → 같은 결과
    assert new.mean[0] > new.mean[1] > new.mean[2]  # 기존 답변의 순서 정보가 그대로 반영된다
    assert old.mean[0] > old.mean[1] > old.mean[2]


def test_free_text_answers_are_used_for_estimation():
    virtual = Answer(SliderSpec(0, 1), 5, origin="free_text")
    with_virtual = fit(1, 3, [virtual])
    assert with_virtual.mean[0] > with_virtual.mean[1]


def test_ties_split_p_best_and_single_option():
    V = np.array([[0.5, 0.5], [0.5, 0.5], [0.0, 0.0]])
    shares = best_shares(prior_particles(0, 2, 100), V)
    np.testing.assert_allclose(shares[:, :2].sum(1), 1)
    np.testing.assert_allclose(shares[:, 0], shares[:, 1])  # 동점이면 비중을 반씩 나눈다
    one = np.array([[0.3, 0.9]])
    s = summary(fit(0, 2, []), one)
    assert s.entropy == pytest.approx(0.0, abs=1e-9) and s.p_best == pytest.approx([1.0])


@settings(max_examples=40, deadline=None)
@given(st.integers(0, 10_000), st.integers(2, 7))
def test_prior_weights_sum_to_one(seed, K):
    W = prior_particles(seed, K, 200)
    assert W.shape == (200, K) and np.all(W >= 0) and np.allclose(W.sum(1), 1)
    np.testing.assert_array_equal(W, prior_particles(seed, K, 200))  # 시드 고정 재현성
