"""질문 API 스키마: 질문 객체(공통)와 31~36번 (개발기획서 7.3 "질문·답변", 4.2).

가정(명세에 없거나 모호한 부분):
- `payload`는 `type`에 맞는 모델이어야 한다. `numeric`/`free_text`의 payload 형식은 명세에 없어 자유 dict로 둔다.
- 종료 이유 코드는 5.10의 4종(CONFIDENT, LOW_INFO_GAIN, MAX_QUESTIONS, USER_STOPPED)으로 제한했다.
- 33번 `metrics`의 값은 예시에 null이 없어 모두 필수·non-null로 읽었다.
- `intervals`는 길이 2의 리스트로 둔다(JSON 배열과 그대로 왕복하도록 튜플을 쓰지 않았다).
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import Field, SerializerFunctionWrapHandler, model_serializer, model_validator

from app.schemas.camel import CamelModel, StrictCamelModel

QuestionType = Literal["slider_pair", "ab_criteria", "ab_scenario", "numeric", "free_text"]
StopReason = Literal["CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS", "USER_STOPPED"]
IntRange = Annotated[list[int], Field(min_length=2, max_length=2)]  # [최소, 최대]
Interval = Annotated[list[float], Field(min_length=2, max_length=2)]  # [하한, 상한]


# --- payload (7.3 질문 객체) -----------------------------------------------------------------------
class SliderSide(StrictCamelModel):
    criterion_id: str
    label: str
    range: str


class SliderPairPayload(StrictCamelModel):
    """slider_pair: s > 0이면 왼쪽이 더 중요 (5.3)."""

    left: SliderSide
    right: SliderSide
    min: int
    max: int


class CriteriaSide(StrictCamelModel):
    criterion_id: str
    label: str


class AbCriteriaPayload(StrictCamelModel):
    option_a: CriteriaSide
    option_b: CriteriaSide


class ScenarioDelta(StrictCamelModel):
    criterion_id: str
    display: str


class ScenarioOption(StrictCamelModel):
    label: str
    description: str
    deltas: list[ScenarioDelta]


class AbScenarioPayload(StrictCamelModel):
    option_a: ScenarioOption
    option_b: ScenarioOption


QuestionPayload = SliderPairPayload | AbCriteriaPayload | AbScenarioPayload | dict[str, Any]

_PAYLOAD_MODELS: dict[str, type[StrictCamelModel]] = {
    "slider_pair": SliderPairPayload,
    "ab_criteria": AbCriteriaPayload,
    "ab_scenario": AbScenarioPayload,
}


class QuestionObject(CamelModel):
    """질문 객체 (7.3 공통)."""

    id: str
    seq: int
    type: QuestionType
    criteria: list[str]
    title: str
    payload: QuestionPayload
    allow_free_text: bool
    allow_skip: bool

    @model_validator(mode="before")
    @classmethod
    def _payload_by_type(cls, data: Any) -> Any:
        """type에 맞는 payload 모델로 검증한다 (키 구성이 겹치지 않게 하는 판별 대신 type을 본다)."""
        if not isinstance(data, dict):
            return data
        model = _PAYLOAD_MODELS.get(data.get("type", ""))
        payload = data.get("payload")
        if model is not None and isinstance(payload, dict):
            data = {**data, "payload": model.model_validate(payload)}
        return data

    @model_validator(mode="after")
    def _payload_matches_type(self) -> QuestionObject:
        model = _PAYLOAD_MODELS.get(self.type)
        if model is not None and not isinstance(self.payload, model):
            raise ValueError(f"{self.type} 질문의 payload 형식이 맞지 않습니다")
        if model is None and not isinstance(self.payload, dict):
            raise ValueError(f"{self.type} 질문의 payload는 객체여야 합니다")
        return self


class AnswerQuestionObject(QuestionObject):
    """36번 items[].question — 질문 객체 + `origin` (engine | free_text)."""

    origin: Literal["engine", "free_text"]


# --- 답변 값 (36·37·38번 response) ------------------------------------------------------------------
class AnswerValue(StrictCamelModel):
    """`{choice}` | `{value}` | `{skip}` 중 정확히 하나. 질문 유형과의 일치 여부는 서비스가 검사한다(400)."""

    choice: Literal["A", "B"] | None = None
    value: Annotated[int, Field(strict=True, ge=-5, le=5)] | None = None
    skip: Literal[True] | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> AnswerValue:
        given = [v for v in (self.choice, self.value, self.skip) if v is not None]
        if len(given) != 1:
            raise ValueError("choice, value, skip 중 정확히 하나만 보내야 합니다")
        return self

    @model_serializer(mode="wrap")
    def _only_given(self, handler: SerializerFunctionWrapHandler) -> dict[str, Any]:
        """명세 예시처럼 값이 있는 키 하나만 낸다."""
        out: dict[str, Any] = handler(self)
        return {k: v for k, v in out.items() if v is not None}


# --- 31 ----------------------------------------------------------------------------------------
class StartProgress(CamelModel):
    answered: int
    expected_range: IntRange


class StartQuestionsResponse(CamelModel):
    """31번 `POST /questions/start` 200. round는 initial | extra."""

    status: Literal["QUESTIONING"]
    round: Literal["initial", "extra"]
    question: QuestionObject
    progress: StartProgress


# --- 32 ----------------------------------------------------------------------------------------
class NextQuestionContinue(CamelModel):
    """32번: 다음 질문 있음."""

    done: Literal[False]
    question: QuestionObject


class NextQuestionDone(CamelModel):
    """32번: 종료."""

    done: Literal[True]
    reason: StopReason


NextQuestionResponse = Annotated[NextQuestionContinue | NextQuestionDone, Field(discriminator="done")]


# --- 33 ----------------------------------------------------------------------------------------
class StatusMetrics(CamelModel):
    answered: int
    top_option: str
    top_probability: float
    decision_entropy: float
    best_next_info_gain: float


class RemainingUncertainty(CamelModel):
    criterion_id: str
    interval: Interval


class StatusResponse(CamelModel):
    """33번 `GET /questions/status`."""

    should_stop: bool
    reason: StopReason | None
    metrics: StatusMetrics
    remaining_uncertainty: list[RemainingUncertainty]


# --- 34 ----------------------------------------------------------------------------------------
class ProgressResponse(CamelModel):
    """34번 `GET /questions/progress` (P2)."""

    answered: int
    expected_range: IntRange
    confidence: float


# --- 35 ----------------------------------------------------------------------------------------
class StopResponse(CamelModel):
    """35번 `POST /questions/stop`. lowConfidence는 39번과 같은 값(P_best < 0.55)."""

    reason: StopReason
    low_confidence: bool


# --- 36 ----------------------------------------------------------------------------------------
class AnswerListItem(CamelModel):
    id: str
    question_id: str
    question: AnswerQuestionObject
    response: AnswerValue
    is_valid: bool
    created_at: datetime


class AnswerListResponse(CamelModel):
    """36번 `GET /answers` (페이지네이션 없이 `items`만, 명세 그대로)."""

    items: list[AnswerListItem]
