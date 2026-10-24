"""LLMSettings 파싱·검증, LLMRouter 모델 선택·대체 순서·사용량 훅 (개발기획서 6.4, 11.3)."""

import asyncio

import pytest

from app.ai.client import LLMUsage
from app.ai.errors import AIUnavailableError, LLMConfigError, ProviderError, SchemaViolationError
from app.ai.providers.fake import FakeAdapter
from app.ai.router import LLMRouter, LLMSettings, ModelSpec, parse_model_spec, validate_settings
from app.ai.schemas import ExplainResultOutput, PhraseQuestionOutput


def test_defaults_are_fake() -> None:
    s = LLMSettings.from_env({})
    assert s.model_fast == s.model_quality == "fake:default"
    assert list(s.fallback_models) == [] and dict(s.routes) == {} and dict(s.api_keys) == {}


def test_from_env_parses_everything() -> None:
    s = LLMSettings.from_env(
        {
            "LLM_MODEL_FAST": "gemini:gemini-2.5-flash",
            "LLM_MODEL_QUALITY": "anthropic:claude-sonnet-4-5",
            "LLM_FALLBACK_MODELS": "openai:gpt-4.1-mini, fake:default",
            "LLM_ROUTES": "explain_result=anthropic:claude-sonnet-4-5, phrase_question=fake:default",
            "GEMINI_API_KEY": " k1 ",
            "OPENAI_API_KEY": "",
            "ANTHROPIC_API_KEY": "k3",
        }
    )
    assert s.model_fast == "gemini:gemini-2.5-flash"
    assert list(s.fallback_models) == ["openai:gpt-4.1-mini", "fake:default"]
    assert dict(s.routes) == {"explain_result": "anthropic:claude-sonnet-4-5", "phrase_question": "fake:default"}
    assert dict(s.api_keys) == {"gemini": "k1", "anthropic": "k3"}
    assert "k1" not in repr(s)  # 키는 repr에 나오지 않는다


@pytest.mark.parametrize("text", ["gemini", "gemini:", ":model", "unknown:x", ""])
def test_bad_model_spec(text: str) -> None:
    with pytest.raises(LLMConfigError):
        parse_model_spec(text)


def test_model_name_may_contain_colon() -> None:
    assert parse_model_spec("gemini:models/x:y") == ModelSpec("gemini", "models/x:y")


@pytest.mark.parametrize("routes", ["explain_result", "nope=fake:default"])
def test_bad_routes(routes: str) -> None:
    with pytest.raises(LLMConfigError):
        LLMSettings.from_env({"LLM_ROUTES": routes})


def test_validate_requires_key_for_configured_models() -> None:
    validate_settings(LLMSettings())  # fake는 키 불필요
    with pytest.raises(LLMConfigError, match="GEMINI_API_KEY"):
        validate_settings(LLMSettings(model_fast="gemini:m"))
    validate_settings(LLMSettings(model_fast="gemini:m", api_keys={"gemini": "k"}))
    with pytest.raises(LLMConfigError, match="OPENAI_API_KEY"):
        validate_settings(LLMSettings(fallback_models=["openai:m"]))
    with pytest.raises(LLMConfigError, match="ANTHROPIC_API_KEY"):
        validate_settings(LLMSettings(routes={"explain_result": "anthropic:m"}))


def test_validation_error_never_contains_key_value() -> None:
    with pytest.raises(LLMConfigError) as info:
        validate_settings(
            LLMSettings(model_fast="gemini:m", fallback_models=["openai:m"], api_keys={"gemini": "SECRET"})
        )
    assert "SECRET" not in str(info.value)


def test_router_validate_flags_unimplemented_providers() -> None:
    settings = LLMSettings(model_fast="openai:m", api_keys={"openai": "k"})
    with pytest.raises(LLMConfigError, match="아직 구현되지 않음"):
        LLMRouter(settings).validate_settings()
    LLMRouter(LLMSettings()).validate_settings()  # fake만이면 통과
    LLMRouter(LLMSettings(model_fast="gemini:m", api_keys={"gemini": "k"})).validate_settings()  # SDK import 없이 통과


def _router(settings: LLMSettings, adapters: dict[str, FakeAdapter], **kw: object) -> LLMRouter:
    return LLMRouter(settings, registry={"fake": lambda spec, s: adapters[spec.model]}, **kw)  # type: ignore[arg-type]


