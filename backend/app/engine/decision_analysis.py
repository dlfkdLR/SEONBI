"""점수·순위·안정성·전환 조건·민감도 (개발기획서 5.7~5.9)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

import numpy as np
from numpy.typing import NDArray

from .posterior import Posterior, best_shares

STABILITY_HIGH = 0.80
STABILITY_MEDIUM = 0.55
PERTURB_SIZE = 0.10  # 보조 지표: 각 기준 ±10%p
PERTURB_TRIALS = 500
SENSITIVITY_POINTS = 21
_CHECK_EPS = 1e-3  # 전환점 양옆에서 실제 1위를 확인할 간격

Grade = Literal["high", "medium", "low"]


@dataclass(frozen=True)
class Stability:
    top: int  # 사후 평균 가중치 기준 1위 선택지 인덱스
    p_best: float  # 안정성 점수 = 그 선택지의 1위 확률
    grade: Grade
    perturb_keep: float  # 보조 지표 (툴팁용)


@dataclass(frozen=True)
class ChangeCondition:
    criterion: int
    current: float  # 현재 가중치
    threshold: float | None  # None이면 어떻게 바꿔도 1위 유지
    direction: Literal["increase", "decrease"] | None
    new_top: int | None


@dataclass(frozen=True)
class Curve:
    t: NDArray[np.float64]  # (SENSITIVITY_POINTS,)
    scores: NDArray[np.float64]  # (SENSITIVITY_POINTS, 선택지 수)


def scores(mean: NDArray[np.float64], V: NDArray[np.float64]) -> NDArray[np.float64]:
    """U_i = Σ_k w_k · v_ik"""
    return V @ mean


def ranking(mean: NDArray[np.float64], V: NDArray[np.float64]) -> list[int]:
    """점수 높은 순 선택지 인덱스 (동점이면 입력 순서)."""
    return [int(i) for i in np.argsort(-scores(mean, V), kind="stable")]


def stability(post: Posterior, V: NDArray[np.float64], seed: int) -> Stability:
    mean = post.mean
    top = ranking(mean, V)[0]
    p_best = float((post.p @ best_shares(post.W, V))[top])
    grade: Grade = "high" if p_best >= STABILITY_HIGH else "medium" if p_best >= STABILITY_MEDIUM else "low"
    rng = np.random.default_rng(seed)
    pert = mean + rng.uniform(-PERTURB_SIZE, PERTURB_SIZE, (PERTURB_TRIALS, len(mean)))
    pert = np.maximum(pert, 1e-9)
    pert /= pert.sum(axis=1, keepdims=True)
    keep = float(np.mean(np.argmax(pert @ V.T, axis=1) == top))
    return Stability(top, p_best, grade, keep)


def change_conditions(mean: NDArray[np.float64], V: NDArray[np.float64]) -> list[ChangeCondition]:
    """기준마다 1위가 바뀌는 가장 가까운 가중치 전환점 1개.

    기준 k의 가중치를 t로 바꾸고 나머지는 원래 비율로 나누면 점수가 t의 1차식이 되므로
    t* = (R_B − R_A) / ((v_Ak − R_A) − (v_Bk − R_B)) 로 구한 뒤, 그 지점에서 B가 전체 1위인지 확인한다.
    """
    top = ranking(mean, V)[0]
    out: list[ChangeCondition] = []
    for k in range(len(mean)):
        wk = float(mean[k])
        best: dict[str, tuple[float, int]] = {}
        if wk < 1.0:
            R = (V @ mean - wk * V[:, k]) / (1 - wk)
            for j in range(V.shape[0]):
                if j == top:
                    continue
                den = (V[top, k] - R[top]) - (V[j, k] - R[j])
                if abs(den) < 1e-12:
                    continue
                t = float((R[j] - R[top]) / den)
                if not 0 < t < 1 or abs(t - wk) < 1e-12:
                    continue
                side = "increase" if t > wk else "decrease"
                probe = t + _CHECK_EPS if side == "increase" else t - _CHECK_EPS
                if _top_at(mean, V, k, min(max(probe, 0.0), 1.0)) != j:
                    continue  # 다른 선택지가 먼저 1위가 됨 → 그 선택지의 전환점이 더 가깝다
                if side not in best or abs(t - wk) < abs(best[side][0] - wk):
                    best[side] = (t, j)
        if not best:
            out.append(ChangeCondition(k, wk, None, None, None))
            continue
        side, (t, j) = min(best.items(), key=lambda kv: abs(kv[1][0] - wk))
        out.append(ChangeCondition(k, wk, t, "increase" if side == "increase" else "decrease", j))
    return out


def sensitivity(mean: NDArray[np.float64], V: NDArray[np.float64], k: int) -> Curve:
    """기준 k의 가중치를 0~1로 바꿀 때 선택지별 점수. 곡선의 교차점이 전환점이다."""
    t = np.linspace(0.0, 1.0, SENSITIVITY_POINTS)
    return Curve(t, np.array([V @ _shift(mean, k, float(x)) for x in t]))


def _shift(mean: NDArray[np.float64], k: int, t: float) -> NDArray[np.float64]:
    """w'_k = t, w'_j = w_j · (1 − t) / (1 − w_k)"""
    rest = 1 - mean[k]
    w = mean * (1 - t) / rest if rest > 0 else np.full(len(mean), (1 - t) / max(len(mean) - 1, 1))
    w[k] = t
    return w


def _top_at(mean: NDArray[np.float64], V: NDArray[np.float64], k: int, t: float) -> int:
    return int(np.argmax(V @ _shift(mean, k, t)))
