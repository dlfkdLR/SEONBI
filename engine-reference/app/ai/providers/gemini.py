"""Gemini 어댑터 (개발기획서 6.4). `google-genai` SDK는 처음 쓸 때 불러온다(import 시점에 필요 없음).

- 구조화 출력: `response_mime_type="application/json"` + `response_schema`(Pydantic 모델 클래스, SDK가 변환).
- 타임아웃 10초. 재시도는 스키마 위반·5xx에 한해 1회만(6.3, 6.4). 타임아웃·4xx는 재시도하지 않는다.
- 테스트는 `client`에 `client.aio.models.generate_content(...)`를 가진 스텁을 주입한다(네트워크 없음).
- 오류 메시지에는 프롬프트·응답 본문을 넣지 않는다.
"""

from __future__ import annotations

import asyncio
import importlib
from typing import Any

from pydantic import ValidationError

from ..client import Generated, LLMUsage, T
from ..errors import LLMConfigError, ProviderError, SchemaViolationError

TIMEOUT_SECONDS = 10.0


def _status_code(exc: BaseException) -> int | None:
    """SDK 예외(`google.genai.errors.APIError`)의 HTTP 상태 코드. SDK를 import하지 않고 속성으로 읽는다."""
    for attr in ("code", "status_code"):
        value = getattr(exc, attr, None)
        if isinstance(value, int):
            return value
    return None


class GeminiAdapter:
    provider = "gemini"

    def __init__(
        self,
        model: str,
        *,
        api_key: str | None = None,
        client: Any = None,
        timeout_s: float = TIMEOUT_SECONDS,
    ) -> None:
        self.model = model
        self._api_key = api_key
        self._client = client
        self._timeout_s = timeout_s

    def _get_client(self) -> Any:
        if self._client is None:
            if not self._api_key:
                raise LLMConfigError("GEMINI_API_KEY가 없습니다")
            try:
                genai = importlib.import_module("google.genai")
            except ImportError as exc:
                raise LLMConfigError("google-genai 패키지가 설치되어 있지 않습니다") from exc
            self._client = genai.Client(api_key=self._api_key)
        return self._client

    async def generate(self, schema: type[T], prompt: str, *, max_tokens: int) -> Generated[T]:
        last: ProviderError | SchemaViolationError | None = None
        for _ in range(2):  # 최초 1회 + 재시도 1회
            try:
                return await self._once(schema, prompt, max_tokens)
            except SchemaViolationError as exc:
                last = exc
            except ProviderError as exc:
                if not exc.retryable:
                    raise
                last = exc
        assert last is not None
        raise last

    async def complete_structured(self, schema: type[T], prompt: str, *, max_tokens: int) -> T:
        return (await self.generate(schema, prompt, max_tokens=max_tokens)).parsed

    async def _once(self, schema: type[T], prompt: str, max_tokens: int) -> Generated[T]:
        client = self._get_client()
        config: dict[str, Any] = {
            "response_mime_type": "application/json",
            "response_schema": schema,
            "max_output_tokens": max_tokens,
            "http_options": {"timeout": int(self._timeout_s * 1000)},
        }
        try:
            response = await asyncio.wait_for(
                client.aio.models.generate_content(model=self.model, contents=prompt, config=config),
                timeout=self._timeout_s,
            )
        except TimeoutError:
            raise ProviderError("Gemini 응답 시간 초과") from None
        except Exception as exc:
            code = _status_code(exc)
            raise ProviderError(
                f"Gemini 호출 실패 ({type(exc).__name__}, status={code})",
                retryable=code is not None and code >= 500,
            ) from exc

        text = getattr(response, "text", None)
        if not isinstance(text, str) or not text.strip():
            raise SchemaViolationError("Gemini 응답이 비어 있습니다")
        try:
            parsed = schema.model_validate_json(text)
        except ValidationError as exc:
            raise SchemaViolationError("Gemini 응답이 스키마와 맞지 않습니다") from exc

        meta = getattr(response, "usage_metadata", None)
        usage = LLMUsage(
            provider=self.provider,
            model=self.model,
            input_tokens=int(getattr(meta, "prompt_token_count", 0) or 0),
            output_tokens=int(getattr(meta, "candidates_token_count", 0) or 0),
            latency_ms=0,  # 실제 지연은 라우터가 잰다
            success=True,
            call_type="",  # 라우터가 채운다
        )
        return Generated(parsed, usage)
