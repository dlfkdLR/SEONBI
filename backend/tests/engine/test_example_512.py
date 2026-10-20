"""개발기획서 5.12 계산 예시를 시드 42로 그대로 재현한다."""

import numpy as np
import pytest
from example_data import CRIT

from app.engine.decision_analysis import change_conditions, stability
from app.engine.likelihood import SliderSpec
from app.engine.posterior import Answer, fit, summary
from app.engine.question_selector import build_scenarios, score_candidates


def test_example_512_numbers(example):
    scales, V = example
    q1, q2 = Answer(SliderSpec(0, 2), -2), Answer(SliderSpec(2, 1), 1)
    post = fit(42, 3, [q1, q2])
    s = summary(post, V)
    np.testing.assert_allclose(s.mean, [0.252, 0.317, 0.431], atol=2e-3)
    np.testing.assert_allclose(s.p_best, [0.082, 0.639, 0.279], atol=2e-3)

    cands = {c.spec: c.eig for c in score_candidates(post, V, CRIT, [], scales=scales)}
    assert abs(cands[SliderSpec(0, 1)] - 0.304) < 2e-3
    big = next(x for x in build_scenarios(s.mean, 0, 1, CRIT, scales) if x.raw_a == 20)
    assert big.raw_b == pytest.approx(0.10)
    assert abs(cands[big] - 0.045) < 2e-3

    post3 = fit(42, 3, [q1, q2, Answer(big, "B")])
    s3 = summary(post3, V)
    np.testing.assert_allclose(s3.mean, [0.215, 0.357, 0.428], atol=2e-3)
    np.testing.assert_allclose(s3.p_best, [0.104, 0.729, 0.167], atol=2e-3)
    assert abs(s3.entropy - 1.103) < 2e-3
    assert post3.ess > 400  # 재샘플링 없이 지나간다

    cc = {c.criterion: c for c in change_conditions(s3.mean, V)}
    assert cc[0].direction == "decrease" and cc[0].new_top == 0 and abs(cc[0].threshold - 0.097) < 2e-3
    assert cc[1].direction == "decrease" and cc[1].new_top == 2 and abs(cc[1].threshold - 0.227) < 2e-3
    assert cc[2].direction == "increase" and cc[2].new_top == 0 and abs(cc[2].threshold - 0.568) < 2e-3
    st = stability(post3, V, seed=1)
    assert st.top == 1 and st.grade == "medium" and st.perturb_keep > 0.95
