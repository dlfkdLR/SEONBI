"""입자 샘플링·재가중·재샘플링, 요약 통계 (개발기획서 5.2, 5.4).

같은 seed·같은 답변 목록이면 항상 같은 결과가 나온다. 그래서 입자는 저장하지 않고 매번 다시 계산한다.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal

import numpy as np
from numpy.typing import NDArray
from scipy.special import gammaln

from .likelihood import QuestionSpec, Response, log_likelihood

N_PARTICLES = 4000
PRIOR_ALPHA = 1.0  # Dirichlet(1, …, 1) — 아무것도 모르는 상태
ESS_RATIO = 0.1  # ESS < N × 0.1 이면 재샘플링
MH_KAPPA = 50.0  # 제안 분포 Dirichlet(κ·w + 1)의 집중도 — 수락률 40~55% (검증 시나리오 기준)
MH_STEPS = 3
INTERVAL = (0.10, 0.90)  # 기준별 신뢰 구간 분위수
TIE_TOL = 1e-12


AnswerOrigin = Literal["engine", "free_text"]


@dataclass(frozen=True)
class Answer:
    """유효한 답변 하나 (is_valid=false 답변은 넘기지 않는다).

    origin이 free_text인 답변은 자유 입력(29번)으로 만든 가상 슬라이더 답변이다. 추정에는 쓰지만
    질문 수·질문 순서 규칙에는 세지 않는다(4.2, 7.3 29번).
    """

    spec: QuestionSpec
    response: Response
    origin: AnswerOrigin = "engine"


@dataclass(frozen=True)
class Posterior:
    W: NDArray[np.float64]  # (N, K) 가중치 후보
    p: NDArray[np.float64]  # (N,) 정규화된 비중

    @property
    def ess(self) -> float:
        return float(1.0 / np.sum(self.p**2))

    @property
    def mean(self) -> NDArray[np.float64]:
        return self.p @ self.W


@dataclass(frozen=True)
class Summary:
    mean: NDArray[np.float64]  # (K,) 가중치 평균
    intervals: NDArray[np.float64]  # (K, 2) 10~90% 구간
    p_best: NDArray[np.float64]  # (선택지 수,) 1위 확률
    entropy: float  # 결정 엔트로피 H (bit)


def prior_particles(seed: int, K: int, n: int = N_PARTICLES) -> NDArray[np.float64]:
    """사전분포에서 뽑은 입자. fit의 첫 단계와 같은 난수를 쓴다."""
    return np.random.default_rng(seed).dirichlet(np.full(K, PRIOR_ALPHA), size=n)


def fit(seed: int, K: int, answers: Sequence[Answer], n: int = N_PARTICLES) -> Posterior:
    """답변을 seq 순서대로 반영해 사후분포를 만든다."""
    rng = np.random.default_rng(seed)
    W = rng.dirichlet(np.full(K, PRIOR_ALPHA), size=n)
    logw = np.zeros(n)
    for i, ans in enumerate(answers):
        logw += log_likelihood(ans.spec, ans.response, W)
        p = _normalize(logw)
        if 1.0 / np.sum(p**2) < n * ESS_RATIO:
            W = _resample_move(W, p, answers[: i + 1], rng)
            logw = np.zeros(n)
    return Posterior(W, _normalize(logw))


def reweight(W: NDArray[np.float64], answers: Sequence[Answer]) -> NDArray[np.float64]:
    """재샘플링 없이 주어진 입자에 답변 묶음만 반영한 비중. 숨은 선호처럼 답변 일부만 볼 때 쓴다."""
    logw = np.zeros(len(W))
    for ans in answers:
        logw += log_likelihood(ans.spec, ans.response, W)
    return _normalize(logw)


def best_shares(W: NDArray[np.float64], V: NDArray[np.float64]) -> NDArray[np.float64]:
    """입자마다 1위 선택지 표시 (N, 선택지 수). 동점이면 1을 나눠 준다."""
    U = W @ V.T
    is_best = U.max(axis=1, keepdims=True) - TIE_TOL <= U
    return is_best / is_best.sum(axis=1, keepdims=True)


def entropy_bits(p_best: NDArray[np.float64]) -> float:
    q = p_best[p_best > 0]
    return float(-np.sum(q * np.log2(q)))


def summary(post: Posterior, V: NDArray[np.float64]) -> Summary:
    p_best = post.p @ best_shares(post.W, V)
    intervals = np.array([_weighted_quantiles(post.W[:, k], post.p, INTERVAL) for k in range(post.W.shape[1])])
    return Summary(post.mean, intervals, p_best, entropy_bits(p_best))


def _normalize(logw: NDArray[np.float64]) -> NDArray[np.float64]:
    p: NDArray[np.float64] = np.exp(logw - logw.max())
    return p / p.sum()


def _weighted_quantiles(x: NDArray[np.float64], p: NDArray[np.float64], qs: tuple[float, ...]) -> list[float]:
    order = np.argsort(x)
    cum = np.cumsum(p[order])
    idx = np.minimum(np.searchsorted(cum, qs), len(x) - 1)
    return [float(v) for v in x[order][idx]]


def _resample_move(
    W: NDArray[np.float64], p: NDArray[np.float64], answers: Sequence[Answer], rng: np.random.Generator
) -> NDArray[np.float64]:
    """systematic resampling 후 Metropolis-Hastings로 입자를 흩뜨린다."""
    n = len(W)
    positions = (rng.random() + np.arange(n)) / n
    idx = np.minimum(np.searchsorted(np.cumsum(p), positions), n - 1)
    W = W[idx]
    loglik = _total_loglik(W, answers)
    for _ in range(MH_STEPS):
        alpha_fwd = MH_KAPPA * W + 1.0
        proposal = np.maximum(rng.standard_gamma(alpha_fwd), 1e-12)  # 행마다 Dirichlet(alpha_fwd)
        proposal /= proposal.sum(axis=1, keepdims=True)
        alpha_back = MH_KAPPA * proposal + 1.0
        loglik_new = _total_loglik(proposal, answers)
        # 사전분포는 Dirichlet(1)이라 상수 → 우도 비 × 제안 분포 비대칭 보정
        log_ratio = loglik_new - loglik + _dirichlet_logpdf(W, alpha_back) - _dirichlet_logpdf(proposal, alpha_fwd)
        accept = np.log(rng.random(n)) < log_ratio
        W = np.where(accept[:, None], proposal, W)
        loglik = np.where(accept, loglik_new, loglik)
    return W


def _total_loglik(W: NDArray[np.float64], answers: Sequence[Answer]) -> NDArray[np.float64]:
    total = np.zeros(len(W))
    for ans in answers:
        total += log_likelihood(ans.spec, ans.response, W)
    return total


def _dirichlet_logpdf(x: NDArray[np.float64], alpha: NDArray[np.float64]) -> NDArray[np.float64]:
    out: NDArray[np.float64] = (
        gammaln(alpha.sum(axis=1)) - gammaln(alpha).sum(axis=1) + ((alpha - 1) * np.log(x)).sum(axis=1)
    )
    return out
