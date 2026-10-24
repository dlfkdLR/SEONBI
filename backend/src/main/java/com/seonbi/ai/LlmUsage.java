package com.seonbi.ai;

/** ai_usage_logs 한 줄에 대응하는 기록. 프롬프트·응답 본문·사용자 입력은 담지 않는다(8.1). */
public record LlmUsage(
    String provider,
    String model,
    int inputTokens,
    int outputTokens,
    long latencyMs,
    boolean success,
    String callType) {

  public LlmUsage withLatencyAndCall(long latencyMs, String callType) {
    return new LlmUsage(provider, model, inputTokens, outputTokens, latencyMs, success, callType);
  }
}
