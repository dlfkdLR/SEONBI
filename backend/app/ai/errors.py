"""AI 계층 오류 (개발기획서 6.2, 6.3, 6.4).

서비스 계층은 `AIUnavailableError`를 `503 AI_UNAVAILABLE`로 옮긴다(7.1).
오류 메시지에는 프롬프트·사용자 원문을 넣지 않는다(8.1, 개인정보 최소화).
"""

from __future__ import annotations


class LLMError(Exception):
    """호출 중 발생하는 모든 LLM 오류의 부모. 라우터의 대체 모델 시도 대상이다."""


class ProviderError(LLMError):
    """제공자 호출 실패(네트워크·타임아웃·4xx/5xx). `retryable`은 5xx처럼 한 번 더 시도할 만한 경우."""

    def __init__(self, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.retryable = retryable


class SchemaViolationError(LLMError):
    """응답이 JSON이 아니거나 Pydantic 스키마 검증에 실패(6.3)."""


class AIUnavailableError(LLMError):
    """모든 모델이 실패했거나 fallback이 없는 호출이 실패 — 서비스가 503으로 옮긴다."""


class LLMConfigError(Exception):
    """설정 오류. 서버 시작 시 발견해 멈추기 위한 것이라 `LLMError`가 아니다(6.4)."""


class ProviderNotImplementedError(LLMConfigError):
    """등록은 되어 있으나 어댑터가 아직 없는 제공자(openai, anthropic)."""
