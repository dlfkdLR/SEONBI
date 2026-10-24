package com.seonbi.ai;

/** 6.2의 호출 종류 6가지. 이름은 LLM_ROUTES 환경변수와 ai_usage_logs.call_type에 그대로 쓴다. */
public enum CallType {
  EXTRACT_CONDITIONS("extract_conditions", 400),
  RECOMMEND_CRITERIA("recommend_criteria", 900),
  RECOMMEND_OPTIONS("recommend_options", 500),
  PHRASE_QUESTION("phrase_question", 300),
  SUMMARIZE_OPTIONS("summarize_options", 700),
  EXPLAIN_RESULT("explain_result", 800);

  private final String key;
  private final int maxTokens;

  // 출력 토큰 상한: 추출 400·설명 800은 12.2, 나머지는 스키마 크기에 맞춘 값(스펙에 없음)
  CallType(String key, int maxTokens) {
    this.key = key;
    this.maxTokens = maxTokens;
  }

  public String key() {
    return key;
  }

  public int maxTokens() {
    return maxTokens;
  }

  public static CallType fromKey(String key) {
    for (CallType t : values()) {
      if (t.key.equals(key)) {
        return t;
      }
    }
    throw new IllegalArgumentException("알 수 없는 호출 종류입니다: " + key);
  }
}
