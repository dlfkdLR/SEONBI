"""후보 생성, 상황형 구성·반올림, EIG 계산, 선택 규칙 (개발기획서 5.5)."""

from __future__ import annotations

import math
from collections.abc import Collection, Mapping, Sequence
from dataclasses import dataclass
from itertools import combinations

import numpy as np
from numpy.typing import NDArray

from .likelihood import CriteriaABSpec, QuestionSpec, ScenarioSpec, SliderSpec, response_logprobs
from .normalize import DEFAULT_LEVELS, Criterion, Scale
from .posterior import Posterior, best_shares, entropy_bits

INITIAL_SLIDERS_MAX = 3  # 초기 슬라이더 사슬 최대 개수
SCENARIO_DELTAS = (0.3, 0.5, 0.7)  # 상황형 Δa 변형 (정규화 범위 대비)
SCENARIO_DELTA_CAP = 0.8  # Δb 상한
SCENARIO_QUOTA_FROM = 4  # 이 번호부터 짝수 번째 질문은 상황형
SCENARIOS_PER_PAIR = 2  # 숨은 선호 판단에 필요한 쌍당 상황형 수
MAX_SAME_TYPE_RUN = 2  # 같은 유형 3연속 금지


@dataclass(frozen=True)
class Candidate:
    spec: QuestionSpec
    eig: float


def next_question(
    post: Posterior,
    V: NDArray[np.float64],
    criteria: Sequence[Criterion],
    history: Sequence[QuestionSpec],
    *,
    scales: Mapping[str, Scale],
    covered: Collection[int] | None = None,
) -> QuestionSpec | None:
    """다음 질문. 물어볼 기준 쌍이 없으면 None. 선택 규칙은 5.5의 4번 항목.

    history는 지금까지 낸 **서버 질문**(답변·건너뛰기 포함)을 순서대로 담는다. 자유 입력으로 만든 가상 질문은
    넣지 않는다 — 질문 번호, 같은 유형 연속, 상황형 할당 차례 계산에서 빠져야 하기 때문이다.
    covered는 유효 답변(가상 답변 포함, "잘 모르겠어요" 제외)에 한 번이라도 나온 기준 번호다. 주지 않으면
    history의 질문에 나온 기준으로 대신한다.
    """
    active = active_criteria(V)
    if len(active) < 2:
        return None
    n = len(history) + 1
    if n <= min(len(active) - 1, INITIAL_SLIDERS_MAX):
        return SliderSpec(active[n - 1], active[n])  # 기준을 사슬처럼 연결

    candidates = score_candidates(post, V, criteria, history, scales=scales)
    if not candidates:
        return None
    seen = set(covered) if covered is not None else {k for q in history for k in (q.a, q.b)}
    fresh = {k for k in active if k not in seen}
    # 규칙 2: 아직 답변에 나온 적 없는 기준이 있으면 그 기준이 들어간 후보 안에서 고른다
    focused = [c for c in candidates if c.spec.a in fresh or c.spec.b in fresh] if fresh else candidates
    if n >= SCENARIO_QUOTA_FROM and n % 2 == 0:
        scenario = _pick_scenario(focused, history)  # 규칙 4·5: 후보가 없으면 규칙 2로 넘어간다
        if scenario is not None:
            return scenario.spec
    allowed = [c for c in focused if _allowed(c.spec, history, len(active))]
    pool = allowed or focused
    return max(pool, key=lambda c: c.eig).spec  # 동점이면 먼저 생성된 후보


def best_info_gain(
    post: Posterior,
    V: NDArray[np.float64],
    criteria: Sequence[Criterion],
    *,
    scales: Mapping[str, Scale],
) -> float:
    """후보 중 최고 EIG. 종료 판단(LOW_INFO_GAIN)에 쓴다."""
    candidates = score_candidates(post, V, criteria, (), scales=scales)
    return max((c.eig for c in candidates), default=0.0)


def score_candidates(
    post: Posterior,
    V: NDArray[np.float64],
    criteria: Sequence[Criterion],
    history: Sequence[QuestionSpec],
    *,
    scales: Mapping[str, Scale],
) -> list[Candidate]:
    shares = best_shares(post.W, V)
    h_now = entropy_bits(post.p @ shares)
    mean = post.mean
    out: list[Candidate] = []
    for a, b in combinations(active_criteria(V), 2):
        specs: list[QuestionSpec] = [SliderSpec(a, b), CriteriaABSpec(a, b)]
        specs += build_scenarios(mean, a, b, criteria, scales)
        out += [Candidate(s, _eig(post, shares, h_now, s)) for s in specs]
    return out


def active_criteria(V: NDArray[np.float64]) -> list[int]:
    """선택지 사이에 값 차이가 있는 기준만. 값이 모두 같으면 순위에 영향이 없어 묻지 않는다."""
    return [k for k in range(V.shape[1]) if np.ptp(V[:, k]) > 0]


