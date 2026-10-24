package com.seonbi.service;

import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmRouter;
import com.seonbi.ai.LlmSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * LLM 설정을 환경변수에서 읽어 {@link LlmRouter}를 만든다 (개발기획서 6.4, 11.3). 서버 시작 시 설정에 적힌 모델의 키가 없으면 오류로 알리고
 * 시작하지 않는다(잘못된 설정을 일찍 발견). 기본값은 {@code fake:default}라 키 없이 돌아간다.
 */
@Configuration
public class LlmConfiguration {

  private static final List<String> KEYS =
      List.of(
          "LLM_MODEL_FAST",
          "LLM_MODEL_QUALITY",
          "LLM_FALLBACK_MODELS",
          "LLM_ROUTES",
          "GEMINI_API_KEY",
          "OPENAI_API_KEY",
          "ANTHROPIC_API_KEY");

  @Bean
  public LlmRouter llmRouter(Environment environment) {
    Map<String, String> env = new LinkedHashMap<>();
    for (String key : KEYS) {
      String value = environment.getProperty(key);
      if (value != null) {
        env.put(key, value);
      }
    }
    LlmRouter router = new LlmRouter(LlmSettings.fromEnv(env));
    router.validateSettings();
    return router;
  }

  @Bean
  public LlmCalls llmCalls(LlmRouter router) {
    return new LlmCalls(router);
  }
}
