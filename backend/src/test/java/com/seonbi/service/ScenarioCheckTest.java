package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seonbi.engine.Criterion;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.Scale;
import com.seonbi.service.QuestionText.CriterionMeta;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioCheckTest {

  private static final CriterionMeta PRICE =
      new CriterionMeta(
          "p",
          "가격",
          Criterion.Type.NUMERIC,
          Criterion.Direction.LOWER_BETTER,
          "만 원",
          null,
          Criterion.InputMode.SCORE,
          null,
          null);
  private static final CriterionMeta PERF =
      new CriterionMeta(
          "q",
          "성능",
          Criterion.Type.RATING,
          Criterion.Direction.HIGHER_BETTER,
          null,
          List.of("낮음", "보통", "높음"),
          Criterion.InputMode.SCORE,
          "A",
          "B");

  private static List<List<ScenarioCheck.Expect>> expectations() {
    ScenarioSpec spec = new ScenarioSpec(0, 1, new double[] {0.5, 0.5}, 20, 1);
    return ScenarioCheck.expectations(spec, List.of(PRICE, PERF));
  }

  @SuppressWarnings("unchecked")
  private static String templateDescription(String side) {
    ScenarioSpec spec = new ScenarioSpec(0, 1, new double[] {0.5, 0.5}, 20, 1);
    Map<String, Object> q =
        QuestionText.buildQuestion(
            spec, List.of(PRICE, PERF), Map.of("p", new Scale(100, 200)), "purchase");
    return (String)
        ((Map<String, Object>) ((Map<String, Object>) q.get("payload")).get(side))
            .get("description");
  }

  // 서버가 만든 템플릿 문장은 항상 자기 자신과 같은 뜻이다
  @Test
  void templateSentencesAreConsistentWithTheirOwnExpectations() {
    assertThat(ScenarioCheck.consistent(expectations().get(0), templateDescription("optionA")))
        .isTrue();
    assertThat(ScenarioCheck.consistent(expectations().get(1), templateDescription("optionB")))
        .isTrue();
  }

  // A와 B 설명을 서로 바꾸거나 좋음·나쁨을 뒤집으면 받지 않는다
  @Test
  void flippedMeaningIsRejected() {
    assertThat(ScenarioCheck.consistent(expectations().get(0), templateDescription("optionB")))
        .isFalse();
    assertThat(ScenarioCheck.consistent(expectations().get(0), "가격이 20만 원 더 나쁘고, 성능이 1단계 더 높아요"))
        .isFalse();
    assertThat(ScenarioCheck.consistent(expectations().get(0), "가격이 20만 원 더 좋고, 성능이 1단계 더 낮아요"))
        .isTrue();
  }

  @Test
  void missingNameOrNumberIsRejected() {
    assertThat(ScenarioCheck.consistent(expectations().get(0), "A안")).isFalse();
    assertThat(ScenarioCheck.consistent(expectations().get(0), "가격이 더 좋고 성능이 더 낮아요")).isFalse();
  }

  // "좋지 않고"처럼 부정하면 글자는 맞아도 뜻이 뒤집히므로 받지 않는다
  @Test
  void negatedSentencesAreRejected() {
    assertThat(
            ScenarioCheck.consistent(expectations().get(0), "가격이 20만 원 더 좋지 않고, 성능이 1단계 더 낮지 않아요"))
        .isFalse();
    assertThat(ScenarioCheck.consistent(expectations().get(0), "가격이 20만 원 더 좋고, 성능이 1단계 더 낮아요"))
        .isTrue();
  }
}
