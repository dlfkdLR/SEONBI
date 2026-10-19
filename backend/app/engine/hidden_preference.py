"""진술 선호와 드러난 선호 비교 (개발기획서 5.6)."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from .likelihood import ScenarioSpec, preference_kind
from .posterior import Answer, reweight

STATED_THRESHOLD = 0.75  # 진술 쪽 P(w_a > w_b) ≥ 이 값
REVEALED_THRESHOLD = 0.25  # 드러난 쪽 P(w_a > w_b) ≤ 이 값
MIN_SCENARIOS_PER_PAIR = 2


@dataclass(frozen=True)
class Mismatch:
    stated_over: int  # 말로는 더 중요하다고 한 기준
    revealed_over: int  # 실제 선택에서 더 중시한 기준
    stated_p: float  # 진술 쪽 P(w_stated_over > w_revealed_over)
    revealed_p: float  # 드러난 쪽 같은 확률
    n_scenarios: int


def detect(W: NDArray[np.float64], answers: Sequence[Answer]) -> list[Mismatch]:
    """W는 사전분포 입자(posterior.prior_particles). 같은 입자에 답변 묶음만 달리해 두 번 재가중한다.

    상황형 답변이 기준 쌍당 2개 이상인 쌍만 판단한다. 순위 입력 기준은 상황형이 없으므로 자연히 빠진다.
    """
    answered = [a for a in answers if a.response is not None]
    stated = [a for a in answered if preference_kind(a.spec) == "stated"]
    revealed = [a for a in answered if preference_kind(a.spec) == "revealed"]
    if not stated or not revealed:
        return []
    p_stated = reweight(W, stated)
    p_revealed = reweight(W, revealed)

    per_pair: dict[tuple[int, int], int] = {}
    for a in revealed:
        if isinstance(a.spec, ScenarioSpec):
            pair = (min(a.spec.a, a.spec.b), max(a.spec.a, a.spec.b))
            per_pair[pair] = per_pair.get(pair, 0) + 1

    out: list[Mismatch] = []
    for (i, j), n in sorted(per_pair.items()):
        if n < MIN_SCENARIOS_PER_PAIR:
            continue
        i_over_j = W[:, i] > W[:, j]
        s = float(p_stated[i_over_j].sum())
        r = float(p_revealed[i_over_j].sum())
        if s >= STATED_THRESHOLD and r <= REVEALED_THRESHOLD:
            out.append(Mismatch(i, j, s, r, n))
        elif 1 - s >= STATED_THRESHOLD and 1 - r <= REVEALED_THRESHOLD:
            out.append(Mismatch(j, i, 1 - s, 1 - r, n))
    return out
