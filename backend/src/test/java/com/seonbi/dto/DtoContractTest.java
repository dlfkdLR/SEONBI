package com.seonbi.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.seonbi.ai.Json;
import com.seonbi.dto.AnalysisDtos.ChangeConditionItem;
import com.seonbi.dto.AnalysisDtos.ChangeConditionsResponse;
import com.seonbi.dto.AnalysisDtos.RankingItem;
import com.seonbi.dto.AnalysisDtos.StabilityResponse;
import com.seonbi.dto.AnalysisDtos.WeightChangeCondition;
import com.seonbi.dto.AnalysisDtos.WeightItem;
import com.seonbi.dto.AnalysisDtos.WeightNoThresholdCondition;
import com.seonbi.dto.AnswerDtos.Estimate;
import com.seonbi.dto.QuestionDtos.AnswerValue;
import com.seonbi.dto.QuestionDtos.NextQuestionContinue;
import com.seonbi.dto.QuestionDtos.NextQuestionDone;
import com.seonbi.dto.QuestionDtos.QuestionObject;
import com.seonbi.engine.GoldenFiles;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 7.3 응답 record가 서비스 조각(AnalysisHelpers·QuestionText)이 만드는 키와 정확히 맞는지 확인한다. 명세에 없는 키가 있으면 읽을 때
 * 실패하도록 엄격한 매퍼를 쓰고, 다시 쓴 JSON이 원본과 같아야 한다(키 누락·추가 모두 잡는다).
 */
class DtoContractTest {

  private static final ObjectMapper STRICT =
      Json.MAPPER.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

  private static final JsonNode HELPERS = GoldenFiles.load("analysishelpers");

  private static JsonNode tree(Object o) throws IOException {
    return Json.MAPPER.readTree(Json.write(o));
  }

  // 4와 4.0처럼 JSON 숫자로는 같은 값은 같다고 본다 (double 필드는 4.0으로 나간다)
  private static void assertSameJson(JsonNode actual, JsonNode expected, String where) {
    boolean same =
        actual.equals(
            (a, b) ->
                a.isNumber() && b.isNumber()
                    ? Double.compare(a.asDouble(), b.asDouble())
                    : (a.equals(b) ? 0 : 1),
            expected);
    assertThat(same).as(where + "\nactual:   " + actual + "\nexpected: " + expected).isTrue();
  }

  /** node → record로 읽은 뒤 다시 JSON으로 쓴 것이 원본과 같은지 본다. */
  private static <T> void roundTrip(JsonNode node, Class<T> type, String where) throws IOException {
    T dto = STRICT.treeToValue(node, type);
    assertSameJson(tree(dto), node, where);
  }

  private static <T> void roundTripList(JsonNode array, Class<T> type, String where)
      throws IOException {
    List<T> list =
        STRICT.readValue(
            STRICT.treeAsTokens(array),
            TypeFactory.defaultInstance().constructCollectionType(List.class, type));
    assertSameJson(tree(list), array, where);
  }

  @Test
  void 분석_조각이_7장_응답_record와_맞는다() throws IOException {
    for (JsonNode c : HELPERS.get("cases")) {
      String name = c.get("name").asText();
      roundTrip(c.get("estimate"), Estimate.class, name + " estimate");
      roundTripList(c.get("weightsItems"), WeightItem.class, name + " weights");
      roundTripList(c.get("rankingsItems"), RankingItem.class, name + " rankings");
      roundTrip(c.get("stability"), StabilityResponse.class, name + " stability");
      roundTrip(
          c.get("changeConditions"), ChangeConditionsResponse.class, name + " change-conditions");
    }
  }

  @Test
  void 숨은_선호_조각도_맞는다() throws IOException {
    roundTripList(
        HELPERS.get("hidden").get("payload"),
        AnalysisDtos.HiddenPreference.class,
        "hiddenPreferences");
  }

  @Test
  void 템플릿_질문은_질문_객체_record가_된다() throws IOException {
    JsonNode g = GoldenFiles.load("questiontext");
    int n = 0;
    for (JsonNode c : g.get("cases")) {
      if (!c.get("error").isNull()) {
        continue;
      }
      com.fasterxml.jackson.databind.node.ObjectNode withIds = c.get("result").deepCopy();
      withIds.put("id", "q" + n);
      withIds.put("seq", ++n);
      QuestionObject q = STRICT.treeToValue(withIds, QuestionObject.class);
      assertThat(q.type()).isEqualTo(c.get("result").get("type").asText());
      // 서버가 붙이는 id·seq를 뺀 나머지는 원본과 같아야 한다
      JsonNode back = tree(q);
      assertThat(back.get("title")).isEqualTo(c.get("result").get("title"));
      assertThat(back.get("payload")).isEqualTo(c.get("result").get("payload"));
    }
    assertThat(n).isGreaterThan(5);
  }

