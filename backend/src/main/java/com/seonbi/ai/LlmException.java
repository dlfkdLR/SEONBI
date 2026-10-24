package com.seonbi.ai;

/**
 * 호출 중 발생하는 LLM 오류의 부모 (개발기획서 6.2, 6.3, 6.4). 라우터의 대체 모델 시도 대상이다. 오류 메시지에는 프롬프트·사용자 원문을 넣지 않는다(8.1,
 * 개인정보 최소화).
 */
public class LlmException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LlmException(String message) {
    super(message);
  }

  public LlmException(String message, Throwable cause) {
    super(message, cause);
  }

  /** 제공자 호출 실패(네트워크·타임아웃·4xx/5xx). retryable은 5xx처럼 한 번 더 시도할 만한 경우다. */
  public static class ProviderError extends LlmException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public ProviderError(String message, boolean retryable) {
      super(message);
      this.retryable = retryable;
    }

    public ProviderError(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }
  }

  /** 응답이 JSON이 아니거나 출력 스키마 검증에 실패했다(6.3). */
  public static class SchemaViolation extends LlmException {
    private static final long serialVersionUID = 1L;

    public SchemaViolation(String message) {
      super(message);
    }

    public SchemaViolation(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** 모든 모델이 실패했거나 fallback이 없는 호출이 실패했다. 서비스가 503 AI_UNAVAILABLE로 옮긴다(7.1). */
  public static class AiUnavailable extends LlmException {
    private static final long serialVersionUID = 1L;

    public AiUnavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