def test_model_selection_fast_quality_and_routes() -> None:
    s = LLMSettings(model_fast="fake:f", model_quality="fake:q", routes={"phrase_question": "fake:r"})
    assert str(s.primary_for("extract_conditions")) == "fake:f"
    assert str(s.primary_for("summarize_options")) == "fake:f"
    assert str(s.primary_for("explain_result")) == "fake:q"
    assert str(s.primary_for("phrase_question")) == "fake:r"
    s2 = LLMSettings(model_fast="fake:f", model_quality="fake:q", routes={"explain_result": "fake:r"})
    assert str(s2.primary_for("explain_result")) == "fake:r"


def test_router_uses_selected_adapter() -> None:
    adapters = {"f": FakeAdapter("f"), "q": FakeAdapter("q")}
    router = _router(LLMSettings(model_fast="fake:f", model_quality="fake:q"), adapters)
    asyncio.run(router.client_for("explain_result").complete_structured(ExplainResultOutput, "p", max_tokens=10))
    asyncio.run(router.client_for("phrase_question").complete_structured(PhraseQuestionOutput, "p", max_tokens=10))
    assert [c[0] for c in adapters["q"].calls] == [ExplainResultOutput]
    assert [c[0] for c in adapters["f"].calls] == [PhraseQuestionOutput]


def test_unknown_call_type_rejected() -> None:
    with pytest.raises(LLMConfigError):
        LLMRouter(LLMSettings()).client_for("nope")


def test_fallback_order_on_provider_failure() -> None:
    adapters = {
        "a": FakeAdapter("a", script=[ProviderError("down")]),
        "b": FakeAdapter("b", script=[SchemaViolationError("bad")]),
        "c": FakeAdapter("c"),
        "d": FakeAdapter("d"),
    }
    settings = LLMSettings(model_fast="fake:a", fallback_models=["fake:b", "fake:c", "fake:d"])
    out = asyncio.run(
        _router(settings, adapters)
        .client_for("phrase_question")
        .complete_structured(PhraseQuestionOutput, "p", max_tokens=10)
    )
    assert out.title
    assert [len(a.calls) for a in adapters.values()] == [1, 1, 1, 0]  # a→b→c 에서 멈춘다


def test_all_models_failing_raises_unavailable() -> None:
    adapters = {"a": FakeAdapter("a", script=[ProviderError("x")]), "b": FakeAdapter("b", script=[ProviderError("y")])}
    router = _router(LLMSettings(model_fast="fake:a", fallback_models=["fake:b"]), adapters)
    with pytest.raises(AIUnavailableError):
        asyncio.run(router.client_for("phrase_question").complete_structured(PhraseQuestionOutput, "p", max_tokens=1))


def test_no_fallback_models_means_single_attempt() -> None:
    adapters = {"a": FakeAdapter("a", script=[ProviderError("x")])}
    router = _router(LLMSettings(model_fast="fake:a"), adapters)
    with pytest.raises(AIUnavailableError):
        asyncio.run(router.client_for("phrase_question").complete_structured(PhraseQuestionOutput, "p", max_tokens=1))
    assert len(adapters["a"].calls) == 1


def test_usage_hook_records_each_attempt_without_prompt_text() -> None:
    seen: list[LLMUsage] = []
    per_call: list[LLMUsage] = []
    adapters = {"a": FakeAdapter("a", script=[ProviderError("down")]), "b": FakeAdapter("b")}
    router = _router(LLMSettings(model_fast="fake:a", fallback_models=["fake:b"]), adapters, on_usage=seen.append)
    client = router.client_for("phrase_question", on_usage=per_call.append)
    asyncio.run(client.complete_structured(PhraseQuestionOutput, "SECRET-PROMPT-텍스트", max_tokens=10))
    assert [(u.model, u.success, u.call_type) for u in seen] == [
        ("a", False, "phrase_question"),
        ("b", True, "phrase_question"),
    ]
    assert per_call == seen
    assert all(u.provider == "fake" and u.latency_ms >= 0 for u in seen)
    assert "SECRET" not in repr(seen)


def test_usage_hook_exception_does_not_break_call() -> None:
    def boom(_: LLMUsage) -> None:
        raise RuntimeError("hook")

    router = _router(LLMSettings(model_fast="fake:a", model_quality="fake:a"), {"a": FakeAdapter("a")}, on_usage=boom)
    out = asyncio.run(router.client_for("explain_result").complete_structured(ExplainResultOutput, "p", max_tokens=1))
    assert out.summary
