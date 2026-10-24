package com.seonbi.ai;

/** 설정 오류. 서버 시작 시 발견해 멈추기 위한 것이라 {@link LlmException}이 아니다(6.4). */
public class LlmConfigException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LlmConfigException(String message) {
    super(message);
  }
}
