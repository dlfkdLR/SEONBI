package com.seonbi.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seonbi.ai.Json;
import java.net.URI;
import java.util.Map;

/**
 * Anthropic 어댑터 (개발기획서 6.4). Messages API에서 출력 스키마를 도구 입력 스키마로 넘기고 그 도구를 반드시 쓰게 해서 구조화 출력을 얻는다. 도구
 * 호출의 input 객체를 JSON 문자열로 바꿔 돌려준다.
 */
public final class AnthropicAdapter extends HttpJsonAdapter {

  private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
  private static final String TOOL = "respond";

  public AnthropicAdapter(String model, String apiKey) {
    this(model, apiKey, HttpTransport.jdk());
  }

  public AnthropicAdapter(String model, String apiKey, HttpTransport transport) {
    super(model, apiKey, transport);
  }

  @Override
  public String provider() {
    return "anthropic";
  }

  @Override
  URI uri() {
    return ENDPOINT;
  }

  @Override
  Map<String, String> headers(String apiKey) {
    return Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01");
  }

  @Override
  ObjectNode requestBody(Class<?> schema, String prompt, int maxTokens) {
    ObjectNode body = Json.MAPPER.createObjectNode();
    body.put("model", model());
    body.put("max_tokens", maxTokens);
    ObjectNode message = body.putArray("messages").addObject();
    message.put("role", "user");
    message.put("content", prompt);
    ObjectNode tool = body.putArray("tools").addObject();
    tool.put("name", TOOL);
    tool.put("description", "지정한 스키마로 결과를 돌려준다");
    tool.set("input_schema", JsonSchemas.generic(schema));
    ObjectNode choice = body.putObject("tool_choice");
    choice.put("type", "tool");
    choice.put("name", TOOL);
    return body;
  }

  @Override
  String extractText(JsonNode root) {
    for (JsonNode block : root.path("content")) {
      if ("tool_use".equals(block.path("type").asText())
          && TOOL.equals(block.path("name").asText())) {
        return block.path("input").toString();
      }
    }
    return "";
  }

  @Override
  int[] tokens(JsonNode root) {
    JsonNode usage = root.path("usage");
    return new int[] {usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0)};
  }
}
