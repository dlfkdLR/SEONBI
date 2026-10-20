"""LLMClient 인터페이스와 사용량 기록 (개발기획서 6.4, 8.1 ai_usage_logs).

서비스 코드는 `LLMClient`만 안다. 어떤 제공자의 어떤 모델이 응답했는지는 `LLMUsage`로만 알려준다.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Generic, Literal, Protocol, TypeVar

from pydantic import BaseModel

T = TypeVar("T", bound=BaseModel)

# 6.2의 호출 종류 6가지
CallType = Literal[
    "extract_conditions",
    "recommend_criteria",
    "recommend_options",
    "phrase_question",
    "summarize_options",
    "explain_result",
]
CALL_TYPES: tuple[CallType, ...] = (
    "extract_conditions",
    "recommend_criteria",
    "recommend_options",
    "phrase_question",
    "summarize_options",
    "explain_result",
)


@dataclass(frozen=True)
class LLMUsage:
    """`ai_usage_logs` 한 줄에 대응하는 기록. 프롬프트·응답 본문·사용자 입력은 담지 않는다(8.1)."""

    provider: str
    model: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    success: bool
    call_type: str


# 서비스 계층이 ai_usage_logs 쓰기를 붙이는 지점. 콜백 안의 예외는 호출 결과에 영향을 주지 않는다.
UsageCallback = Callable[[LLMUsage], None]


class LLMClient(Protocol):
    """서비스 코드가 아는 유일한 인터페이스 (6.4). 검증된 Pydantic 객체를 돌려준다."""

    async def complete_structured(self, schema: type[T], prompt: str, *, max_tokens: int) -> T: ...


@dataclass(frozen=True)
class Generated(Generic[T]):
    """어댑터 한 번의 성공 결과: 검증된 객체와 사용량(call_type은 라우터가 채운다)."""

    parsed: T
    usage: LLMUsage


class ProviderAdapter(Protocol):
    """제공자별 SDK 호출과 구조화 출력 변환만 담당하는 어댑터 (6.4)."""

    provider: str
    model: str

    async def generate(self, schema: type[T], prompt: str, *, max_tokens: int) -> Generated[T]: ...
