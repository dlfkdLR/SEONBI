package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 개발기획서 5.12 노트북 예시 (노트북 3개 비교). 난수가 파이썬과 달라 소수 둘째 자리는 조금 다를 수 있어 ±0.03으로 본다. */
class Example512Test {

  @Test
  void 다섯_번째_예시_숫자를_재현한다() {
    JsonNode sel = Golden.load("selector");
    double[][] v = Golden.mat(sel.get("V"));
    List<Criterion> criteria = Golden.criteria(sel.get("criteria"));
    Map<String, Scale> scales = Golden.scales(sel.get("scales"));
    double tol = Golden.RANDOM_TOL;

    // 질문 2개 뒤: 슬라이더 (가격 vs 성능) -2, (성능 vs 무게) +1
    Answer q1 = new Answer(new SliderSpec(0, 2), new Response.Slider(-2));
    Answer q2 = new Answer(new SliderSpec(2, 1), new Response.Slider(1));
    Posterior post = Posterior.fit(1, 3, List.of(q1, q2));
    Posterior.Summary s = averaged(List.of(q1, q2), v);
    assertThat(s.mean()).containsExactly(new double[] {0.252, 0.317, 0.431}, offset(tol));
    assertThat(s.pBest()).containsExactly(new double[] {0.082, 0.639, 0.279}, offset(tol));

    // 가격 vs 무게 슬라이더의 EIG가 약 0.304bit
    List<QuestionSelector.Candidate> cands =
        QuestionSelector.scoreCandidates(post, v, criteria, scales);
    double sliderEig =
        cands.stream()
            .filter(c -> c.spec().equals(new SliderSpec(0, 1)))
            .findFirst()
            .orElseThrow()
            .eig();
    assertThat(sliderEig).isCloseTo(0.304, offset(tol));

    // 가격 20만 원 차이짜리 상황형을 B로 고르면 사후분포가 한 번 더 움직인다
    ScenarioSpec big =
        QuestionSelector.buildScenarios(s.mean(), 0, 1, criteria, scales).stream()
            .filter(x -> x.rawA() == 20)
            .findFirst()
            .orElseThrow();
    assertThat(big.rawB()).isCloseTo(0.10, offset(1e-9));
    List<Answer> three = List.of(q1, q2, new Answer(big, Response.Choice.B));
    Posterior post3 = Posterior.fit(1, 3, three);
    Posterior.Summary s3 = averaged(three, v);
    assertThat(s3.mean()).containsExactly(new double[] {0.215, 0.357, 0.428}, offset(tol));
    assertThat(s3.pBest()).containsExactly(new double[] {0.104, 0.729, 0.167}, offset(tol));
    assertThat(post3.ess()).isGreaterThan(400); // 재샘플링 없이 지나간다

    // 전환 조건: 기준마다 1위가 바뀌는 가중치
    List<DecisionAnalysis.ChangeCondition> cc = DecisionAnalysis.changeConditions(s3.mean(), v);
    assertThat(cc.get(0).direction()).isEqualTo(DecisionAnalysis.Direction.DECREASE);
    assertThat(cc.get(0).newTop()).isEqualTo(0);
    assertThat(cc.get(0).threshold()).isCloseTo(0.097, offset(tol));
    assertThat(cc.get(1).direction()).isEqualTo(DecisionAnalysis.Direction.DECREASE);
    assertThat(cc.get(1).newTop()).isEqualTo(2);
    assertThat(cc.get(1).threshold()).isCloseTo(0.227, offset(tol));
    assertThat(cc.get(2).direction()).isEqualTo(DecisionAnalysis.Direction.INCREASE);
    assertThat(cc.get(2).newTop()).isEqualTo(0);
    assertThat(cc.get(2).threshold()).isCloseTo(0.568, offset(tol));

    DecisionAnalysis.Stability st = DecisionAnalysis.stability(post3, v, 1);
    assertThat(st.top()).isEqualTo(1);
    assertThat(st.grade()).isEqualTo(DecisionAnalysis.Grade.MEDIUM);
    assertThat(st.perturbKeep()).isGreaterThan(0.95);
  }

  // 시드 하나로는 표본 잡음이 있어서(파이썬의 시드 42 값도 마찬가지) 시드 5개의 평균 요약을 쓴다
  private static Posterior.Summary averaged(List<Answer> answers, double[][] v) {
    int seeds = 5;
    double[] mean = new double[3];
    double[] pBest = new double[3];
    double entropy = 0;
    for (int seed = 1; seed <= seeds; seed++) {
      Posterior.Summary s = Posterior.fit(seed, 3, answers).summary(v);
      for (int k = 0; k < 3; k++) {
        mean[k] += s.mean()[k] / seeds;
        pBest[k] += s.pBest()[k] / seeds;
      }
      entropy += s.entropy() / seeds;
    }
    return new Posterior.Summary(mean, null, pBest, entropy);
  }
}
