"""답변 API 스키마: 37·38번 (개발기획서 7.3 "질문·답변").

가정:
- 37번 `answer: { id: "a7", ... }`의 `...`는 36번 items의 `question`을 뺀 필드(id, questionId, response, isValid,
  createdAt)로 읽었다.
- 38번 응답은 "37번과 같은 estimate"라는 문장 그대로 `estimate`만 둔다(수정된 answer는 넣지 않는다).
"""

from __future__ import annotations

from datetime import datetime

from app.schemas.camel import CamelModel, StrictCamelModel
from app.schemas.questions import AnswerValue, Interval, NextQuestionResponse


class SubmitAnswerRequest(StrictCamelModel):
    """37번 요청. response는 choice | value | skip 중 하나."""

    question_id: str
    response: AnswerValue


class EditAnswerRequest(StrictCamelModel):
    """38번 요청."""

    response: AnswerValue


class AnswerObject(CamelModel):
    id: str
    question_id: str
    response: AnswerValue
    is_valid: bool
    created_at: datetime


class WeightEstimate(CamelModel):
    criterion_id: str
    mean: float
    interval: Interval


class Estimate(CamelModel):
    weights: list[WeightEstimate]
    top_probability: float


class SubmitAnswerResponse(CamelModel):
    """37번 201. next는 32번과 같은 형식(다음 질문을 함께 반환)."""

    answer: AnswerObject
    estimate: Estimate
    next: NextQuestionResponse


class EditAnswerResponse(CamelModel):
    """38번 200 — 전체 재추정 후 37번과 같은 estimate."""

    estimate: Estimate
