"""질문 유형별 로그 우도 (개발기획서 5.3)."""

import numpy as np
import pytest

from app.engine.likelihood import (
    CriteriaABSpec,
    ScenarioSpec,
    SliderSpec,
    log_likelihood,
    preference_kind,
    response_logprobs,
    response_options,
)
from app.engine.posterior import prior_particles


def test_probabilities_sum_to_one_and_match_log_likelihood():
    W = prior_particles(0, 3, 500)
    for spec in [SliderSpec(0, 1), CriteriaABSpec(0, 2), ScenarioSpec(0, 1, (0.3, -0.2, 0), 1, 1)]:
        L = np.exp(response_logprobs(spec, W))
        np.testing.assert_allclose(L.sum(1), 1, atol=1e-9)
        for j, r in enumerate(response_options(spec)):
            np.testing.assert_allclose(log_likelihood(spec, r, W), np.log(L[:, j]), atol=1e-9)


def test_skip_has_no_information_and_bad_response_raises():
    W = prior_particles(0, 3, 100)
    assert np.all(log_likelihood(SliderSpec(0, 1), None, W) == 0)
    with pytest.raises(ValueError):
        log_likelihood(SliderSpec(0, 1), 6, W)  # 슬라이더는 -5..5
    with pytest.raises(ValueError):
        log_likelihood(CriteriaABSpec(0, 1), 3, W)  # A/B만


def test_slider_has_eleven_options_and_sign_rule():
    assert list(response_options(SliderSpec(0, 1))) == list(range(-5, 6))
    W = np.array([[0.8, 0.1, 0.1], [0.1, 0.8, 0.1]])  # 입자 0은 기준0이 훨씬 중요
    lp = log_likelihood(SliderSpec(0, 1), 5, W)  # s > 0 이면 왼쪽(a)이 더 중요
    assert lp[0] > lp[1]


def test_stated_vs_revealed_tag():
    assert preference_kind(SliderSpec(0, 1)) == "stated"
    assert preference_kind(CriteriaABSpec(0, 1)) == "stated"
    assert preference_kind(ScenarioSpec(0, 1, (0.3, -0.2, 0), 1, 1)) == "revealed"


def test_scenario_prefers_higher_score_side():
    spec = ScenarioSpec(0, 1, (0.4, -0.1, 0.0), 1, 1)  # A는 기준0이 좋고 기준1이 조금 나쁨
    W = np.array([[0.8, 0.1, 0.1], [0.1, 0.8, 0.1]])
    p_a = np.exp(log_likelihood(spec, "A", W))
    assert p_a[0] > 0.5 > p_a[1]


@pytest.mark.parametrize("seed", range(5))
def test_slider_expected_value_rises_with_log_ratio(seed):
    W = prior_particles(seed, 2, 400)
    L = np.exp(response_logprobs(SliderSpec(0, 1), W))
    expected = L @ np.array(response_options(SliderSpec(0, 1)), dtype=float)
    order = np.argsort(np.log(W[:, 0] / W[:, 1]))
    assert np.all(np.diff(expected[order]) >= -1e-9)
