package com.seonbi.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 11.3의 LLM 설정. apiKeys는 제공자 → 키이며 toString에 나오지 않는다.
 *
 * @param routes 호출 종류 → "제공자:모델명" (LLM_ROUTES)
 */
public record LlmSettings(
    String modelFast,
    String modelQuality,
    List<String> fallbackModels,
    Map<CallType, String> routes,
    Map<String, String> apiKeys) {

  public static final String DEFAULT_MODEL = "fake:default";
  // 키가 필요한 제공자 → 환경변수 이름 (fake는 키 불필요)
  static final Map<String, String> KEY_ENV =
      Map.of(
          "gemini", "GEMINI_API_KEY", "openai", "OPENAI_API_KEY", "anthropic", "ANTHROPIC_API_KEY");

  public LlmSettings {
    fallbackModels = fallbackModels == null ? List.of() : List.copyOf(fallbackModels);
    routes = routes == null ? Map.of() : Map.copyOf(routes);
    apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
  }

  public static LlmSettings defaults() {
    return new LlmSettings(DEFAULT_MODEL, DEFAULT_MODEL, List.of(), Map.of(), Map.of());
  }

  private static List<String> split(String value) {
    List<String> out = new ArrayList<>();
    if (value == null) {
      return out;
    }
    Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(out::add);
    return out;
  }

  private static String orDefault(String v) {
    return v == null || v.isBlank() ? DEFAULT_MODEL : v.strip();
  }

  public static LlmSettings fromEnv(Map<String, String> env) {
    Map<CallType, String> routes = new LinkedHashMap<>();
    for (String entry : split(env.get("LLM_ROUTES"))) {
      int i = entry.indexOf('=');
      String call = i < 0 ? entry : entry.substring(0, i).strip();
      CallType type;
      try {
        type = CallType.fromKey(call);
      } catch (IllegalArgumentException e) {
        throw new LlmConfigException("LLM_ROUTES 항목 형식 오류(호출=제공자:모델): '" + call + "'");
      }
      if (i < 0) {
        throw new LlmConfigException("LLM_ROUTES 항목 형식 오류(호출=제공자:모델): '" + call + "'");
      }
      routes.put(type, entry.substring(i + 1).strip());
    }
    Map<String, String> keys = new LinkedHashMap<>();
    KEY_ENV.forEach(
        (provider, name) -> {
          String v = env.get(name);
          if (v != null && !v.isBlank()) {
            keys.put(provider, v.strip());
          }
        });
    return new LlmSettings(
        orDefault(env.get("LLM_MODEL_FAST")),
        orDefault(env.get("LLM_MODEL_QUALITY")),
        split(env.get("LLM_FALLBACK_MODELS")),
        routes,
        keys);
  }

  /** 호출 종류의 기본 모델: LLM_ROUTES &gt; (explain_result면 QUALITY, 아니면 FAST). 6.4: 결과 설명만 QUALITY다. */
  public ModelSpec primaryFor(CallType call) {
    if (routes.containsKey(call)) {
      return ModelSpec.parse(routes.get(call));
    }
    return ModelSpec.parse(call == CallType.EXPLAIN_RESULT ? modelQuality : modelFast);
  }

  /** 시도 순서: 기본 모델 → 대체 모델들(중복 제거). */
  public List<ModelSpec> candidatesFor(CallType call) {
    LinkedHashSet<ModelSpec> ordered = new LinkedHashSet<>();
    ordered.add(primaryFor(call));
    fallbackModels.forEach(m -> ordered.add(ModelSpec.parse(m)));
    return List.copyOf(ordered);
  }

  public List<ModelSpec> allSpecs() {
    LinkedHashSet<ModelSpec> out = new LinkedHashSet<>();
    out.add(ModelSpec.parse(modelFast));
    out.add(ModelSpec.parse(modelQuality));
    fallbackModels.forEach(m -> out.add(ModelSpec.parse(m)));
    routes.values().forEach(m -> out.add(ModelSpec.parse(m)));
    return List.copyOf(out);
  }

  /** 서버 시작 시 호출: 형식 오류나 제공자 키 누락이면 LlmConfigException(6.4). 키 값은 쓰지 않는다. */
  public void validate() {
    for (ModelSpec spec : allSpecs()) {
      String env = KEY_ENV.get(spec.provider());
      if (env != null && !apiKeys.containsKey(spec.provider())) {
        throw new LlmConfigException(spec + " 모델을 쓰려면 " + env + "가 필요합니다");
      }
    }
  }

  @Override
  public String toString() {
    // 키 값이 로그에 남지 않게 제공자 이름만 보인다
    return "LlmSettings[fast="
        + modelFast
        + ", quality="
        + modelQuality
        + ", fallback="
        + fallbackModels
        + ", routes="
        + routes
        + ", keys="
        + apiKeys.keySet()
        + "]";
  }
}
