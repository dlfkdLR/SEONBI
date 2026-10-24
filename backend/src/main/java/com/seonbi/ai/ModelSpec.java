package com.seonbi.ai;

import java.util.List;

/** "제공자:모델명" 형식의 모델 지정 (개발기획서 6.4, 11.3). */
public record ModelSpec(String provider, String model) {

  static final List<String> KNOWN_PROVIDERS = List.of("gemini", "openai", "anthropic", "fake");

  /** 첫 ':'에서만 나눈다(모델명에 ':'가 있어도 된다). */
  public static ModelSpec parse(String text) {
    String t = text.strip();
    int i = t.indexOf(':');
    if (i <= 0 || i == t.length() - 1) {
      throw new LlmConfigException("모델 지정 형식은 '제공자:모델명'이어야 합니다: '" + text + "'");
    }
    String provider = t.substring(0, i);
    if (!KNOWN_PROVIDERS.contains(provider)) {
      throw new LlmConfigException(
          "지원하지 않는 제공자입니다: '" + provider + "' (지원: " + String.join(", ", KNOWN_PROVIDERS) + ")");
    }
    return new ModelSpec(provider, t.substring(i + 1));
  }

  @Override
  public String toString() {
    return provider + ":" + model;
  }
}
