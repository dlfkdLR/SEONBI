"""질문 유형별 로그 우도 (개발기획서 5.3).

기준은 호출 시점 기준 목록의 인덱스로 가리킨다. DB(questions.spec)에는 기준 ID로 저장하고
서비스 계층에서 인덱스로 바꿔 넘긴다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

import numpy as np
from numpy.typing import NDArray

BT_LAMBDA = 1.0  # Bradley-Terry 노이즈 계수 λ
SLIDER_C = 0.3  # 슬라이더 한 칸이 뜻하는 log(w_a/w_b)
SLIDER_SIGMA = 0.6
SLIDER_MAX = 5  # 응답 범위 −5..+5
SCENARIO_BETA = 8.0  # 상황형 로지스틱 β

SLIDER_VALUES = np.arange(-SLIDER_MAX, SLIDER_MAX + 1)
_EPS = 1e-12

Choice = Literal["A", "B"]
Response = int | Choice | None  # None = "잘 모르겠어요"


@dataclass(frozen=True)
class SliderSpec:
    """양방향 슬라이더. s > 0이면 왼쪽(a)이 더 중요."""

    a: int
    b: int


@dataclass(frozen=True)
class CriteriaABSpec:
    """A = 기준 a, B = 기준 b 중 더 중요한 쪽 고르기."""

    a: int
    b: int


@dataclass(frozen=True)
class ScenarioSpec:
    """가상 선택지 A/B. A는 기준 a가 좋고 b가 나쁘며, B는 반대.

    delta는 정규화 값 차이 v_A − v_B (길이 K). 화면에 보여준 반올림 값에서 다시 계산한 값이다.
    raw_a, raw_b는 화면용 차이(숫자형은 원래 단위, 단계형은 단계 수, 예/아니요는 1).
    """

    a: int
    b: int
    delta: tuple[float, ...]
    raw_a: float
    raw_b: float


QuestionSpec = SliderSpec | CriteriaABSpec | ScenarioSpec


def preference_kind(spec: QuestionSpec) -> Literal["stated", "revealed"]:
    """슬라이더·기준 A/B는 진술 선호, 상황형은 드러난 선호 (5.6)."""
    return "revealed" if isinstance(spec, ScenarioSpec) else "stated"


def response_options(spec: QuestionSpec) -> list[Response]:
    if isinstance(spec, SliderSpec):
        return [int(s) for s in SLIDER_VALUES]
    return ["A", "B"]


def response_logprobs(spec: QuestionSpec, W: NDArray[np.float64]) -> NDArray[np.float64]:
    """입자마다 가능한 모든 응답의 로그 확률. (N, 응답 수), 열 순서는 response_options와 같다."""
    if isinstance(spec, SliderSpec):
        return _slider_logprobs(W, spec.a, spec.b)
    return np.stack(_log_prob_ab(spec, W), axis=1)


def log_likelihood(spec: QuestionSpec, response: Response, W: NDArray[np.float64]) -> NDArray[np.float64]:
    """입자마다 log P(응답 | w). (N,) "잘 모르겠어요"는 0 (정보 없음)."""
    if response is None:
        return np.zeros(len(W))
    if isinstance(spec, SliderSpec):
        if not isinstance(response, int) or abs(response) > SLIDER_MAX:
            raise ValueError(f"슬라이더 응답은 -{SLIDER_MAX}..{SLIDER_MAX} 정수: {response!r}")
        return _slider_logprobs(W, spec.a, spec.b)[:, response + SLIDER_MAX]
    if response not in ("A", "B"):
        raise ValueError(f"A/B 질문 응답은 'A' 또는 'B': {response!r}")
    log_a, log_b = _log_prob_ab(spec, W)
    return log_a if response == "A" else log_b


def _slider_logprobs(W: NDArray[np.float64], a: int, b: int) -> NDArray[np.float64]:
    # P(s | w) ∝ exp(−(log(w_a/w_b) − c·s)² / 2σ²), 11개 응답에 대해 합이 1
    lr = np.log(np.maximum(W[:, a], _EPS) / np.maximum(W[:, b], _EPS))[:, None]
    logits = -((lr - SLIDER_C * SLIDER_VALUES[None, :]) ** 2) / (2 * SLIDER_SIGMA**2)
    out: NDArray[np.float64] = logits - np.logaddexp.reduce(logits, axis=1, keepdims=True)
    return out


def _log_prob_ab(
    spec: CriteriaABSpec | ScenarioSpec, W: NDArray[np.float64]
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """(log P(A), log P(B))"""
    if isinstance(spec, CriteriaABSpec):
        # Bradley-Terry: P(A) = w_a^λ / (w_a^λ + w_b^λ)
        la = BT_LAMBDA * np.log(np.maximum(W[:, spec.a], _EPS))
        lb = BT_LAMBDA * np.log(np.maximum(W[:, spec.b], _EPS))
        norm = np.logaddexp(la, lb)
        return la - norm, lb - norm
    # 베이지안 로지스틱: P(A) = σ(β · Σ_k w_k (v_Ak − v_Bk))
    z = SCENARIO_BETA * (W @ np.asarray(spec.delta))
    return -np.logaddexp(0.0, -z), -np.logaddexp(0.0, z)
