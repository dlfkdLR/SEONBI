"""Gemini 어댑터: 스텁 클라이언트로 성공·스키마 위반 재시도·5xx·타임아웃 (네트워크 없음)."""

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.ai.errors import LLMConfigError, ProviderError, SchemaViolationError
from app.ai.providers.gemini import GeminiAdapter
from app.ai.schemas import PhraseQuestionOutput


class ApiError(Exception):
    def __init__(self, code: int) -> None:
        super().__init__("api error")
        self.code = code


def _response(text: str | None, inp: int = 11, out: int = 7) -> SimpleNamespace:
    return SimpleNamespace(
        text=text, usage_metadata=SimpleNamespace(prompt_token_count=inp, candidates_token_count=out)
    )


class StubClient:
    def __init__(self, *results: Any, delay: float = 0.0) -> None:
        self._results = list(results)
        self._delay = delay
        self.requests: list[dict[str, Any]] = []
        self.aio = SimpleNamespace(models=SimpleNamespace(generate_content=self._generate))

    async def _generate(self, **kwargs: Any) -> Any:
        self.requests.append(kwargs)
        if self._delay:
            await asyncio.sleep(self._delay)
        result = self._results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


GOOD = '{"title": "제목", "optionA": "가", "leftLabel": "좌"}'


def _gen(adapter: GeminiAdapter) -> Any:
    return asyncio.run(adapter.generate(PhraseQuestionOutput, "프롬프트", max_tokens=300))


def test_success_sends_json_config_and_converts_usage() -> None:
    client = StubClient(_response(GOOD))
    result = _gen(GeminiAdapter("gemini-x", client=client))
    assert result.parsed.title == "제목" and result.parsed.option_a == "가" and result.parsed.left_label == "좌"
    assert (result.usage.provider, result.usage.model) == ("gemini", "gemini-x")
    assert (result.usage.input_tokens, result.usage.output_tokens, result.usage.success) == (11, 7, True)
    req = client.requests[0]
    assert req["model"] == "gemini-x" and req["contents"] == "프롬프트"
    assert req["config"]["response_mime_type"] == "application/json"
    assert req["config"]["response_schema"] is PhraseQuestionOutput
    assert req["config"]["max_output_tokens"] == 300
    assert req["config"]["http_options"]["timeout"] == 10_000


def test_schema_violation_retried_once_then_succeeds() -> None:
    client = StubClient(_response("{not json"), _response(GOOD))
    assert _gen(GeminiAdapter("m", client=client)).parsed.title == "제목"
    assert len(client.requests) == 2


def test_schema_violation_twice_fails_after_exactly_one_retry() -> None:
    client = StubClient(_response('{"wrong": 1}'), _response(""), _response(GOOD))
    with pytest.raises(SchemaViolationError):
        _gen(GeminiAdapter("m", client=client))
    assert len(client.requests) == 2


def test_5xx_retried_once() -> None:
    client = StubClient(ApiError(503), _response(GOOD))
    assert _gen(GeminiAdapter("m", client=client)).parsed.title == "제목"
    client = StubClient(ApiError(500), ApiError(502), _response(GOOD))
    with pytest.raises(ProviderError):
        _gen(GeminiAdapter("m", client=client))
    assert len(client.requests) == 2


def test_4xx_not_retried() -> None:
    client = StubClient(ApiError(429), _response(GOOD))
    with pytest.raises(ProviderError) as info:
        _gen(GeminiAdapter("m", client=client))
    assert not info.value.retryable and len(client.requests) == 1


def test_timeout_not_retried() -> None:
    client = StubClient(_response(GOOD), delay=0.2)
    with pytest.raises(ProviderError, match="시간 초과"):
        _gen(GeminiAdapter("m", client=client, timeout_s=0.01))
    assert len(client.requests) == 1


def test_error_messages_do_not_leak_prompt_or_body() -> None:
    client = StubClient(_response('{"secret-body": 1}'), _response('{"secret-body": 1}'))
    with pytest.raises(SchemaViolationError) as info:
        asyncio.run(GeminiAdapter("m", client=client).generate(PhraseQuestionOutput, "SECRET-PROMPT", max_tokens=1))
    assert "SECRET" not in str(info.value) and "secret" not in str(info.value)


def test_missing_key_without_injected_client_is_config_error() -> None:
    with pytest.raises(LLMConfigError, match="GEMINI_API_KEY"):
        _gen(GeminiAdapter("m"))
