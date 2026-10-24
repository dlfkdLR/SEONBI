package com.seonbi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seonbi.ai.LlmSchemas.ExtractConditionsOutput;
import com.seonbi.ai.LlmSchemas.ExtractedConstraint;
import com.seonbi.ai.LlmSchemas.ExtractedCriterion;
import com.seonbi.ai.LlmSchemas.ExtractedOption;
import com.seonbi.ai.LlmSchemas.ExtractedPreference;
import com.seonbi.ai.LlmSchemas.RecommendCriteriaOutput;
import com.seonbi.ai.LlmSchemas.RecommendCriterion;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmSchemasTest {

  private static RecommendCriterion rating(List<String> levels, String inputMode) {
    return new RecommendCriterion("성능", "rating", null, null, levels, inputMode, "이유");
  }

  @Test
  void levels는_3에서_7개_각_12자_이하_중복_없음() {
    assertThatThrownBy(() -> rating(List.of("a", "b"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rating(List.of("a", "b", "c", "d", "e", "f", "g", "h"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rating(List.of("a", "b", "일이삼사오육칠팔구십일이삼"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rating(List.of("a", "a", "b"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rating(List.of("낮음", "보통", "높음"), "score").levels()).hasSize(3);
  }

  @Test
  void inputMode는_rating에서만_허용된다() {
    assertThatThrownBy(
            () -> new RecommendCriterion("가격", "numeric", "lower_better", "원", null, "rank", "이유"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rating(null, "rank").inputMode()).isEqualTo("rank");
  }

  @Test
  void 기준_추천은_3개에서_6개다() {
    RecommendCriterion c = rating(null, null);
    assertThatThrownBy(() -> new RecommendCriteriaOutput(List.of(c, c)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecommendCriteriaOutput(List.of(c, c, c, c, c, c, c)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new RecommendCriteriaOutput(List.of(c, c, c)).criteria()).hasSize(3);
  }

  @Test
  void 추출_항목은_type으로_종류를_구분해_읽는다() {
    String json =
        """
        {"relevant": true, "ambiguity": "필수인가요?", "items": [
          {"type": "criterion", "name": "배터리", "criterionType": "rating", "confidence": "high"},
          {"type": "constraint", "kind": "threshold", "criterionId": "c1", "operator": "<=", "value": 150, "confidence": "medium"},
          {"type": "constraint", "kind": "required_tag", "label": "Windows", "confidence": "high"},
          {"type": "option", "name": "그램", "confidence": "low"},
          {"type": "preference", "criterionA": "c1", "criterionB": "c2", "value": -3, "confidence": "high"}
        ]}
        """;
    ExtractConditionsOutput out = Json.parse(ExtractConditionsOutput.class, json);
    assertThat(out.relevant()).isTrue();
    assertThat(out.items())
        .hasSize(5)
        .satisfies(
            items -> {
              assertThat(items.get(0)).isInstanceOf(ExtractedCriterion.class);
              assertThat(items.get(1)).isInstanceOf(ExtractedConstraint.class);
              assertThat(items.get(3)).isInstanceOf(ExtractedOption.class);
              assertThat(((ExtractedPreference) items.get(4)).value()).isEqualTo(-3);
            });
    // 직렬화하면 다시 type이 붙는다
    assertThat(Json.write(out.items().get(3))).contains("\"type\":\"option\"");
  }

  @Test
  void 잘못된_추출_항목은_스키마_위반이다() {
    String[] bad = {
      // threshold인데 값이 없음
      """
      {"relevant": true, "items": [{"type": "constraint", "kind": "threshold", "criterionId": "c1", "confidence": "high"}]}
      """,
      // required_tag인데 label이 없음
      """
      {"relevant": true, "items": [{"type": "constraint", "kind": "required_tag", "confidence": "high"}]}
      """,
      // preference 범위 밖
      """
      {"relevant": true, "items": [{"type": "preference", "criterionA": "a", "criterionB": "b", "value": 9, "confidence": "high"}]}
      """,
      // 알 수 없는 type
      """
      {"relevant": true, "items": [{"type": "other", "name": "x"}]}
      """,
      // confidence 값이 틀림
      """
      {"relevant": true, "items": [{"type": "option", "name": "x", "confidence": "sure"}]}
      """,
      "이건 JSON이 아닙니다"
    };
    for (String json : bad) {
      assertThatThrownBy(() -> Json.parse(ExtractConditionsOutput.class, json))
          .as(json)
          .isInstanceOf(LlmException.SchemaViolation.class);
    }
  }

  @Test
  void 스키마_위반_메시지에_응답_본문이_들어가지_않는다() {
    String secret = "나만-아는-내용-SECRET";
    assertThatThrownBy(
            () -> Json.parse(ExtractConditionsOutput.class, "{\"relevant\": \"" + secret + "\""))
        .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("SECRET"));
  }

  @Test
  void 기본_기준_목록은_6개_카테고리_모두_스키마를_통과한다() {
    for (String category :
        List.of("purchase", "career", "study", "activity", "living", "etc", "알수없음")) {
      List<RecommendCriterion> items = DefaultCriteria.forCategory(category);
      assertThat(items.size()).isBetween(3, 6);
      // 한 번 직렬화했다 읽어도 같은 검증을 통과해야 한다
      Json.parse(RecommendCriteriaOutput.class, Json.write(new RecommendCriteriaOutput(items)));
    }
    // 6.2 표: career는 흥미가 순위 입력 단계형이다
    assertThat(DefaultCriteria.forCategory("career").get(0).inputMode()).isEqualTo("rank");
    assertThat(DefaultCriteria.forCategory("purchase").get(0).name()).isEqualTo("가격");
  }
}
