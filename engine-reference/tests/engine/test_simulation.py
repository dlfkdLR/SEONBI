"""가상 사용자로 전체 질문 흐름을 돌려 본다 (개발기획서 14장 엔진 시뮬레이션의 테스트판).

정답 가중치를 가진 가상 사용자가 우도 모델대로 답한다. 구매형(숫자형 위주)과 진로형(단계형·순위 위주)을 둘 다 돌린다.
"""

import numpy as np
from example_data import CRIT
from numpy.typing import NDArray

from app.engine.decision_analysis import ranking
from app.engine.likelihood import QuestionSpec, SliderSpec, response_logprobs, response_options
from app.engine.normalize import Criterion, Scale, freeze_scales, normalize
from app.engine.posterior import Answer, Posterior, fit
from app.engine.question_selector import best_info_gain, next_question
from app.engine.stopping import StopReason, extra_round_limit, max_questions, should_stop

Session = tuple[Posterior, list[Answer], StopReason | None, list[QuestionSpec]]


def covered(answers: list[Answer]) -> set[int]:
    return {k for a in answers if a.response is not None for k in (a.spec.a, a.spec.b)}


def run_session(
    true_w: NDArray[np.float64],
    V: NDArray[np.float64],
    crit: list[Criterion],
    scales: dict[str, Scale],
    seed: int,
    *,
    answers: list[Answer] | None = None,
    history: list[QuestionSpec] | None = None,
    round_start: int | None = None,
    limit: int | None = None,
) -> Session:
    rng = np.random.default_rng(seed)
    answers = list(answers or [])
    history = list(history or [])
    while True:
        post = fit(seed, len(true_w), answers)
        eig = best_info_gain(post, V, crit, scales=scales)
        stop, reason = should_stop(post, V, answers, eig, question_limit=limit, round_start=round_start)
        if stop:
            return post, answers, reason, history
        q = next_question(post, V, crit, history, scales=scales, covered=covered(answers))
        if q is None:
            return post, answers, None, history
        probs = np.exp(response_logprobs(q, true_w[None, :]))[0]
        response = response_options(q)[rng.choice(len(probs), p=probs / probs.sum())]
        history.append(q)
        answers.append(Answer(q, response))


def test_purchase_users_reach_the_right_top(example):
    scales, V = example
    rng = np.random.default_rng(11)
    hits, lengths = 0, []
    for i in range(30):
        true_w = rng.dirichlet(np.ones(3))
        post, answers, reason, history = run_session(true_w, V, CRIT, scales, seed=100 + i)
        assert reason in ("CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS")
        assert len(answers) <= max_questions(3)
        if len(history) >= 4:
            assert history[3].__class__.__name__ == "ScenarioSpec"  # 4번째 질문은 상황형
        hits += int(ranking(post.mean, V)[0] == ranking(true_w, V)[0])
        lengths.append(len(answers))
    assert hits >= 21  # 30명 중 70% 이상 정답 1위
    assert np.mean(lengths) < max_questions(3)


def _career():
    crit = [
        Criterion("interest", "rating", input_mode="rank"),
        Criterion("fit", "rating", levels=4),
        Criterion("outlook", "rating", levels=5),
        Criterion("commute", "numeric", "lower_better"),
        Criterion("tuition", "numeric", "lower_better"),
    ]
    options = ["cs", "biz", "design", "edu"]
    vals = {
        ("cs", "interest"): 1, ("biz", "interest"): 3, ("design", "interest"): 2, ("edu", "interest"): 4,
        ("cs", "fit"): 4, ("biz", "fit"): 2, ("design", "fit"): 3, ("edu", "fit"): 2,
        ("cs", "outlook"): 5, ("biz", "outlook"): 3, ("design", "outlook"): 3, ("edu", "outlook"): 2,
        ("cs", "commute"): 70, ("biz", "commute"): 30, ("design", "commute"): 50, ("edu", "commute"): 20,
        ("cs", "tuition"): 800, ("biz", "tuition"): 600, ("design", "tuition"): 900, ("edu", "tuition"): 500,
    }  # fmt: skip
    scales = freeze_scales(crit, vals)
    matrix, _ = normalize(vals, crit, scales, option_ids=options)
    return crit, scales, matrix.values


def test_career_users_flow_never_uses_rank_in_scenarios():
    crit, scales, V = _career()
    rng = np.random.default_rng(21)
    hits, lengths = 0, []
    for i in range(20):
        true_w = rng.dirichlet(np.ones(5))
        post, answers, _, history = run_session(true_w, V, crit, scales, seed=300 + i)
        for q in history:
            if q.__class__.__name__ == "ScenarioSpec":
                assert q.a != 0 and q.b != 0  # 기준 0(흥미)은 순위 입력이라 상황형에 안 나온다
        assert len(answers) <= max_questions(5)
        hits += int(ranking(post.mean, V)[0] == ranking(true_w, V)[0])
        lengths.append(len(answers))
    assert hits >= 12 and np.mean(lengths) <= max_questions(5)


def test_every_criterion_gets_asked_in_career_flow():
    """5.5 규칙 2: 질문이 이어지는 동안 모든 기준이 적어도 한 번은 질문에 나온다."""
    crit, scales, V = _career()
    true_w = np.array([0.1, 0.2, 0.3, 0.2, 0.2])
    _, _, _, history = run_session(true_w, V, crit, scales, seed=7)
    if len(history) >= 5:
        assert {k for q in history for k in (q.a, q.b)} == set(range(5))


def test_extra_round_asks_at_least_one_more_and_stays_in_limit(example):
    scales, V = example
    true_w = np.array([0.2, 0.3, 0.5])
    post, answers, reason, history = run_session(true_w, V, CRIT, scales, seed=5)
    start = len(answers)
    limit = extra_round_limit(start)
    post2, answers2, reason2, _ = run_session(
        true_w, V, CRIT, scales, seed=5, answers=answers, history=history, round_start=start, limit=limit
    )
    assert start + 1 <= len(answers2) <= limit
    assert reason2 in ("LOW_INFO_GAIN", "MAX_QUESTIONS")  # 추가 라운드는 CONFIDENT로 끝나지 않는다


def test_adding_a_criterion_mid_session_is_asked_and_reproducible(example):
    scales, V = example
    crit = [*CRIT, Criterion("battery", "numeric")]
    scales = {**scales, "battery": Scale(5, 15)}
    V4 = np.column_stack([V, [0.1, 0.9, 0.4]])
    true_w = np.array([0.2, 0.2, 0.3, 0.3])
    old_answers = [Answer(SliderSpec(0, 1), 1), Answer(SliderSpec(1, 2), -2), Answer(SliderSpec(0, 2), -1)]
    post, answers, _, history = run_session(
        true_w, V4, crit, scales, seed=9, answers=old_answers,
        history=[a.spec for a in old_answers], round_start=3, limit=extra_round_limit(3),
    )  # fmt: skip
    assert any(3 in (q.a, q.b) for q in history[3:])  # 새 기준이 먼저 질문된다
    post_again = fit(9, 4, answers)
    np.testing.assert_array_equal(post.W, post_again.W)  # 같은 시드·같은 답변 → 같은 결과
