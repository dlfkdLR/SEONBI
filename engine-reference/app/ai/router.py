"""LLMRouter와 설정 (개발기획서 6.4, 11.3).

호출 종류마다 모델을 고르고, 실패하면 `LLM_FALLBACK_MODELS`를 순서대로 시도한다.
제공자 추가 = 어댑터 파일 + 레지스트리 항목 + 키 환경변수.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace

from .client import CALL_TYPES, LLMClient, LLMUsage, ProviderAdapter, T, UsageCallback
from .errors import AIUnavailableError, LLMConfigError, LLMError, ProviderNotImplementedError
from .providers.fake import FakeAdapter
from .providers.gemini import GeminiAdapter

logger = logging.getLogger(__name__)

KNOWN_PROVIDERS = ("gemini", "openai", "anthropic", "fake")
# 키가 필요한 제공자 → 환경변수 이름 (fake는 키 불필요)
KEY_ENV: dict[str, str] = {"gemini": "GEMINI_API_KEY", "openai": "OPENAI_API_KEY", "anthropic": "ANTHROPIC_API_KEY"}
# 6.4: 결과 설명만 QUALITY 모델, 나머지는 FAST (LLM_ROUTES로 덮어쓸 수 있다)
QUALITY_CALLS = frozenset({"explain_result"})
DEFAULT_MODEL = "fake:default"


@dataclass(frozen=True)
class ModelSpec:
    provider: str
    model: str

    def __str__(self) -> str:
        return f"{self.provider}:{self.model}"


def parse_model_spec(text: str) -> ModelSpec:
    """`제공자:모델명` 파싱. 첫 `:`에서만 나눈다(모델명에 `:`가 있어도 됨)."""
    provider, sep, model = text.strip().partition(":")
    if not sep or not provider or not model:
        raise LLMConfigError(f"모델 지정 형식은 '제공자:모델명'이어야 합니다: {text!r}")
    if provider not in KNOWN_PROVIDERS:
        raise LLMConfigError(f"지원하지 않는 제공자입니다: {provider!r} (지원: {', '.join(KNOWN_PROVIDERS)})")
    return ModelSpec(provider, model)


def _split(value: str) -> list[str]:
    return [part.strip() for part in value.split(",") if part.strip()]


@dataclass(frozen=True)
class LLMSettings:
    """11.3의 LLM 설정. `api_keys`는 제공자 → 키이며 repr에 나오지 않는다."""

    model_fast: str = DEFAULT_MODEL
    model_quality: str = DEFAULT_MODEL
    fallback_models: Sequence[str] = ()
    routes: Mapping[str, str] = field(default_factory=dict)  # 호출 종류 → 모델
    api_keys: Mapping[str, str] = field(default_factory=dict, repr=False)

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> LLMSettings:
        routes: dict[str, str] = {}
        for entry in _split(env.get("LLM_ROUTES", "")):
            call, sep, model = entry.partition("=")
            call = call.strip()
            if not sep or call not in CALL_TYPES:
                raise LLMConfigError(f"LLM_ROUTES 항목 형식 오류(호출=제공자:모델): {call!r}")
            routes[call] = model.strip()
        keys = {p: env[name].strip() for p, name in KEY_ENV.items() if env.get(name, "").strip()}
        return cls(
            model_fast=env.get("LLM_MODEL_FAST", "").strip() or DEFAULT_MODEL,
            model_quality=env.get("LLM_MODEL_QUALITY", "").strip() or DEFAULT_MODEL,
            fallback_models=tuple(_split(env.get("LLM_FALLBACK_MODELS", ""))),
            routes=routes,
            api_keys=keys,
        )

    def primary_for(self, call_type: str) -> ModelSpec:
        """호출 종류의 기본 모델: LLM_ROUTES > (explain_result면 QUALITY, 아니면 FAST)."""
        if call_type in self.routes:
            return parse_model_spec(self.routes[call_type])
        return parse_model_spec(self.model_quality if call_type in QUALITY_CALLS else self.model_fast)

    def candidates_for(self, call_type: str) -> list[ModelSpec]:
        """시도 순서: 기본 모델 → 대체 모델들(중복 제거)."""
        ordered = [self.primary_for(call_type), *(parse_model_spec(m) for m in self.fallback_models)]
        return list(dict.fromkeys(ordered))

    def all_specs(self) -> list[ModelSpec]:
        texts = [self.model_fast, self.model_quality, *self.fallback_models, *self.routes.values()]
        return list(dict.fromkeys(parse_model_spec(t) for t in texts))


def validate_settings(settings: LLMSettings) -> None:
    """서버 시작 시 호출: 형식 오류나 제공자 키 누락이면 `LLMConfigError`(6.4). 키 값은 쓰지 않는다."""
    for call in settings.routes:
        if call not in CALL_TYPES:
            raise LLMConfigError(f"LLM_ROUTES에 알 수 없는 호출 종류가 있습니다: {call!r}")
    for spec in settings.all_specs():
        env_name = KEY_ENV.get(spec.provider)
        if env_name is not None and not settings.api_keys.get(spec.provider):
            raise LLMConfigError(f"{spec} 모델을 쓰려면 {env_name}가 필요합니다")


AdapterFactory = Callable[[ModelSpec, LLMSettings], ProviderAdapter]


def _fake_factory(spec: ModelSpec, settings: LLMSettings) -> ProviderAdapter:
    return FakeAdapter(spec.model)


def _gemini_factory(spec: ModelSpec, settings: LLMSettings) -> ProviderAdapter:
    return GeminiAdapter(spec.model, api_key=settings.api_keys.get("gemini"))


def _not_implemented(provider: str) -> AdapterFactory:
    def factory(spec: ModelSpec, settings: LLMSettings) -> ProviderAdapter:
        raise ProviderNotImplementedError(f"{provider} 어댑터는 아직 구현되지 않음")

    return factory


def default_registry() -> dict[str, AdapterFactory]:
    """제공자 이름 → 어댑터 팩토리."""
    return {
        "gemini": _gemini_factory,
        "fake": _fake_factory,
        "openai": _not_implemented("openai"),
        "anthropic": _not_implemented("anthropic"),
    }


class LLMRouter:
    def __init__(
        self,
        settings: LLMSettings,
        *,
        registry: Mapping[str, AdapterFactory] | None = None,
        on_usage: UsageCallback | None = None,
    ) -> None:
        self.settings = settings
        self._registry = dict(registry) if registry is not None else default_registry()
        self._on_usage = on_usage
        self._adapters: dict[str, ProviderAdapter] = {}

    def validate_settings(self) -> None:
        """키 검사 + 설정된 모든 모델의 어댑터를 만들어 본다(구현 안 된 제공자도 시작 시 드러난다)."""
        validate_settings(self.settings)
        for spec in self.settings.all_specs():
            self.adapter_for(spec)

    def adapter_for(self, spec: ModelSpec) -> ProviderAdapter:
        key = str(spec)
        if key not in self._adapters:
            factory = self._registry.get(spec.provider)
            if factory is None:
                raise LLMConfigError(f"등록되지 않은 제공자입니다: {spec.provider!r}")
            self._adapters[key] = factory(spec, self.settings)
        return self._adapters[key]

    def client_for(self, call_type: str, *, on_usage: UsageCallback | None = None) -> LLMClient:
        """호출 종류에 맞는 `LLMClient`. `on_usage`는 서비스가 client_id·decision_id를 묶어 넘기는 호출별 훅."""
        if call_type not in CALL_TYPES:
            raise LLMConfigError(f"알 수 없는 호출 종류입니다: {call_type!r}")
        return _RoutedClient(self, call_type, on_usage)

    def _emit(self, usage: LLMUsage, extra: UsageCallback | None) -> None:
        for hook in (self._on_usage, extra):
            if hook is None:
                continue
            try:
                hook(usage)
            except Exception:  # 기록 실패가 사용자 요청을 깨뜨리지 않게 한다
                logger.warning("LLM usage 콜백 실패", exc_info=True)


class _RoutedClient:
    def __init__(self, router: LLMRouter, call_type: str, on_usage: UsageCallback | None) -> None:
        self._router = router
        self._call_type = call_type
        self._on_usage = on_usage

    async def complete_structured(self, schema: type[T], prompt: str, *, max_tokens: int) -> T:
        router = self._router
        last: LLMError | None = None
        for spec in router.settings.candidates_for(self._call_type):
            adapter = router.adapter_for(spec)
            started = time.perf_counter()
            try:
                generated = await adapter.generate(schema, prompt, max_tokens=max_tokens)
            except LLMError as exc:
                last = exc
                failure = LLMUsage(spec.provider, spec.model, 0, 0, _ms(started), False, self._call_type)
                router._emit(failure, self._on_usage)
                continue
            usage = replace(generated.usage, latency_ms=_ms(started), call_type=self._call_type)
            router._emit(usage, self._on_usage)
            return generated.parsed
        raise AIUnavailableError("사용 가능한 모델이 없습니다") from last


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)
