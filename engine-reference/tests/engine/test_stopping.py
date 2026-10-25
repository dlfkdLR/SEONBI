"""질문 종료 판단과 추가 질문 라운드 (개발기획서 5.10, 4.2)."""

import pytest

from app.engine.likelihood import SliderSpec
from app.engine.posterior import Answer, fit
from app.engine.stopping import (
    extra_round_limit,
    is_low_confidence,
    max_questions,
    raised_limit,
    should_stop,
)

SKIP = Answer(SliderSpec(0, 1), None)
STRONG = [Answer(SliderSpec(2, 0), 5), Answer(SliderSpec(1, 0), 5), Answer(SliderSpec(1, 2), 5)] * 2


def test_first_round_reasons(example):
    _, V = example
    post = fit(1, 3, [])
    assert should_stop(post, V, [SKIP] * 2, 0.5) == (False, None)
    assert should_stop(post, V, [SKIP] * 2, 0.5, user_stopped=True) == (True, "USER_STOPPED")
    assert should_stop(post, V, [SKIP] * 4, 0.01) == (True, "LOW_INFO_GAIN")
    assert should_stop(post, V, [SKIP] * 2, 0.01) == (False, None)  # 최소 3문항 전에는 끝내지 않는다
    assert should_stop(post, V, [SKIP] * max_questions(3), 0.5) == (True, "MAX_QUESTIONS")
    assert should_stop(fit(1, 3, STRONG), V, STRONG, 0.5)[1] == "CONFIDENT"


def test_max_questions():
    assert max_questions(2) == 3 and max_questions(3) == 6 and max_questions(5) == 10 and max_questions(7) == 12


def test_extra_round_needs_one_question_and_ignores_confident(example):
    _, V = example
    post = fit(1, 3, STRONG)
    five = [SKIP] * 5
    assert should_stop(post, V, five, 0.5, question_limit=8, round_start=5)[1] != "CONFIDENT"
    # 라운드 안에서 한 문항도 안 물었으면 확신해도, 정보가 없어도 끝내지 않는다
    assert should_stop(post, V, five, 0.0, question_limit=8, round_start=5) == (False, None)
    # 한 문항 물은 뒤에는 확신도가 높아도 CONFIDENT로 끝나지 않고 LOW_INFO_GAIN·상한으로만 끝난다
    six = [SKIP] * 6
    assert should_stop(post, V, six, 0.5, question_limit=8, round_start=5) == (False, None)
    assert should_stop(post, V, six, 0.01, question_limit=8, round_start=5) == (True, "LOW_INFO_GAIN")
    assert should_stop(post, V, [SKIP] * 8, 0.5, question_limit=8, round_start=5) == (True, "MAX_QUESTIONS")
    assert should_stop(post, V, five, 0.5, question_limit=8, round_start=5, user_stopped=True) == (True, "USER_STOPPED")


def test_virtual_answers_are_not_counted(example):
    _, V = example
    post = fit(1, 3, [])
    virtual = Answer(SliderSpec(0, 1), 3, origin="free_text")
    # 가상 답변 2개를 더해도 질문 수는 늘지 않는다
    assert should_stop(post, V, [SKIP] * 5 + [virtual] * 2, 0.5, question_limit=8, round_start=5) == (False, None)
    assert should_stop(post, V, [SKIP] * 2 + [virtual] * 5, 0.01) == (False, None)  # 서버 질문 2개 < 최소 3문항


def test_limit_helpers():
    assert extra_round_limit(7) == 10
    assert raised_limit(8, 7) == 10 and raised_limit(12, 7) == 12 and raised_limit(12, 12) == 15


@pytest.mark.parametrize(("p", "low"), [(0.54, True), (0.55, False), (0.9, False)])
def test_low_confidence(p, low):
    assert is_low_confidence(p) is low
