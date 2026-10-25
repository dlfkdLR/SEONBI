package com.seonbi.ai;

import com.seonbi.ai.ProviderAdapter.Generated;
import com.seonbi.ai.provider.AnthropicAdapter;
import com.seonbi.ai.provider.FakeAdapter;
import com.seonbi.ai.provider.GeminiAdapter;
import com.seonbi.ai.provider.OpenAiAdapter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 호출 종류마다 모델을 고르고, 실패하면 LLM_FALLBACK_MODELS를 순서대로 시도한다 (개발기획서 6.4, 11.3). 제공자 추가 = 어댑터 클래스 + 레지스트리
 * 항목 + 키 환경변수.
 */
public final class LlmRouter {

  private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

  /** 제공자 이름 → 어댑터 팩토리. */
  @FunctionalInterface
  public interface AdapterFactory {
    ProviderAdapter create(ModelSpec spec, LlmSettings settings);
  }

  private final LlmSettings settings;
  private final Map<String, AdapterFactory> registry;
  private final Consumer<LlmUsage> onUsage;
  private final Map<String, ProviderAdapter> adapters = new HashMap<>();

  public LlmRouter(
      LlmSettings settings, Map<String, AdapterFactory> registry, Consumer<LlmUsage> onUsage) {
    this.settings = settings;
    this.registry = Map.copyOf(registry);
    this.onUsage = onUsage;
  }

  public LlmRouter(LlmSettings settings) {
    this(settings, defaultRegistry(), null);
  }

  public static Map<String, AdapterFactory> defaultRegistry() {
    return Map.of(
        "gemini",
        (spec, s) -> new GeminiAdapter(spec.model(), s.apiKeys().get("gemini")),
        "fake",
        (spec, s) -> new FakeAdapter(spec.model()),
        "openai",
        (spec, s) -> new OpenAiAdapter(spec.model(), s.apiKeys().get("openai")),
        "anthropic",
        (spec, s) -> new AnthropicAdapter(spec.model(), s.apiKeys().get("anthropic")));
  }

  public LlmSettings settings() {
    return settings;
  }

  /** 키 검사 + 설정된 모든 모델의 어댑터를 만들어 본다(구현 안 된 제공자도 시작 시 드러난다). */
  public void validateSettings() {
    settings.validate();
    for (ModelSpec spec : settings.allSpecs()) {
      adapterFor(spec);
    }
  }

  public synchronized ProviderAdapter adapterFor(ModelSpec spec) {
    return adapters.computeIfAbsent(
        spec.toString(),
        k -> {
          AdapterFactory factory = registry.get(spec.provider());
          if (factory == null) {
            throw new LlmConfigException("등록되지 않은 제공자입니다: '" + spec.provider() + "'");
          }
          return factory.create(spec, settings);
        });
  }

  /** 호출 종류에 맞는 LlmClient. extraUsage는 서비스가 clientId·decisionId를 묶어 넘기는 호출별 훅이다. */
  public LlmClient clientFor(CallType call, Consumer<LlmUsage> extraUsage) {
    return new RoutedClient(call, extraUsage);
  }

  public LlmClient clientFor(CallType call) {
    return clientFor(call, null);
  }

  private void emit(LlmUsage usage, Consumer<LlmUsage> extra) {
    for (Consumer<LlmUsage> hook : java.util.Arrays.asList(onUsage, extra)) {
      if (hook == null) {
        continue;
      }
      try {
        hook.accept(usage);
      } catch (RuntimeException e) {
        // 기록 실패가 사용자 요청을 깨뜨리지 않게 한다
        log.warn("LLM usage 콜백 실패", e);
      }
    }
  }

  private final class RoutedClient implements LlmClient {
    private final CallType call;
    private final Consumer<LlmUsage> extraUsage;

    RoutedClient(CallType call, Consumer<LlmUsage> extraUsage) {
      this.call = call;
      this.extraUsage = extraUsage;
    }

    @Override
    public <T> T completeStructured(Class<T> schema, String prompt, int maxTokens) {
      LlmException last = null;
      for (ModelSpec spec : settings.candidatesFor(call)) {
        ProviderAdapter adapter = adapterFor(spec);
        long started = System.nanoTime();
        try {
          if (adapter.reportsAttempts()) {
            // 어댑터가 시도마다(재시도·실패 포함) 알려 주므로 여기서 따로 기록하지 않는다
            return adapter
                .generate(
                    schema,
                    prompt,
                    maxTokens,
                    attempt ->
                        emit(
                            attempt.withLatencyAndCall(attempt.latencyMs(), call.key()),
                            extraUsage))
                .parsed();
          }
          Generated<T> generated = adapter.generate(schema, prompt, maxTokens);
          emit(generated.usage().withLatencyAndCall(ms(started), call.key()), extraUsage);
          return generated.parsed();
        } catch (LlmException e) {
          last = e;
          if (!adapter.reportsAttempts()) {
            // 실패한 시도도 기록하고 다음 모델로 넘어간다
            emit(
                new LlmUsage(spec.provider(), spec.model(), 0, 0, ms(started), false, call.key()),
                extraUsage);
          }
        }
      }
      throw new LlmException.AiUnavailable("사용 가능한 모델이 없습니다", last);
    }
  }

  private static long ms(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }
}