  @Test
  void payload가_질문_유형과_맞지_않으면_거절한다() {
    Map<String, Object> sliderPayload =
        Map.of("optionA", Map.of("criterionId", "c1", "label", "가격"));
    assertThatThrownBy(
            () ->
                new QuestionObject(
                    "q1", 1, "slider_pair", List.of("c1", "c2"), "t", sliderPayload, true, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new QuestionObject("q1", 1, "unknown", List.of(), "t", Map.of(), true, true))
        .isInstanceOf(IllegalArgumentException.class);
    // 명세에 형식이 없는 유형은 자유 객체를 허용한다
    assertThat(
            new QuestionObject(
                    "q1", 1, "free_text", List.of(), "t", Map.of("hint", "x"), true, true)
                .payload())
        .isNotEmpty();
  }

  @Test
  void 답변_값은_choice_value_skip_중_정확히_하나다() throws IOException {
    for (var bad :
        List.<Runnable>of(
            () -> new AnswerValue(null, null, null),
            () -> new AnswerValue("A", 1, null),
            () -> new AnswerValue("C", null, null),
            () -> new AnswerValue(null, 6, null),
            () -> new AnswerValue(null, -6, null),
            () -> new AnswerValue(null, null, false))) {
      assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
    }
    // 값이 있는 키 하나만 낸다
    assertThat(Json.write(AnswerValue.ofChoice("A"))).isEqualTo("{\"choice\":\"A\"}");
    assertThat(Json.write(AnswerValue.ofValue(-2))).isEqualTo("{\"value\":-2}");
    assertThat(Json.write(AnswerValue.ofSkip())).isEqualTo("{\"skip\":true}");
    // 요청 본문도 같은 규칙으로 읽는다
    assertThat(STRICT.readValue("{\"value\":3}", AnswerValue.class).value()).isEqualTo(3);
    assertThatThrownBy(() -> STRICT.readValue("{\"value\":3,\"extra\":1}", AnswerValue.class))
        .isInstanceOf(IOException.class);
  }

  @Test
  void 다음_질문_응답은_done_값으로_갈린다() throws IOException {
    Map<String, Object> payload =
        Map.of(
            "optionA",
            Map.of("criterionId", "c1", "label", "가격"),
            "optionB",
            Map.of("criterionId", "c2", "label", "무게"));
    QuestionObject q =
        new QuestionObject("q1", 4, "ab_criteria", List.of("c1", "c2"), "t", payload, true, true);
    JsonNode cont = tree(new NextQuestionContinue(q));
    assertThat(cont.get("done").asBoolean()).isFalse();
    assertThat(cont.has("question")).isTrue();
    assertThat(cont.has("reason")).isFalse();
    JsonNode done = tree(new NextQuestionDone("CONFIDENT"));
    assertThat(done.get("done").asBoolean()).isTrue();
    assertThat(done.get("reason").asText()).isEqualTo("CONFIDENT");
    assertThatThrownBy(() -> new NextQuestionDone("뭔가"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 시각은_ISO_8601_문자열이다() throws IOException {
    var item = new AnalysisDtos.Recommendation("o1", "그램", 0.78);
    assertThat(item.topProbability()).isEqualTo(0.78);
    var answer =
        new AnswerDtos.AnswerObject(
            "a1", "q1", AnswerValue.ofSkip(), true, Instant.parse("2026-10-24T06:30:00Z"));
    JsonNode t = tree(answer);
    assertThat(t.get("createdAt").asText()).isEqualTo("2026-10-24T06:30:00Z");
    assertThat(t.get("isValid").asBoolean()).isTrue();
  }

  @Test
  void 가중치_전환_항목은_임계값_유무로_종류가_갈린다() throws IOException {
    JsonNode items = HELPERS.get("cases").get(0).get("changeConditions").get("items");
    List<ChangeConditionItem> parsed = new ArrayList<>();
    for (JsonNode n : items) {
      parsed.add(STRICT.treeToValue(n, ChangeConditionItem.class));
    }
    assertThat(parsed).anyMatch(i -> i instanceof WeightChangeCondition);
    assertThat(parsed).anyMatch(i -> i instanceof AnalysisDtos.ValueChangeCondition);
    ChangeConditionItem none =
        STRICT.readValue(
            "{\"type\":\"weight\",\"criterionId\":\"c1\",\"threshold\":null,\"text\":\"유지\"}",
            ChangeConditionItem.class);
    assertThat(none).isInstanceOf(WeightNoThresholdCondition.class);
    assertThat(Json.write(none)).contains("\"threshold\":null");
  }
}
