package com.seonbi.engine.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.seonbi.engine.sim.Simulation.Summary;
import org.junit.jupiter.api.Test;

/** 시뮬레이션이 같은 시드에서 같은 결과를 내고, EIG 전략이 랜덤보다 나쁘지 않은지 가볍게 확인한다. 전체 실험은 ./gradlew simulate 로 돌린다. */
class SimulationTest {

  @Test
  void 같은_시드면_같은_결과다() {
    Summary a = Simulation.runExperiment("career", 4, 7, 0);
    Summary b = Simulation.runExperiment("career", 4, 7, 0);
    assertThat(a.adaptive().get("eig").get("mae")).isEqualTo(b.adaptive().get("eig").get("mae"));
    assertThat(a.paired()).isEqualTo(b.paired());
  }

  @Test
  void 두_전략_모두_종료_규칙_안의_질문_수를_쓴다() {
    Summary s = Simulation.runExperiment("purchase", 6, 42, 0);
    for (String strategy : Simulation.STRATEGIES) {
      double questions = s.adaptive().get(strategy).get("questions").mean();
      assertThat(questions).isBetween(1.0, 10.0);
    }
    assertThat(s.fixed()).containsOnlyKeys(3, 5, 7);
  }

  @Test
  void 같은_질문_수에서_EIG는_랜덤보다_평균적으로_덜_틀린다() {
    // 사용자 30명 기준. 짝 비교 차이(랜덤 − EIG)가 양수여야 EIG가 더 정확한 것이다 (파이썬 결과도 같은 방향)
    Summary s = Simulation.runExperiment("purchase", 30, 42, 0);
    assertThat(s.paired().get("mae@5").mean()).isPositive();
    assertThat(s.paired().get("mae@7").mean()).isPositive();
  }

  @Test
  void 마크다운_표가_만들어진다() {
    String md = Simulation.formatMarkdown(Simulation.runExperiment("career", 3, 1, 0));
    assertThat(md)
        .contains("| 전략 | MAE | 1위 적중률 | 평균 질문 수 |")
        .contains("| eig |")
        .contains("| random |");
  }
}
