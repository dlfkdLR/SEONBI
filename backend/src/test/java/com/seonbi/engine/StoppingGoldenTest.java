package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoppingGoldenTest {

  @Test
  void 종료_판단이_골든과_같다() {
    JsonNode g = Golden.load("stopping");
    double[][] w = Golden.mat(g.get("W"));
    double[][] v = Golden.mat(g.get("V"));
    Posterior flat = new Posterior(w, Golden.vec(g.get("pFlat")));
    Posterior conf = new Posterior(w, Golden.vec(g.get("pConf")));

    for (JsonNode c : g.get("cases")) {
      Posterior post = "flat".equals(c.get("label").asText()) ? flat : conf;
      List<Answer> history = history(c.get("nEngine").asInt(), c.get("nFree").asInt());
      Stopping.Decision d =
          Stopping.shouldStop(
              post,
              v,
              history,
              c.get("bestEig").asDouble(),
              c.get("userStopped").asBoolean(),
              Golden.optInt(c.get("questionLimit")),
              Golden.optInt(c.get("roundStart")));
      String where = c.toString();
      assertThat(d.stop()).as(where).isEqualTo(c.get("stop").asBoolean());
      if (c.get("reason").isNull()) {
        assertThat(d.reason()).as(where).isNull();
      } else {
        assertThat(d.reason().name()).as(where).isEqualTo(c.get("reason").asText());
      }
    }
  }

  @Test
  void 질문_수_상한_계산이_골든과_같다() {
    JsonNode g = Golden.load("stopping");
    g.get("maxQuestions")
        .forEach(
            c ->
                assertThat(Stopping.maxQuestions(c.get("k").asInt()))
                    .isEqualTo(c.get("limit").asInt()));
    g.get("extraRoundLimit")
        .forEach(
            c ->
                assertThat(Stopping.extraRoundLimit(c.get("answered").asInt()))
                    .isEqualTo(c.get("limit").asInt()));
    g.get("raisedLimit")
        .forEach(
            c ->
                assertThat(
                        Stopping.raisedLimit(c.get("current").asInt(), c.get("answered").asInt()))
                    .isEqualTo(c.get("limit").asInt()));
  }

  @Test
  void 자유_입력_가상_답변은_질문_수에_세지_않는다() {
    assertThat(Stopping.countedAnswers(history(4, 3))).isEqualTo(4);
  }

  @Test
  void 확신이_낮으면_배너_대상이다() {
    assertThat(Stopping.isLowConfidence(0.54)).isTrue();
    assertThat(Stopping.isLowConfidence(0.55)).isFalse();
  }

  // 엔진 답변 n개 + 자유 입력 가상 답변 m개로 된 답변 목록
  private static List<Answer> history(int nEngine, int nFree) {
    List<Answer> out = new ArrayList<>();
    for (int i = 0; i < nEngine; i++) {
      out.add(new Answer(new SliderSpec(0, 1), new Response.Slider(1)));
    }
    for (int i = 0; i < nFree; i++) {
      out.add(new Answer(new SliderSpec(0, 2), new Response.Slider(1), Answer.Origin.FREE_TEXT));
    }
    return out;
  }
}