def build_scenarios(
    mean: NDArray[np.float64],
    a: int,
    b: int,
    criteria: Sequence[Criterion],
    scales: Mapping[str, Scale],
) -> list[ScenarioSpec]:
    """응답이 반반으로 갈리도록 Δb = Δa · ŵ_a / ŵ_b 로 맞춘 상황형 후보. 순위 입력 기준은 만들지 않는다."""
    ca, cb = criteria[a], criteria[b]
    if _is_rank(ca) or _is_rank(cb):
        return []
    out: list[ScenarioSpec] = []
    for da in SCENARIO_DELTAS:
        db = da * mean[a] / max(mean[b], 1e-12)
        if db > SCENARIO_DELTA_CAP:
            da, db = da * SCENARIO_DELTA_CAP / db, SCENARIO_DELTA_CAP
        na, raw_a = _round_delta(ca, scales.get(ca.id), da)
        nb, raw_b = _round_delta(cb, scales.get(cb.id), db)
        delta = [0.0] * len(criteria)
        delta[a], delta[b] = na, -nb
        spec = ScenarioSpec(a, b, tuple(delta), raw_a, raw_b)
        if spec not in out:
            out.append(spec)
    return out


def nice_step(span: float) -> float:
    """span/10 이하에서 가장 큰 1·2·5×10ⁿ. 예: 39 → 2, 0.26 → 0.02."""
    raw = span / 10
    e = 10.0 ** math.floor(math.log10(raw))
    for m in (5, 2, 1):
        if m * e <= raw * (1 + 1e-9):
            return m * e
    return e


def _round_delta(c: Criterion, scale: Scale | None, d: float) -> tuple[float, float]:
    """정규화 Δ를 화면에 보여줄 값으로 반올림하고, 반올림한 값에서 정규화 Δ를 다시 계산한다."""
    if c.type == "boolean":
        return 1.0, 1.0
    if c.type == "rating":
        unit = 1 / ((c.levels or DEFAULT_LEVELS) - 1)
        steps = max(1, round(d / unit))
        return min(steps * unit, 1.0), float(steps)
    if scale is None or scale.span == 0:
        raise ValueError(f"숫자형 기준 {c.id}의 척도가 없다")
    step = nice_step(scale.span)
    raw = max(step, round(d * scale.span / step) * step)
    raw = float(f"{raw:.10g}")  # 부동소수점 꼬리 제거 (0.30000000000000004 → 0.3)
    return min(raw / scale.span, 1.0), raw


def _is_rank(c: Criterion) -> bool:
    return c.type == "rating" and c.input_mode == "rank"


def _eig(post: Posterior, shares: NDArray[np.float64], h_now: float, spec: QuestionSpec) -> float:
    """EIG = H(현재) − Σ_r P(r) · H(답변 r 이후)"""
    L = np.exp(response_logprobs(spec, post.W))  # (N, R)
    pr = post.p @ L  # (R,)
    joint = (post.p[:, None] * L).T @ shares  # (R, 선택지 수), 행 합 = P(r)
    h_after = 0.0
    for r in range(len(pr)):
        if pr[r] > 1e-12:
            h_after += pr[r] * entropy_bits(joint[r] / pr[r])
    return h_now - h_after


def _pair(spec: QuestionSpec) -> tuple[int, int]:
    return (min(spec.a, spec.b), max(spec.a, spec.b))


def _allowed(spec: QuestionSpec, history: Sequence[QuestionSpec], n_active: int) -> bool:
    if history and n_active > 2 and _pair(history[-1]) == _pair(spec):
        return False  # 같은 기준 쌍 연속 금지 (기준이 2개면 적용하지 않음)
    recent = history[-MAX_SAME_TYPE_RUN:]
    return not (len(recent) == MAX_SAME_TYPE_RUN and all(type(q) is type(spec) for q in recent))


def _pick_scenario(candidates: Sequence[Candidate], history: Sequence[QuestionSpec]) -> Candidate | None:
    """상황형 할당 차례: 상황형이 1개뿐인 쌍을 먼저 채워 쌍당 2개가 되게 하고, 그다음 EIG 순."""
    scenarios = [c for c in candidates if isinstance(c.spec, ScenarioSpec)]
    if not scenarios:
        return None
    counts: dict[tuple[int, int], int] = {}
    for q in history:
        if isinstance(q, ScenarioSpec):
            counts[_pair(q)] = counts.get(_pair(q), 0) + 1
    half_filled = [c for c in scenarios if 0 < counts.get(_pair(c.spec), 0) < SCENARIOS_PER_PAIR]
    return max(half_filled or scenarios, key=lambda c: c.eig)
