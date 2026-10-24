package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class HiddenPreferenceGoldenTest {

  @Test
  void 숨은_선호_판정이_골든과_같다() {
    JsonNode g = Golden.load("hidden");
    List<HiddenPreference.Mismatch> actual =
        HiddenPreference.detect(Golden.mat(g.get("W")), Golden.answers(g.get("answers")));
    JsonNode expected = g.get("mismatches");
    assertThat(actual).hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      JsonNode e = expected.get(i);
      assertThat(actual.get(i).statedOver()).isEqualTo(e.get("statedOver").asInt());
      assertThat(actual.get(i).revealedOver()).isEqualTo(e.get("revealedOver").asInt());
      assertThat(actual.get(i).statedP())
          .isCloseTo(e.get("statedP").asDouble(), offset(Golden.TOL));
      assertThat(actual.get(i).revealedP())
          .isCloseTo(e.get("revealedP").asDouble(), offset(Golden.TOL));
      assertThat(actual.get(i).nScenarios()).isEqualTo(e.get("nScenarios").asInt());
    }
  }

  @Test
  void 상황형_답변이_없으면_판단하지_않는다() {
    double[][] w = {{0.6, 0.4}, {0.3, 0.7}};
    List<Answer> onlyStated = List.of(new Answer(new SliderSpec(0, 1), new Response.Slider(3)));
    assertThat(HiddenPreference.detect(w, onlyStated)).isEmpty();
  }
}
