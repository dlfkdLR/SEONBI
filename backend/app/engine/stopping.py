"""질문 종료 판단 (개발기획서 5.10)."""

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

StopReason = Literal["CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS", "USER_STOPPED"]


def max_questions(K: int) -> int:
    return min(MAX_QUESTIONS_CAP, max(MAX_QUESTIONS_FLOOR, 2 * K))


def should_stop(
    post: Posterior,
    V: NDArray[np.float64],
    history: Sequence[Answer],
    best_eig: float,
    *,
    user_stopped: bool = False,
) -> tuple[bool, StopReason | None]:
    """history는 지금까지의 답변("잘 모르겠어요" 포함). best_eig는 question_selector.best_info_gain 값."""
    if user_stopped:
        return True, "USER_STOPPED"
    answered = len(history)
    top = float(np.max(post.p @ best_shares(post.W, V)))
    if answered >= MIN_QUESTIONS and top >= CONFIDENT_P_BEST:
        return True, "CONFIDENT"
    if answered >= MIN_QUESTIONS and best_eig < LOW_INFO_GAIN_BITS:
        return True, "LOW_INFO_GAIN"
    if answered >= max_questions(V.shape[1]):
        return True, "MAX_QUESTIONS"
    return False, None


def is_low_confidence(top_p_best: float) -> bool:
    return top_p_best < LOW_CONFIDENCE_P_BEST
