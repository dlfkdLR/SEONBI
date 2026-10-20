"""A의 서비스가 데이터를 읽고 쓰는 통로 (개발기획서 8장, 역할 문서 5절).

A의 서비스(질문 흐름·분석)는 DB 모델에 직접 의존하지 않고 이 파일의 데이터 클래스와 Protocol만 안다.
B가 SQLAlchemy 모델을 만들면 B(또는 연결 담당)가 아래 Protocol을 구현한 어댑터를 붙인다.
테스트는 tests/services/fakes.py의 메모리 구현으로 돌린다.

필드 이름은 8장 컬럼 이름을 따랐다. 새 필드를 만들지 않는다.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from .state_rules import Status

Category = Literal["purchase", "career", "study", "activity", "living", "etc"]
QuestionType = Literal["slider_pair", "ab_criteria", "ab_scenario", "numeric", "free_text"]
QuestionOrigin = Literal["engine", "free_text"]
PhrasingSource = Literal["ai", "template"]
PreferenceKind = Literal["stated", "revealed", "none"]


@dataclass(frozen=True)
class OptionRow:
    id: str
    name: str
    description: str | None = None
    position: int = 0


@dataclass(frozen=True)
class CriterionRow:
    id: str
    name: str
    type: Literal["numeric", "rating", "boolean"]
    direction: Literal["higher_better", "lower_better", "true_better"] | None
    position: int
    unit: str | None = None
    levels: tuple[str, ...] | None = None
    input_mode: Literal["score", "rank"] = "score"
    scale_min: float | None = None
    scale_max: float | None = None
    scale_frozen: bool = False


@dataclass(frozen=True)
class ValueRow:
    """option_values 한 칸. 숫자는 value_num, 예/아니요는 value_bool, 순위 입력은 value_rank에 담긴다."""

    option_id: str
    criterion_id: str
    value_num: float | None = None
    value_bool: bool | None = None
    value_rank: int | None = None


@dataclass(frozen=True)
class ConditionRow:
    id: str
    kind: Literal["threshold", "required_tag"]
    criterion_id: str | None = None
    operator: Literal["<=", ">=", "=="] | None = None
    value: float | None = None
    label: str | None = None
    satisfied_by: frozenset[str] = frozenset()  # required_tag: 충족하는 선택지 id


@dataclass(frozen=True)
class DecisionSnapshot:
    """한 결정의 읽기 전용 스냅샷. B의 get_decision 의존성이 만들어 넘긴다. 기준은 position 순이다."""

    id: str
    client_id: str
    category: Category
    status: Status
    rng_seed: int
    question_limit: int | None
    version: int
    options: tuple[OptionRow, ...]
    criteria: tuple[CriterionRow, ...]
    values: tuple[ValueRow, ...]
    conditions: tuple[ConditionRow, ...] = ()


@dataclass(frozen=True)
class QuestionRow:
    id: str
    decision_id: str
    seq: int
    type: QuestionType
    spec: dict[str, Any]  # 계산용 스펙(기준은 인덱스가 아니라 id로 저장)
    display: dict[str, Any]  # 화면용 질문 객체 내용 (7.3 "질문 객체")
    phrasing_source: PhrasingSource
    origin: QuestionOrigin = "engine"
    info_gain: float | None = None


@dataclass(frozen=True)
class AnswerRow:
    id: str
    question_id: str
    decision_id: str
    response: dict[str, Any]  # {"choice": "A"} | {"value": -2} | {"skip": true}
    preference_kind: PreferenceKind
    is_valid: bool = True
    created_at: str = ""
    updated_at: str = ""


@dataclass(frozen=True)
class CachedAnalysis:
    decision_version: int
    payload: dict[str, Any]
    explanation: dict[str, Any] | None = None


@dataclass(frozen=True)
class FinalChoiceRow:
    decision_id: str
    option_id: str
    memo: str | None
    matched_recommendation: bool
    recommendation_snapshot: dict[str, Any] = field(default_factory=dict)
    finalized_at: str = ""


@dataclass(frozen=True)
class UsageRecord:
    """ai_usage_logs 한 줄 (8.1). 사용자 원문·프롬프트는 담지 않는다."""

    client_id: str
    decision_id: str | None
    call_type: str
    provider: str
    model: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    success: bool


class DecisionWriter(Protocol):
    """decisions 행 중 A가 바꾸는 컬럼. version 증가는 쓰기와 같은 트랜잭션에서 한다(8.2)."""

    async def set_status(self, decision_id: str, status: Status) -> None: ...

    async def set_question_limit(self, decision_id: str, limit: int | None) -> None: ...

    async def freeze_scales(self, decision_id: str, scales: dict[str, tuple[float, float]]) -> None:
        """기준 id → (scale_min, scale_max). scale_frozen = true로 함께 저장한다."""
        ...

    async def bump_version(self, decision_id: str) -> int: ...


class QuestionStore(Protocol):
    async def list_questions(self, decision_id: str) -> Sequence[QuestionRow]:
        """seq 순."""
        ...

    async def add_question(self, row: QuestionRow) -> None: ...

    async def list_answers(self, decision_id: str) -> Sequence[AnswerRow]:
        """질문 seq 순. 무효 답변(is_valid=false)도 포함한다."""
        ...

    async def add_answer(self, row: AnswerRow) -> None: ...

    async def update_answer(
        self, answer_id: str, response: dict[str, Any], preference_kind: PreferenceKind
    ) -> None: ...

    async def invalidate_answers_of_criterion(self, decision_id: str, criterion_id: str) -> int:
        """기준이 삭제될 때 그 기준이 들어간 답변을 is_valid=false로. 바꾼 개수를 돌려준다(22번)."""
        ...

    async def update_question_display(
        self, question_id: str, display: dict[str, Any], phrasing_source: PhrasingSource
    ) -> None: ...


class AnalysisCache(Protocol):
    async def get(self, decision_id: str) -> CachedAnalysis | None: ...

    async def put(self, decision_id: str, value: CachedAnalysis) -> None: ...


class FinalChoiceStore(Protocol):
    async def add(self, row: FinalChoiceRow) -> None: ...

    async def get(self, decision_id: str) -> FinalChoiceRow | None: ...


class UsageStore(Protocol):
    """AI 호출 기록과 하루 상한 집계 (12.2). 사용자가 직접 부르는 호출(18·23·28)만 센다."""

    async def count_today(self, client_id: str, call_types: Sequence[str]) -> int: ...

    async def add(self, record: UsageRecord) -> None: ...


@dataclass(frozen=True)
class Stores:
    """한 요청에서 쓰는 저장소 묶음."""

    decisions: DecisionWriter
    questions: QuestionStore
    analysis: AnalysisCache
    final_choices: FinalChoiceStore
    usage: UsageStore
