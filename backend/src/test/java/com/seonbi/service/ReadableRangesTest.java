package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seonbi.engine.Criterion;
import com.seonbi.service.QuestionText.CriterionMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadableRangesTest {

  private static CriterionMeta rating(String id, Criterion.InputMode mode) {
    return new CriterionMeta(
        id,
        id,
        Criterion.Type.RATING,
        Criterion.Direction.HIGHER_BETTER,
        null,
        List.of("낮음", "높음"),
        mode,
        "ai학과",
        "소프트웨어개발과");
  }

  @Test
  void optionNameRangesTellWhichEndIsHigher() {
    var out =
        QuestionText.readableRanges(
            List.of(
                rating("score", Criterion.InputMode.SCORE),
                rating("rank", Criterion.InputMode.RANK)));
    assertThat(out.get("score")).isEqualTo("ai학과(낮음)~소프트웨어개발과(높음)");
    assertThat(out.get("rank")).isEqualTo("ai학과(낮은 순위)~소프트웨어개발과(높은 순위)");
  }

  @Test
  void numericCriteriaKeepTheirOwnRange() {
    var numeric = CriterionMeta.of("price", "가격", Criterion.Type.NUMERIC);
    assertThat(QuestionText.readableRanges(List.of(numeric))).isEmpty();
  }
}
