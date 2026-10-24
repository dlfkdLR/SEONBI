package com.seonbi.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seonbi.ai.Json;
import java.net.URI;
import java.util.Map;

/** Gemini 어댑터 (개발기획서 6.4). SDK 없이 REST로 부르고, 구조화 출력은 responseJsonSchema로 요청한다. */
public final class GeminiAdapter extends HttpJsonAdapter {

  private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

  public GeminiAdapter(String model, String apiKey) {
    this(model, apiKey, HttpTransport.jdk());
  }

  public GeminiAdapter(String model, String apiKey, HttpTransport transport) {
    super(model, apiKey, transport);
  }

  @Override
  public String provider() {
    return "gemini";
  }

  @Override
  URI uri() {
    return URI.create(BASE + model() + ":generateContent");
  }

  @Override
  Map<String, String> headers(String apiKey) {
    return Map.of("x-goog-api-key", apiKey);
  }

  @Override
  ObjectNode requestBody(Class<?> schema, String prompt, int maxTokens) {
    ObjectNode body = Json.MAPPER.createObjectNode();
    body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
    ObjectNode config = body.putObject("generationConfig");
    config.put("responseMimeType", "application/json");
    config.set("responseJsonSchema", JsonSchemas.generic(schema));
    config.put("maxOutputTokens", maxTokens);
    return body;
  }

  @Override
  String extractText(JsonNode root) {
    return root.path("candidates")
        .path(0)
        .path("content")
        .path("parts")
        .path(0)
        .path("text")
        .asText("");
  }

  @Override
  int[] tokens(JsonNode root) {
    JsonNode meta = root.path("usageMetadata");
    return new int[] {
      meta.path("promptTokenCount").asInt(0), meta.path("candidatesTokenCount").asInt(0)
    };
  }
}
