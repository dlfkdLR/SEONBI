"""질문 종료 판단과 질문 수 상한 (개발기획서 5.10, 4.2 추가 질문 라운드)."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Literal

import numpy as np
from numpy.typing import NDArray

from .posterior import Answer, Posterior, best_shares

MIN_QUESTIONS = 3
CONFIDENT_P_BEST = 0.85
LOW_INFO_GAIN_BITS = 0.03
MAX_QUESTIONS_FLOOR = 6
MAX_QUESTIONS_CAP = 12
LOW_CONFIDENCE_P_BEST = 0.55  # 이보다 낮게 끝나면 결과 화면에 배너
EXTRA_ROUND_QUESTIONS = 3  # 추가 질문 라운드에서 더 묻는 최대 문항 수 (4.2)

StopReason = Literal["CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS", "USER_STOPPED"]


def max_questions(K: int) -> int:
    """첫 라운드의 질문 수 상한. 5.10: max(6, 2K), 상한 12."""
    return min(MAX_QUESTIONS_CAP, max(MAX_QUESTIONS_FLOOR, 2 * K))


def extra_round_limit(answered: int) -> int:
    """추가 질문 라운드를 시작할 때의 상한 = 현재 답변 수 + 3 (4.2). decisions.question_limit에 저장한다."""
    return answered + EXTRA_ROUND_QUESTIONS


def raised_limit(current_limit: int, answered: int) -> int:
    """QUESTIONING 중 기준을 추가했을 때의 상한 = max(현재 상한, 현재 답변 수 + 3) (4.2)."""
    return max(current_limit, answered + EXTRA_ROUND_QUESTIONS)


def counted_answers(history: Sequence[Answer]) -> int:
    """질문 수로 세는 답변 수. 자유 입력으로 만든 가상 답변(origin=free_text)은 뺀다."""
    return sum(1 for a in history if a.origin == "engine")


def should_stop(
    post: Posterior,
    V: NDArray[np.float64],
    history: Sequence[Answer],
    best_eig: float,
    *,
    user_stopped: bool = False,
    question_limit: int | None = None,
    round_start: int | None = None,
) -> tuple[bool, StopReason | None]:
    """질문을 끝낼지 판단한다.

    history는 지금까지의 답변("잘 모르겠어요" 포함, 가상 답변은 세지 않음). best_eig는
    question_selector.best_info_gain 값.
    question_limit이 없으면 첫 라운드 상한 max_questions(K)를 쓴다.
    round_start는 추가 질문 라운드를 시작할 때의 답변 수다. 값이 있으면(추가 라운드):
    라운드 안에서 최소 1문항을 묻기 전에는 끝내지 않고(USER_STOPPED 제외), CONFIDENT로는 끝내지 않는다.
    """
    if user_stopped:
        return True, "USER_STOPPED"
    answered = counted_answers(history)
    extra_round = round_start is not None
    if round_start is not None and answered <= round_start:
        return False, None
    limit = question_limit if question_limit is not None else max_questions(V.shape[1])
    top = float(np.max(post.p @ best_shares(post.W, V)))
    if not extra_round and answered >= MIN_QUESTIONS and top >= CONFIDENT_P_BEST:
        return True, "CONFIDENT"
    if answered >= MIN_QUESTIONS and best_eig < LOW_INFO_GAIN_BITS:
        return True, "LOW_INFO_GAIN"
    if answered >= limit:
        return True, "MAX_QUESTIONS"
    return False, None


def is_low_confidence(top_p_best: float) -> bool:
    return top_p_best < LOW_CONFIDENCE_P_BEST
