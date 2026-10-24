package com.seonbi.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmConfigException;
import com.seonbi.ai.LlmException;
import com.seonbi.ai.LlmSchemas.ExtractConditionsOutput;
import com.seonbi.ai.LlmSchemas.PhraseQuestionOutput;
import com.seonbi.ai.ProviderAdapter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 제공자 어댑터 3종의 요청 변환과 응답 해석 (개발기획서 6.4). 응답은 각 제공자 API 문서의 응답 모양을 본뜬 고정 값이다. 실제 키로 녹화한 응답은 키가 .env에
 * 들어간 뒤 같은 자리에 추가한다.
 */
class ProviderAdaptersTest {

  /** 요청을 기록하고, 미리 넣어 둔 응답·오류를 차례로 돌려주는 가짜 전송 계층. */
  static final class StubTransport implements HttpTransport {
    record Sent(URI uri, Map<String, String> headers, String body) {}

    final List<Sent> sent = new ArrayList<>();
    private final Deque<Object> replies = new ArrayDeque<>();

    StubTransport reply(int status, String body) {
      replies.add(new Result(status, body));
      return this;
    }

    StubTransport fail(Exception e) {
      replies.add(e);
      return this;
    }

    @Override
    public Result post(URI uri, Map<String, String> headers, String jsonBody, Duration timeout)
        throws IOException {
      sent.add(new Sent(uri, headers, jsonBody));
      Object next = replies.poll();
      if (next instanceof IOException io) {
        throw io;
      }
      return (Result) next;
    }
  }

  private static final String PHRASE_JSON =
      "{\"title\":\"어느 쪽이 나은가요?\",\"optionA\":\"가격\",\"optionB\":null}";

  private static String geminiBody(String text) {
    return Json.write(
        Map.of(
            "candidates",
                List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", text))))),
            "usageMetadata", Map.of("promptTokenCount", 12, "candidatesTokenCount", 7)));
  }

  private static String openAiBody(String content) {
    return Json.write(
        Map.of(
            "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))),
            "usage", Map.of("prompt_tokens", 20, "completion_tokens", 9)));
  }

  private static String anthropicBody(String inputJson) {
    return "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"tool_use\",\"name\":\"respond\",\"input\":"
        + inputJson
        + "}],\"usage\":{\"input_tokens\":31,\"output_tokens\":11}}";
  }

  // ── Gemini ────────────────────────────────────────────────────

  @Test
  void gemini_요청은_JSON_스키마를_담고_키는_헤더로만_보낸다() throws Exception {
    StubTransport t = new StubTransport().reply(200, geminiBody(PHRASE_JSON));
    GeminiAdapter adapter = new GeminiAdapter("gemini-2.5-flash", "SECRET-KEY", t);

    ProviderAdapter.Generated<PhraseQuestionOutput> out =
        adapter.generate(PhraseQuestionOutput.class, "프롬프트", 300);

    assertThat(out.parsed().title()).isEqualTo("어느 쪽이 나은가요?");
    assertThat(out.usage().inputTokens()).isEqualTo(12);
    assertThat(out.usage().outputTokens()).isEqualTo(7);
    StubTransport.Sent s = t.sent.get(0);
    assertThat(s.uri().toString())
        .endsWith("/gemini-2.5-flash:generateContent")
        .doesNotContain("SECRET-KEY");
    assertThat(s.headers()).containsEntry("x-goog-api-key", "SECRET-KEY");
    JsonNode body = Json.MAPPER.readTree(s.body());
    assertThat(body.at("/generationConfig/responseMimeType").asText())
        .isEqualTo("application/json");
    assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(300);
    assertThat(body.at("/generationConfig/responseJsonSchema/properties").has("title")).isTrue();
    assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo("프롬프트");
  }

  // ── OpenAI ────────────────────────────────────────────────────

