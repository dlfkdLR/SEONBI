package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.engine.Criterion;
import com.seonbi.engine.Golden;
import com.seonbi.engine.GoldenFiles;
import com.seonbi.engine.QuestionSpec;
import com.seonbi.engine.Scale;
import com.seonbi.service.QuestionText.CriterionMeta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 템플릿 문장이 파이썬 참조 구현의 질문 객체와 글자까지 같은지 확인한다 (5.1, 5.5, 7.3). */
class QuestionTextGoldenTest {

  private static final JsonNode G = GoldenFiles.load("questiontext");

  private static CriterionMeta meta(JsonNode n) {
    List<String> levels = null;
    if (!n.get("levels").isNull()) {
      levels = new ArrayList<>();
      for (JsonNode x : n.get("levels")) {
        levels.add(x.asText());
      }
    }
    return new CriterionMeta(
        n.get("id").asText(),
        n.get("name").asText(),
        Criterion.Type.valueOf(n.get("type").asText().toUpperCase()),
        Criterion.Direction.valueOf(n.get("direction").asText().toUpperCase()),
        n.get("unit").isNull() ? null : n.get("unit").asText(),
        levels,
        Criterion.InputMode.valueOf(n.get("inputMode").asText().toUpperCase()),
        n.get("lowName").isNull() ? null : n.get("lowName").asText(),
        n.get("highName").isNull() ? null : n.get("highName").asText());
  }

  private static Map<String, Scale> scales() {
    // 생성기와 같은 척도 (golden/generate.py)
    return Map.of("c1", new Scale(150, 189), "c2", new Scale(1.19, 1.45), "c5", new Scale(8, 20.5));
  }

  private static List<CriterionMeta> metas(JsonNode c) {
    List<CriterionMeta> criteria = new ArrayList<>();
    for (JsonNode n : c.get("criteria")) {
      criteria.add(meta(n));
    }
    return criteria;
  }

  @Test
  void 질문_객체가_골든과_같다() {
    for (JsonNode c : G.get("cases")) {
      String name = c.get("name").asText();
      QuestionSpec spec = Golden.spec(c.get("spec"));
      List<CriterionMeta> criteria = metas(c);
      Map<String, String> ranges = new LinkedHashMap<>();
      if (!c.get("ranges").isNull()) {
        c.get("ranges")
            .fields()
            .forEachRemaining(e -> ranges.put(e.getKey(), e.getValue().asText()));
      }
      String category = c.get("category").asText();
      if (c.get("error").isNull()) {
        Map<String, Object> actual =
            QuestionText.buildQuestion(spec, criteria, scales(), category, ranges);
        // JSON으로 바꿔 구조와 값(키 순서 제외)을 비교한다
        JsonNode got = com.seonbi.ai.Json.MAPPER.valueToTree(actual);
        assertThat(got).as(name).isEqualTo(c.get("result"));
      } else {
        assertThatThrownBy(
                () -> QuestionText.buildQuestion(spec, criteria, scales(), category, ranges))
            .as(name)
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void 질문_객체의_키_순서는_7장_예시와_같다() {
    JsonNode c = G.get("cases").get(0);
    Map<String, Object> q =
        QuestionText.buildQuestion(Golden.spec(c.get("spec")), metas(c), scales(), "purchase");
    assertThat(q.keySet())
        .containsExactly("type", "criteria", "title", "payload", "allowFreeText", "allowSkip");
  }

  @Test
  void 숫자_표기가_골든과_같다() {
    for (JsonNode n : G.get("formatNumber")) {
      assertThat(QuestionText.formatNumber(n.get("x").asDouble()))
          .as("%s", n.get("x"))
          .isEqualTo(n.get("text").asText());
    }
    assertThatThrownBy(() -> QuestionText.formatNumber(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 카테고리별_명사가_같다() {
    G.get("nouns")
        .fields()
        .forEachRemaining(
            e ->
                assertThat(QuestionText.nounForCategory(e.getKey()))
                    .isEqualTo(e.getValue().asText()));
  }
}
