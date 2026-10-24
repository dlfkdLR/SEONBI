package com.seonbi.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmException;
import java.net.URI;
import java.util.Map;

/** OpenAI 어댑터 (개발기획서 6.4). Chat Completions의 response_format=json_schema(strict)로 구조화 출력을 요청한다. */
public final class OpenAiAdapter extends HttpJsonAdapter {

  private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");

  public OpenAiAdapter(String model, String apiKey) {
    this(model, apiKey, HttpTransport.jdk());
  }

  public OpenAiAdapter(String model, String apiKey, HttpTransport transport) {
    super(model, apiKey, transport);
  }

  @Override
  public String provider() {
    return "openai";
  }

  @Override
  URI uri() {
    return ENDPOINT;
  }

  @Override
  Map<String, String> headers(String apiKey) {
    return Map.of("Authorization", "Bearer " + apiKey);
  }

  @Override
  ObjectNode requestBody(Class<?> schema, String prompt, int maxTokens) {
    ObjectNode body = Json.MAPPER.createObjectNode();
    body.put("model", model());
    body.put("max_completion_tokens", maxTokens);
    ObjectNode message = body.putArray("messages").addObject();
    message.put("role", "user");
    message.put("content", prompt);
    ObjectNode format = body.putObject("response_format");
    format.put("type", "json_schema");
    ObjectNode js = format.putObject("json_schema");
    js.put("name", "response");
    js.put("strict", true);
    js.set("schema", JsonSchemas.strict(schema));
    return body;
  }

  @Override
  String extractText(JsonNode root) {
    JsonNode message = root.path("choices").path(0).path("message");
    if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull()) {
      // 모델이 답을 거절한 경우. 거절 문구는 노출하지 않는다
      throw new LlmException.SchemaViolation("openai 모델이 응답을 거절했습니다");
    }
    return message.path("content").asText("");
  }

  @Override
  int[] tokens(JsonNode root) {
    JsonNode usage = root.path("usage");
    return new int[] {
      usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0)
    };
  }
}
