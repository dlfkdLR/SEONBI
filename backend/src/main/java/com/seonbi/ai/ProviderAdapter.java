package com.seonbi.ai;

/** 제공자별 호출과 구조화 출력 변환만 담당하는 어댑터 (6.4). */
public interface ProviderAdapter {

  String provider();

  String model();

  <T> Generated<T> generate(Class<T> schema, String prompt, int maxTokens);

  /** 어댑터 한 번의 성공 결과: 검증된 객체와 사용량(callType은 라우터가 채운다). */
  record Generated<T>(T parsed, LlmUsage usage) {}
}
