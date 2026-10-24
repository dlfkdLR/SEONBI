package com.seonbi.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** AI 계층이 공유하는 Jackson 설정. 필드 이름은 camelCase 그대로 쓴다(개발기획서 6.4, 7.1). */
public final class Json {

  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          // 시각은 ISO 8601 문자열로 낸다 (7.1)
          .configure(
              com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Json() {}

  /** JSON 문자열을 출력 스키마로 읽는다. 형식이 틀리거나 검증에 실패하면 SchemaViolation이다. */
  public static <T> T parse(Class<T> schema, String text) {
    try {
      return MAPPER.readValue(text, schema);
    } catch (JsonProcessingException e) {
      // 메시지에 응답 본문이 들어갈 수 있어서 원인만 달고 내용은 옮기지 않는다
      throw new LlmException.SchemaViolation("응답이 스키마와 맞지 않습니다", e);
    }
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
