package com.seonbi.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmConfigException;
import com.seonbi.ai.LlmException;
import com.seonbi.ai.LlmUsage;
import com.seonbi.ai.ProviderAdapter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP로 JSON을 주고받는 제공자 어댑터의 공통 뼈대 (개발기획서 6.4). 제공자마다 다른 것(주소·헤더·요청 모양·응답에서 글 꺼내기)만 하위 클래스가 채운다.
 *
 * <ul>
 *   <li>타임아웃 10초. 재시도는 스키마 위반·5xx에 한해 1회만(6.3, 6.4). 타임아웃·4xx는 재시도하지 않는다.
 *   <li>오류 메시지에는 프롬프트·응답 본문·키를 넣지 않는다.
 * </ul>
 */
abstract class HttpJsonAdapter implements ProviderAdapter {

  static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final String model;
  private final String apiKey;
  private final HttpTransport transport;

  HttpJsonAdapter(String model, String apiKey, HttpTransport transport) {
    this.model = model;
    this.apiKey = apiKey;
    this.transport = transport;
  }

  @Override
  public String model() {
    return model;
  }

  /** 요청 주소. */
  abstract URI uri();

  /** 인증 등 제공자별 헤더. 키는 URL이 아니라 헤더로만 보낸다. */
  abstract Map<String, String> headers(String apiKey);

  /** 구조화 출력 요청 본문. */
  abstract ObjectNode requestBody(Class<?> schema, String prompt, int maxTokens);

  /** 응답에서 모델이 만든 JSON 문자열을 꺼낸다. 없으면 SchemaViolation. */
  abstract String extractText(JsonNode root);

  /** 응답의 토큰 수. */
  abstract int[] tokens(JsonNode root);

  @Override
  public <T> Generated<T> generate(Class<T> schema, String prompt, int maxTokens) {
    LlmException last = null;
    for (int attempt = 0; attempt < 2; attempt++) { // 최초 1회 + 재시도 1회
      try {
        return once(schema, prompt, maxTokens);
      } catch (LlmException.SchemaViolation e) {
        last = e;
      } catch (LlmException.ProviderError e) {
        if (!e.retryable()) {
          throw e;
        }
        last = e;
      }
    }
    throw last;
  }

  private <T> Generated<T> once(Class<T> schema, String prompt, int maxTokens) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new LlmConfigException(provider() + " API 키가 없습니다");
    }
    HttpTransport.Result result;
    try {
      result =
          transport.post(
              uri(), headers(apiKey), requestBody(schema, prompt, maxTokens).toString(), TIMEOUT);
    } catch (HttpTimeoutException e) {
      throw new LlmException.ProviderError(provider() + " 응답 시간 초과", false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException.ProviderError(provider() + " 호출이 중단되었습니다", false);
    } catch (IOException e) {
      // 원인 메시지에 주소가 들어갈 수 있어 종류만 남긴다
      throw new LlmException.ProviderError(
          provider() + " 호출 실패 (" + e.getClass().getSimpleName() + ")", false);
    }
    if (result.status() >= 400) {
      throw new LlmException.ProviderError(
          provider() + " 호출 실패 (status=" + result.status() + ")", result.status() >= 500);
    }
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(result.body());
    } catch (IOException e) {
      throw new LlmException.SchemaViolation(provider() + " 응답이 JSON이 아닙니다");
    }
    String text = extractText(root);
    if (text == null || text.isBlank()) {
      throw new LlmException.SchemaViolation(provider() + " 응답이 비어 있습니다");
    }
    T parsed = Json.parse(schema, text); // 같은 record 스키마로 재검증한다 (6.4 공통 검증)
    int[] t = tokens(root);
    // 실제 지연과 호출 종류는 라우터가 채운다
    return new Generated<>(parsed, new LlmUsage(provider(), model, t[0], t[1], 0, true, ""));
  }
}
