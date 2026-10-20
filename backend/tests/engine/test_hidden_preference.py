"""진술 선호와 드러난 선호의 불일치 (개발기획서 5.6)."""

from app.engine.hidden_preference import detect
from app.engine.likelihood import CriteriaABSpec, ScenarioSpec, SliderSpec
from app.engine.posterior import Answer, prior_particles

W = prior_particles(5, 3)
SCEN = ScenarioSpec(0, 1, (0.6, -0.3, 0.0), 20, 0.1)  # A: 기준0이 크게 좋고 기준1이 조금 나쁨
STATED = [Answer(SliderSpec(0, 1), 4), Answer(CriteriaABSpec(0, 1), "A")]


def test_contradiction_is_reported():
    found = detect(W, [*STATED, *[Answer(SCEN, "B")] * 3])
    assert len(found) == 1 and found[0].stated_over == 0 and found[0].revealed_over == 1
    assert found[0].stated_p >= 0.75 and found[0].revealed_p <= 0.25 and found[0].n_scenarios == 3


def test_consistent_answers_report_nothing():
    assert detect(W, [*STATED, *[Answer(SCEN, "A")] * 3]) == []


def test_needs_two_scenarios_per_pair():
    assert detect(W, [*STATED, Answer(SCEN, "B")]) == []


def test_skips_are_ignored_and_empty_inputs():
    assert detect(W, [Answer(SCEN, None)] * 3 + STATED) == []
    assert detect(W, STATED) == [] and detect(W, []) == []
