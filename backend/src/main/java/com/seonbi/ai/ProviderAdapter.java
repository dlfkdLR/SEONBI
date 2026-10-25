package com.seonbi.ai;

import java.util.function.Consumer;

/** 제공자별 호출과 구조화 출력 변환만 담당하는 어댑터 (6.4). */
public interface ProviderAdapter {

  String provider();

  String model();

  <T> Generated<T> generate(Class<T> schema, String prompt, int maxTokens);

  /**
   * 어댑터가 안에서 재시도하면 시도마다 한 줄씩 기록해야 비용이 빠지지 않는다(6.3). true인 어댑터는 {@link #generate(Class, String, int,
   * Consumer)}로 시도 하나가 끝날 때마다 사용량을 알리고, 라우터는 결과를 따로 기록하지 않는다. 기본(false)은 호출 전체를 한 건으로 본다.
   */
  default boolean reportsAttempts() {
    return false;
  }

  /** 시도마다 onAttempt로 사용량(지연 포함, callType은 비움)을 알린다. */
  default <T> Generated<T> generate(
      Class<T> schema, String prompt, int maxTokens, Consumer<LlmUsage> onAttempt) {
    return generate(schema, prompt, maxTokens);
  }

  /** 어댑터 한 번의 성공 결과: 검증된 객체와 사용량(callType은 라우터가 채운다). */
  record Generated<T>(T parsed, LlmUsage usage) {}
}
