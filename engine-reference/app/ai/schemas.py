"""LLM 출력 스키마 6종 (개발기획서 6.2, 7.3의 19·28번 "추출 항목 형식").

JSON 키는 camelCase(별칭), 파이썬 속성은 snake_case. 검증 실패는 어댑터가 `SchemaViolationError`로 바꾼다.
"""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

Direction = Literal["higher_better", "lower_better", "true_better"]
CriterionType = Literal["numeric", "rating", "boolean"]
InputMode = Literal["score", "rank"]
Confidence = Literal["high", "medium", "low"]


class _Base(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


def check_levels(levels: list[str] | None) -> None:
    """7.3 19번: levels는 3~7개, 각 1~12자, 중복 불가."""
    if levels is None:
        return
    if not 3 <= len(levels) <= 7:
        raise ValueError("levels는 3~7개여야 합니다")
    for label in levels:
        if not label.strip() or len(label) > 12:
            raise ValueError("levels의 각 라벨은 1~12자여야 합니다")
    if len(set(levels)) != len(levels):
        raise ValueError("levels에 중복이 있습니다")


def check_input_mode(criterion_type: str, input_mode: str | None) -> None:
    """7.3 19번: inputMode는 rating에서만 허용."""
    if input_mode is not None and criterion_type != "rating":
        raise ValueError("inputMode는 rating에서만 허용됩니다")


# ── extract_conditions ────────────────────────────────────────────────


class ExtractedCriterion(_Base):
    """type=criterion. 19번 요청 필드. 항목 구분자 `type`과 이름이 겹쳐 기준 유형은 `criterionType`으로 둔다."""

    type: Literal["criterion"]
    name: str = Field(min_length=1)
    criterion_type: CriterionType
    direction: Direction | None = None
    unit: str | None = None
    levels: list[str] | None = None
    input_mode: InputMode | None = None
    confidence: Confidence

    @model_validator(mode="after")
    def _check(self) -> ExtractedCriterion:
        check_levels(self.levels)
        check_input_mode(self.criterion_type, self.input_mode)
        return self


class ExtractedConstraint(_Base):
    """type=constraint. 27번 항목 필드."""

    type: Literal["constraint"]
    kind: Literal["threshold", "required_tag"]
    criterion_id: str | None = None
    operator: Literal["<=", ">=", "=="] | None = None
    value: bool | float | None = None
    label: str | None = None
    satisfied_by: list[str] | None = None
    confidence: Confidence

    @model_validator(mode="after")
    def _check(self) -> ExtractedConstraint:
        if self.kind == "threshold" and (self.criterion_id is None or self.operator is None or self.value is None):
            raise ValueError("threshold에는 criterionId·operator·value가 필요합니다")
        if self.kind == "required_tag" and not self.label:
            raise ValueError("required_tag에는 label이 필요합니다")
        return self


class ExtractedOption(_Base):
    """type=option. 14번 요청 필드."""

    type: Literal["option"]
    name: str = Field(min_length=1)
    description: str | None = None
    url: str | None = None
    confidence: Confidence


class ExtractedPreference(_Base):
    """type=preference. value는 -5~+5, 양수면 A가 더 중요."""

    type: Literal["preference"]
    criterion_a: str = Field(min_length=1)
    criterion_b: str = Field(min_length=1)
    value: int = Field(ge=-5, le=5)
    confidence: Confidence


ExtractedItem = Annotated[
    ExtractedCriterion | ExtractedConstraint | ExtractedOption | ExtractedPreference,
    Field(discriminator="type"),
]


class ExtractConditionsOutput(_Base):
    relevant: bool
    items: list[ExtractedItem] = Field(default_factory=list)
    ambiguity: str | None = None


# ── recommend_criteria ───────────────────────────────────────────────


class RecommendCriterion(_Base):
    name: str = Field(min_length=1)
    type: CriterionType
    direction: Direction | None = None  # rating이면 생략(항상 higher_better), boolean이면 true_better (19번)
    unit: str | None = None
    levels: list[str] | None = None
    input_mode: InputMode | None = None
    reason: str

    @model_validator(mode="after")
    def _check(self) -> RecommendCriterion:
        check_levels(self.levels)
        check_input_mode(self.type, self.input_mode)
        return self


class RecommendCriteriaOutput(_Base):
    criteria: list[RecommendCriterion] = Field(min_length=3, max_length=6)


# ── recommend_options (P2) ───────────────────────────────────────────


class RecommendedOption(_Base):
    name: str = Field(min_length=1)
    reason: str


class RecommendOptionsOutput(_Base):
    options: list[RecommendedOption]


# ── phrase_question ──────────────────────────────────────────────────


class PhraseQuestionOutput(_Base):
    title: str = Field(min_length=1)
    option_a: str | None = None
    option_b: str | None = None
    left_label: str | None = None
    right_label: str | None = None


# ── summarize_options ────────────────────────────────────────────────


class OptionProsCons(_Base):
    option_id: str
    pros: list[str] = Field(default_factory=list)
    cons: list[str] = Field(default_factory=list)


class SummarizeOptionsOutput(_Base):
    options: list[OptionProsCons]


# ── explain_result ───────────────────────────────────────────────────


class ExplainResultOutput(_Base):
    summary: str = Field(min_length=1)
    reasons: list[str] = Field(default_factory=list)
    caveats: list[str] = Field(default_factory=list)
