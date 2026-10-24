package com.seonbi.ai;

/** 서비스 코드가 아는 유일한 인터페이스 (6.4). 검증된 출력 객체를 돌려준다. 어떤 제공자의 어떤 모델이 응답했는지는 모른다. */
public interface LlmClient {

  <T> T completeStructured(Class<T> schema, String prompt, int maxTokens);
}
