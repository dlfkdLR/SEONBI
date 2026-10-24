package com.seonbi.ai.provider;

import com.seonbi.ai.DefaultCriteria;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmException;
import com.seonbi.ai.LlmSchemas.ExplainResultOutput;
import com.seonbi.ai.LlmSchemas.ExtractConditionsOutput;
import com.seonbi.ai.LlmSchemas.ExtractedConstraint;
import com.seonbi.ai.LlmSchemas.OptionProsCons;
import com.seonbi.ai.LlmSchemas.PhraseQuestionOutput;
import com.seonbi.ai.LlmSchemas.RecommendCriteriaOutput;
import com.seonbi.ai.LlmSchemas.RecommendOptionsOutput;
import com.seonbi.ai.LlmSchemas.RecommendedOption;
import com.seonbi.ai.LlmSchemas.SummarizeOptionsOutput;
import com.seonbi.ai.LlmUsage;
import com.seonbi.ai.ProviderAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결정적 Fake 어댑터 (개발기획서 6.4: 테스트·로컬 기본, 키 불필요). 스키마 타입마다 고정된 유효 응답을 돌려준다. 테스트는 script로 응답·오류·잘못된
 * JSON을 순서대로 주입할 수 있다. canned 응답은 프롬프트의 user_input 블록을 읽지 않는다 → 사용자 원문이 출력을 바꾸지 못한다.
 */
public final class FakeAdapter implements ProviderAdapter {

  private static final Pattern USER_BLOCK =
      Pattern.compile("<user_input>.*?</user_input>", Pattern.DOTALL);
  private static final Pattern CATEGORY = Pattern.compile("카테고리: *(\\w+)");
  private static final Pattern SERVER_DATA =
      Pattern.compile("<server_data>(.*?)</server_data>", Pattern.DOTALL);
  private static final Pattern OPTION_ID = Pattern.compile("\"optionId\"\\s*:\\s*\"([^\"]+)\"");

  /** 한 번의 호출 기록 (테스트 검사용). */
  public record Call(Class<?> schema, String prompt, int maxTokens) {}

  private final String model;
  // 스크립트 한 칸: 출력 객체 | 원본 JSON 문자열 | 던질 예외
  private final Deque<Object> script = new ArrayDeque<>();
  private final List<Call> calls = new ArrayList<>();

  public FakeAdapter(String model) {
    this.model = model;
  }

  public FakeAdapter push(Object... items) {
    script.addAll(List.of(items));
    return this;
  }

  public List<Call> calls() {
    return calls;
  }

  @Override
  public String provider() {
    return "fake";
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public <T> Generated<T> generate(Class<T> schema, String prompt, int maxTokens) {
    calls.add(new Call(schema, prompt, maxTokens));
    Object item = script.pollFirst();
    if (item instanceof RuntimeException e) {
      throw e;
    }
    T parsed = toModel(schema, item, prompt);
    return new Generated<>(parsed, new LlmUsage("fake", model, 0, 0, 0, true, ""));
  }

  private <T> T toModel(Class<T> schema, Object item, String prompt) {
    if (item == null) {
      // canned 응답도 JSON으로 한 번 돌려서 실제 응답과 같은 검증을 거치게 한다
      return Json.parse(schema, Json.write(canned(schema, prompt)));
    }
    if (item instanceof String raw) {
      return Json.parse(schema, raw);
    }
    if (schema.isInstance(item)) {
      return schema.cast(item);
    }
    throw new LlmException.SchemaViolation("스크립트 응답이 요청한 스키마와 다릅니다");
  }

  private static Object canned(Class<?> schema, String prompt) {
    String visible = USER_BLOCK.matcher(prompt).replaceAll(""); // 사용자 원문 블록은 읽지 않는다
    if (schema == ExtractConditionsOutput.class) {
      return new ExtractConditionsOutput(
          true,
          List.of(
              new ExtractedConstraint(
                  "required_tag", null, null, null, "Windows 지원", null, "high")),
          null);
    }
    if (schema == RecommendCriteriaOutput.class) {
      Matcher m = CATEGORY.matcher(visible);
      return new RecommendCriteriaOutput(
          DefaultCriteria.forCategory(m.find() ? m.group(1) : "etc"));
    }
    if (schema == RecommendOptionsOutput.class) {
      return new RecommendOptionsOutput(
          List.of("A", "B", "C").stream()
              .map(c -> new RecommendedOption("추천 후보 " + c, "목적에 맞는 후보"))
              .toList());
    }
    if (schema == PhraseQuestionOutput.class) {
      return new PhraseQuestionOutput(
          "두 가지 중 어느 쪽이 더 마음에 드세요?", "첫 번째 선택지의 조건입니다", "두 번째 선택지의 조건입니다", "왼쪽 기준", "오른쪽 기준");
    }
    if (schema == SummarizeOptionsOutput.class) {
      Matcher data = SERVER_DATA.matcher(prompt);
      LinkedHashSet<String> ids = new LinkedHashSet<>();
      if (data.find()) {
        Matcher m = OPTION_ID.matcher(data.group(1));
        while (m.find()) {
          ids.add(m.group(1));
        }
      }
      return new SummarizeOptionsOutput(
          ids.stream()
              .map(
                  id ->
                      new OptionProsCons(
                          id, List.of("입력한 값 기준으로 비교해 보세요"), List.of("표의 값을 함께 확인해 보세요")))
              .toList());
    }
    if (schema == ExplainResultOutput.class) {
      return new ExplainResultOutput(
          "입력한 기준과 답변을 바탕으로 계산한 결과입니다.",
          List.of("중요하게 답한 기준에서 앞선 선택지가 상위에 올랐습니다."),
          List.of("답변이 바뀌면 결과도 달라질 수 있습니다."));
    }
    throw new LlmException.SchemaViolation("Fake 어댑터가 모르는 스키마: " + schema.getSimpleName());
  }
}