  @Test
  void openai_요청은_strict_json_schema를_쓰고_모든_필드가_required다() throws Exception {
    StubTransport t = new StubTransport().reply(200, openAiBody(PHRASE_JSON));
    OpenAiAdapter adapter = new OpenAiAdapter("gpt-4.1-mini", "k", t);

    ProviderAdapter.Generated<PhraseQuestionOutput> out =
        adapter.generate(PhraseQuestionOutput.class, "p", 300);

    assertThat(out.parsed().optionA()).isEqualTo("가격");
    assertThat(out.usage().provider()).isEqualTo("openai");
    assertThat(out.usage().inputTokens()).isEqualTo(20);
    StubTransport.Sent s = t.sent.get(0);
    assertThat(s.headers()).containsEntry("Authorization", "Bearer k");
    JsonNode body = Json.MAPPER.readTree(s.body());
    assertThat(body.get("model").asText()).isEqualTo("gpt-4.1-mini");
    assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
    assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isTrue();
    JsonNode schema = body.at("/response_format/json_schema/schema");
    // strict: 속성이 전부 required이고 추가 속성은 금지
    assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    List<String> props = new ArrayList<>();
    schema.get("properties").fieldNames().forEachRemaining(props::add);
    List<String> required = new ArrayList<>();
    schema.get("required").forEach(n -> required.add(n.asText()));
    assertThat(required).containsExactlyInAnyOrderElementsOf(props);
  }

