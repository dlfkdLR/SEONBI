package com.seonbi.engine.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seonbi.engine.Answer;
import com.seonbi.engine.DecisionAnalysis;
import com.seonbi.engine.Posterior;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import com.seonbi.engine.Response;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

/**
 * 범용 AI 비교 실험(개선사항 4)의 입력과 선비 쪽 결과를 만든다.
 *
 * <p>정답 가중치를 가진 가상 사용자가 같은 슬라이더 질문 6개에 답한 결과를 JSON으로 내보낸다. 같은 답변을 GPT·Claude에게도 보여
 * 주고(scripts/ai_compare.py), 선비 엔진은 입자 난수 시드를 5번 바꿔가며 같은 답변에서 가중치를 추정한다. 사용자 입력 원문이나 개인정보는 없다(전부 합성
 * 데이터).
 *
 * <p>실행: {@code ./gradlew aiCompareFixture} (출력은 표준 출력의 JSON)
 */
public final class AiCompareFixture {

  static final int[][] PAIRS = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 3}, {1, 4}};
  static final int USERS = 4; // 1위가 맥북인 사용자 2명 + 아닌 사용자 2명 (1위 적중 지표가 자명하지 않도록)
  static final long SEED = 2026;

  public static void main(String[] args) throws Exception {
    ObjectMapper m = new ObjectMapper();
    Simulation.Problem p = Simulation.problem("purchase");
    int k = p.v()[0].length;
    ObjectNode root = m.createObjectNode();
    root.put("fitSeeds", "1..5 (입자 4000, 같은 시드는 항상 같은 결과)");
    ArrayNode matrix = root.putArray("normalizedMatrix"); // 선택지 × 기준, 0~1 (높을수록 좋음)
    for (double[] row : p.v()) {
      ArrayNode r = matrix.addArray();
      for (double x : row) r.add(round(x));
    }
    ArrayNode users = root.putArray("users");
    int macTop = 0, otherTop = 0;
    for (int u = 0; macTop + otherTop < USERS; u++) {
      double[] trueW = Posterior.priorParticles(SEED * 1_000_003L + u * 31L, k, 1)[0];
      boolean isMac = DecisionAnalysis.ranking(trueW, p.v()).get(0) == 3;
      if (isMac ? macTop >= USERS / 2 : otherTop >= USERS / 2) continue;
      if (isMac) macTop++;
      else otherTop++;
      RandomGenerator rng = new Well19937c(SEED * 7919L + u);
      List<Answer> answers = new ArrayList<>();
      ObjectNode user = users.addObject();
      ArrayNode trueArr = user.putArray("trueWeights");
      for (double x : trueW) trueArr.add(round(x));
      user.put("trueTop", DecisionAnalysis.ranking(trueW, p.v()).get(0));
      ArrayNode qa = user.putArray("answers");
      for (int[] pair : PAIRS) {
        SliderSpec q = new SliderSpec(pair[0], pair[1]);
        Response r = Simulation.sampleResponse(q, trueW, rng, 0);
        answers.add(new Answer(q, r));
        ObjectNode a = qa.addObject();
        a.put("a", pair[0]);
        a.put("b", pair[1]);
        a.put("value", ((Response.Slider) r).value());
      }
      ArrayNode runs = user.putArray("seonbi");
      for (long fit = 1; fit <= 5; fit++) {
        double[] mean = Posterior.fit(fit, k, answers).mean();
        ObjectNode run = runs.addObject();
        run.put("fitSeed", fit);
        ArrayNode w = run.putArray("weights");
        for (double x : mean) w.add(round(x));
        run.put("top", DecisionAnalysis.ranking(mean, p.v()).get(0));
      }
    }
    System.out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(root));
  }

  private static double round(double x) {
    return Math.round(x * 10000.0) / 10000.0;
  }
}
