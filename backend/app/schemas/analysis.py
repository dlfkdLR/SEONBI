"""분석 API 스키마: 39~46번 (개발기획서 7.3 "분석", 4.2, 5.6~5.9).

가정:
- 42번 `rawValue`는 숫자(float)로 읽었다(단계형·예/아니요의 원시값 표기는 명세에 없다).
- 43번 `points`의 21개 제한은 걸지 않는다. `crossovers[].from`은 파이썬 예약어라 `from_`(별칭 `from`).
- 45번 가중치 항목은 임계값이 있는 형태와 `threshold: null`만 있는 형태(키 4개)를 별도 모델로 둔다.
- 46번 `normalizedWeights` 항목은 요청과 같은 `{criterionId, weight}`로 읽었고, `rankings` 항목은 예시대로
  `{rank, optionId, score}`(이름·확률 없음)다. 요청의 가중치 값 범위는 명세에 없어 제한하지 않는다.
- 시각(`computedAt`)은 datetime(ISO 8601 UTC)이다.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import Field

from app.schemas.camel import CamelModel, StrictCamelModel
from app.schemas.questions import Interval


# --- 40 ----------------------------------------------------------------------------------------
class WeightItem(CamelModel):
    criterion_id: str
    name: str
    mean: float
    interval: Interval
    stated_rank: int | None
    revealed_rank: int | None


class WeightsResponse(CamelModel):
    """40번 `GET /weights`."""

    items: list[WeightItem]
    interval_level: float


# --- 41 ----------------------------------------------------------------------------------------
class RankingItem(CamelModel):
    rank: int
    option_id: str
    name: str
    score: float
    top_probability: float


class RankingsResponse(CamelModel):
    """41번 `GET /rankings`."""

    items: list[RankingItem]


# --- 42 ----------------------------------------------------------------------------------------
class ScoreCell(CamelModel):
    criterion_id: str
    raw_value: float
    normalized: float
    contribution: float


class ScoreItem(CamelModel):
    option_id: str
    cells: list[ScoreCell]


class ScoresResponse(CamelModel):
    """42번 `GET /scores`."""

    criteria: list[str]
    items: list[ScoreItem]


# --- 43 ----------------------------------------------------------------------------------------
class SensitivityPoint(CamelModel):
    weight: float
    scores: dict[str, float]  # optionId -> 점수


class Crossover(CamelModel):
    weight: float
    from_: str = Field(alias="from")
    to: str


class SensitivityItem(CamelModel):
    criterion_id: str
    current_weight: float
    points: list[SensitivityPoint]
    crossovers: list[Crossover]


class SensitivityResponse(CamelModel):
    """43번 `GET /sensitivity`."""

    items: list[SensitivityItem]


# --- 44 ----------------------------------------------------------------------------------------
class Competitor(CamelModel):
    option_id: str
    probability: float


class StabilityResponse(CamelModel):
    """44번 `GET /stability`."""

    option_id: str
    score: float
    level: Literal["HIGH", "MEDIUM", "LOW"]
    local_perturbation_keep_rate: float
    competitors: list[Competitor]
    text: str


# --- 45 ----------------------------------------------------------------------------------------
class WeightChangeCondition(CamelModel):
    type: Literal["weight"]
    criterion_id: str
    current_weight: float
    threshold: float
    direction: Literal["increase", "decrease"]
    new_top: str
    text: str


class WeightNoThresholdCondition(CamelModel):
    """어떻게 바뀌어도 1위가 유지되는 기준: threshold가 null이고 다른 키는 없다."""

    type: Literal["weight"]
    criterion_id: str
    threshold: None
    text: str


class ValueChangeCondition(CamelModel):
    """type=value (P1)."""

    type: Literal["value"]
    option_id: str
    criterion_id: str
    current_value: float
    threshold: float
    text: str


# 키 구성이 겹치지 않는 모델들이라 smart union으로 충분하다 (threshold 유무·type·currentWeight 유무로 갈린다)
ChangeConditionItem = WeightChangeCondition | WeightNoThresholdCondition | ValueChangeCondition


class ChangeConditionsResponse(CamelModel):
    """45번 `GET /change-conditions`."""

    current_top: str
    items: list[ChangeConditionItem]


# --- 39 ----------------------------------------------------------------------------------------
class Recommendation(CamelModel):
    option_id: str
    name: str
    top_probability: float


class HiddenPreference(CamelModel):
    criterion_a: str
    criterion_b: str
    stated: Literal["A_MORE_IMPORTANT", "B_MORE_IMPORTANT"]
    revealed: Literal["A_MORE_IMPORTANT", "B_MORE_IMPORTANT"]
    strength: float
    text: str


class ExcludedOption(CamelModel):
    option_id: str
    reasons: list[str]


class OptionSummary(CamelModel):
    option_id: str
    pros: list[str]
    cons: list[str]


class Explanation(CamelModel):
    summary: str
    reasons: list[str]
    caveats: list[str]
    source: Literal["ai", "template"]


class AnalysisResponse(CamelModel):
    """39번 `GET /analysis` (종합). QUESTIONING 중에는 provisional=true."""

    provisional: bool
    recommendation: Recommendation
    rankings: list[RankingItem]
    weights: list[WeightItem]
    stability: StabilityResponse
    change_conditions: list[ChangeConditionItem]
    hidden_preferences: list[HiddenPreference]
    excluded_options: list[ExcludedOption]
    option_summaries: list[OptionSummary]
    explanation: Explanation
    low_confidence: bool
    computed_at: datetime


# --- 46 ----------------------------------------------------------------------------------------
class SimulationWeight(StrictCamelModel):
    criterion_id: str
    weight: float


class SimulationRequest(StrictCamelModel):
    """46번 요청. 합이 1이 아니어도 서버가 정규화한다."""

    weights: list[SimulationWeight]


class SimulationRankingItem(CamelModel):
    rank: int
    option_id: str
    score: float


class SimulationResponse(CamelModel):
    """46번 200 — 저장하지 않는 순수 계산."""

    normalized_weights: list[SimulationWeight]
    rankings: list[SimulationRankingItem]
    changed_from_current: bool