  @Test
  void openai가_답을_거절하면_스키마_위반이다() {
    String refusal =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":\"안 됩니다\"}}]}";
    OpenAiAdapter adapter =
        new OpenAiAdapter("m", "k", new StubTransport().reply(200, refusal).reply(200, refusal));
    assertThatThrownBy(() -> adapter.generate(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOf(LlmException.SchemaViolation.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("안 됩니다"));
  }

  // ── Anthropic ─────────────────────────────────────────────────

  @Test
  void anthropic_요청은_도구_입력_스키마로_구조화_출력을_받는다() throws Exception {
    StubTransport t = new StubTransport().reply(200, anthropicBody(PHRASE_JSON));
    AnthropicAdapter adapter = new AnthropicAdapter("claude-haiku-4-5", "k", t);

    ProviderAdapter.Generated<PhraseQuestionOutput> out =
        adapter.generate(PhraseQuestionOutput.class, "p", 300);

    assertThat(out.parsed().title()).isEqualTo("어느 쪽이 나은가요?");
    assertThat(out.usage().outputTokens()).isEqualTo(11);
    StubTransport.Sent s = t.sent.get(0);
    assertThat(s.headers()).containsEntry("x-api-key", "k").containsKey("anthropic-version");
    JsonNode body = Json.MAPPER.readTree(s.body());
    assertThat(body.at("/tool_choice/type").asText()).isEqualTo("tool");
    assertThat(body.at("/tools/0/name").asText()).isEqualTo(body.at("/tool_choice/name").asText());
    assertThat(body.at("/tools/0/input_schema/type").asText()).isEqualTo("object");
    assertThat(body.get("max_tokens").asInt()).isEqualTo(300);
  }

  @Test
  void anthropic_응답에_도구_호출이_없으면_스키마_위반이다() {
    String noTool = "{\"content\":[{\"type\":\"text\",\"text\":\"그냥 말만\"}]}";
    AnthropicAdapter adapter =
        new AnthropicAdapter("m", "k", new StubTransport().reply(200, noTool).reply(200, noTool));
    assertThatThrownBy(() -> adapter.generate(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOf(LlmException.SchemaViolation.class);
  }

  // ── 공통: 재시도 규칙, 오류 메시지 ─────────────────────────────

  @Test
  void 스키마_위반은_한_번만_재시도하고_성공하면_돌려준다() {
    StubTransport t =
        new StubTransport().reply(200, geminiBody("{}")).reply(200, geminiBody(PHRASE_JSON));
    assertThat(
            new GeminiAdapter("m", "k", t)
                .generate(PhraseQuestionOutput.class, "p", 10)
                .parsed()
                .title())
        .isNotBlank();
    assertThat(t.sent).hasSize(2);
  }

  @Test
  void 스키마_위반이_두_번_이어지면_재시도는_딱_한_번이다() {
    StubTransport t =
        new StubTransport()
            .reply(200, geminiBody("{}"))
            .reply(200, geminiBody("{}"))
            .reply(200, geminiBody(PHRASE_JSON));
    assertThatThrownBy(
            () -> new GeminiAdapter("m", "k", t).generate(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOf(LlmException.SchemaViolation.class);
    assertThat(t.sent).hasSize(2);
  }

  @Test
  void 서버_오류_5xx는_한_번_재시도한다() {
    StubTransport t = new StubTransport().reply(503, "busy").reply(200, openAiBody(PHRASE_JSON));
    assertThat(
            new OpenAiAdapter("m", "k", t).generate(PhraseQuestionOutput.class, "p", 10).parsed())
        .isNotNull();
    assertThat(t.sent).hasSize(2);
  }

  @Test
  void 클라이언트_오류_4xx와_타임아웃은_재시도하지_않는다() {
    StubTransport bad = new StubTransport().reply(401, "no").reply(200, geminiBody(PHRASE_JSON));
    assertThatThrownBy(
            () -> new GeminiAdapter("m", "k", bad).generate(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOfSatisfying(
            LlmException.ProviderError.class, e -> assertThat(e.retryable()).isFalse());
    assertThat(bad.sent).hasSize(1);

    StubTransport slow =
        new StubTransport()
            .fail(new HttpTimeoutException("timeout"))
            .reply(200, geminiBody(PHRASE_JSON));
    assertThatThrownBy(
            () -> new GeminiAdapter("m", "k", slow).generate(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOf(LlmException.ProviderError.class);
    assertThat(slow.sent).hasSize(1);
  }

  @Test
  void 오류_메시지에_프롬프트_응답_본문_키가_들어가지_않는다() {
    String secret = "SECRET-PROMPT";
    StubTransport t = new StubTransport().reply(400, "SECRET-BODY " + secret);
    assertThatThrownBy(
            () ->
                new AnthropicAdapter("m", "SECRET-KEY", t)
                    .generate(PhraseQuestionOutput.class, secret, 10))
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
    StubTransport io = new StubTransport().fail(new IOException("https://x?key=SECRET-KEY"));
    assertThatThrownBy(
            () ->
                new OpenAiAdapter("m", "SECRET-KEY", io)
                    .generate(PhraseQuestionOutput.class, secret, 10))
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
  }

  @Test
  void 키가_없으면_설정_오류다() {
    for (ProviderAdapter a :
        List.of(
            new GeminiAdapter("m", null),
            new OpenAiAdapter("m", ""),
            new AnthropicAdapter("m", " "))) {
      assertThatThrownBy(() -> a.generate(PhraseQuestionOutput.class, "p", 10))
          .isInstanceOf(LlmConfigException.class);
    }
  }

  // ── 스키마 생성 ───────────────────────────────────────────────

  @Test
  void 추출_항목처럼_종류가_갈리는_스키마도_제공자_요청에_실린다() throws Exception {
    StubTransport t =
        new StubTransport().reply(200, anthropicBody("{\"relevant\":false,\"items\":[]}"));
    new AnthropicAdapter("m", "k", t).generate(ExtractConditionsOutput.class, "p", 400);
    JsonNode schema = Json.MAPPER.readTree(t.sent.get(0).body()).at("/tools/0/input_schema");
    String text = schema.toString();
    // 네 가지 항목 이름이 모두 스키마에 드러나야 모델이 형식을 알 수 있다
    for (String kind : List.of("criterion", "constraint", "option", "preference")) {
      assertThat(text).as(kind).contains("\"" + kind + "\"");
    }
  }
}
