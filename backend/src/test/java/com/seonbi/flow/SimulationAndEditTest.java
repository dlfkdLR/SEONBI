package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 개선사항 1(가중치 시뮬레이션)·2(답변 수정)의 동작을 수치로 확인한다.
 *
 * <ul>
 *   <li>시뮬레이션: 추정 가중치를 그대로 넣으면 순위가 안 바뀌고, 한 기준에 몰아주면 그 기준 1등이 1위가 된다. 합이 1이 아니어도 비율로 정규화한다.
 *   <li>답변 수정: 고치면 추정이 바뀌고, 원래 답으로 되돌리면 처음 추정과 정확히 같아진다(모든 유효 답변으로 처음부터 다시 계산하므로).
 * </ul>
 */
class SimulationAndEditTest extends FlowTestBase {

  private record Done(List<String> answerIds, List<JsonNode> questions) {}

  /** 질문을 끝까지 답한다. 슬라이더 질문은 3, 그 밖은 A를 고른다. */
  private Done answerAll(Setup s) throws Exception {
    JsonNode current = postJ(s.base() + "/questions/start", null, 200).path("question");
    List<String> ids = new ArrayList<>();
    List<JsonNode> questions = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      Map<String, Object> response =
          "slider_pair".equals(current.path("type").asText())
              ? Map.of("value", 3)
              : Map.of("choice", "A");
      questions.add(current);
      JsonNode res =
          postJ(
              s.base() + "/answers",
              Map.of("questionId", current.path("id").asText(), "response", response),
              201);
      ids.add(res.path("answer").path("id").asText());
      if (res.path("next").path("done").asBoolean()) return new Done(ids, questions);
      current = res.path("next").path("question");
    }
    throw new AssertionError("질문이 끝나지 않았다");
  }

  private double[] means(Setup s) throws Exception {
    JsonNode w = getJ(s.base() + "/analysis", 200).path("weights");
    double[] out = new double[w.size()];
    for (int i = 0; i < out.length; i++) out[i] = w.get(i).path("mean").asDouble();
    return out;
  }

  private JsonNode simulate(Setup s, double... w) throws Exception {
    List<Object> items = new ArrayList<>();
    for (int i = 0; i < w.length; i++)
      items.add(Map.of("criterionId", s.criteria().get(i), "weight", w[i]));
    return postJ(s.base() + "/simulations", Map.of("weights", items), 200);
  }

  @Test
  void 추정_가중치를_그대로_넣으면_순위가_바뀌지_않고_점수는_분석과_같다() throws Exception {
    Setup s = laptops();
    answerAll(s);
    JsonNode analysis = getJ(s.base() + "/analysis", 200);
    double[] w = means(s);
    JsonNode sim = simulate(s, w);
    assertThat(sim.path("changedFromCurrent").asBoolean()).isFalse();
    for (int i = 0; i < w.length; i++) {
      assertThat(sim.path("rankings").get(i).path("optionId").asText())
          .isEqualTo(analysis.path("rankings").get(i).path("optionId").asText());
    }
  }

  @Test
  void 한_기준에_몰아주면_그_기준의_1등이_1위가_되고_합이_1이_아니어도_정규화한다() throws Exception {
    Setup s = laptops();
    answerAll(s);
    // 가격(낮을수록 좋음)에만: 요가(150만 원)가 1위
    JsonNode price = simulate(s, 7, 0, 0);
    assertThat(price.path("rankings").get(0).path("optionId").asText())
        .isEqualTo(s.options().get(2));
    assertThat(price.path("normalizedWeights").get(0).path("weight").asDouble()).isEqualTo(1.0);
    // 무게(낮을수록 좋음)에만: 그램(1.19kg)이 1위
    JsonNode weight = simulate(s, 0, 40, 0);
    assertThat(weight.path("rankings").get(0).path("optionId").asText())
        .isEqualTo(s.options().get(1));
    // 점수는 큰 순서대로, 순위는 1부터
    JsonNode r = price.path("rankings");
    for (int i = 0; i < r.size(); i++) assertThat(r.get(i).path("rank").asInt()).isEqualTo(i + 1);
    for (int i = 1; i < r.size(); i++)
      assertThat(r.get(i - 1).path("score").asDouble())
          .isGreaterThanOrEqualTo(r.get(i).path("score").asDouble());
    // 두 가지 주문(가격만 / 무게만)은 1위가 다르므로 changedFromCurrent도 둘 중 하나는 true다
    assertThat(
            price.path("changedFromCurrent").asBoolean()
                || weight.path("changedFromCurrent").asBoolean())
        .isTrue();
  }

  @Test
  void 시뮬레이션은_저장하지_않는다() throws Exception {
    Setup s = laptops();
    answerAll(s);
    String computed = getJ(s.base() + "/analysis", 200).path("computedAt").asText();
    double[] before = means(s);
    simulate(s, 1, 0, 0);
    simulate(s, 0, 1, 0);
    assertThat(getJ(s.base() + "/analysis", 200).path("computedAt").asText()).isEqualTo(computed);
    assertThat(means(s)).containsExactly(before);
  }

  @Test
  void 답변을_고치면_추정이_바뀌고_원래대로_되돌리면_처음과_같아진다() throws Exception {
    Setup s = laptops();
    Done d = answerAll(s);
    double[] original = means(s);
    String first = d.answerIds().get(0);
    JsonNode q = d.questions().get(0);
    boolean slider = "slider_pair".equals(q.path("type").asText());
    Map<String, Object> originalResponse = slider ? Map.of("value", 3) : Map.of("choice", "A");
    Map<String, Object> changedResponse = slider ? Map.of("value", 5) : Map.of("choice", "B");

    JsonNode patched =
        call(patch(s.base() + "/answers/" + first), Map.of("response", changedResponse), 200);
    assertThat(patched.path("estimate").isObject()).isTrue();
    double[] changed = means(s);
    assertThat(changed).isNotEqualTo(original);
    // 합은 여전히 1이다(응답이 소수 둘째 자리로 반올림돼서 0.02 안쪽까지 본다)
    double sum = 0;
    for (double v : changed) sum += v;
    assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.02));

    call(patch(s.base() + "/answers/" + first), Map.of("response", originalResponse), 200);
    double[] restored = means(s);
    assertThat(restored).containsExactly(original);
  }

  @Test
  void 답변_수정_뒤에도_상태는_유지되고_확정되면_막힌다() throws Exception {
    Setup s = laptops();
    Done d = answerAll(s);
    String status = getJ(s.base(), 200).path("status").asText();
    boolean slider = "slider_pair".equals(d.questions().get(0).path("type").asText());
    Map<String, Object> other = slider ? Map.of("value", 5) : Map.of("choice", "B");
    Map<String, Object> back = slider ? Map.of("value", 3) : Map.of("choice", "A");
    String first = d.answerIds().get(0);
    call(patch(s.base() + "/answers/" + first), Map.of("response", other), 200);
    assertThat(getJ(s.base(), 200).path("status").asText()).isEqualTo(status);
    // 확정한 뒤에는 409
    JsonNode top = getJ(s.base() + "/analysis", 200).path("recommendation");
    postJ(s.base() + "/final-choice", Map.of("optionId", top.path("optionId").asText()), 201);
    JsonNode err = call(patch(s.base() + "/answers/" + first), Map.of("response", back), 409);
    assertThat(err.path("error").path("code").asText()).isEqualTo("DECISION_FINALIZED");
  }
}
