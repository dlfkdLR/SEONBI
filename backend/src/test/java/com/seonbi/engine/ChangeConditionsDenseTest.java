package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 전환점이 촘촘하게 붙어 있어도 처음 1위가 되는 선택지를 건너뛰지 않는다 (리뷰 R12). */
class ChangeConditionsDenseTest {

  @Test
  void firstWinnerIsNotSkippedWhenBreakpointsAreClose() {
    double[] mean = {0.4, 0.6};
    double[][] v = {{0.6, 0.6}, {0.9, 0.3}, {1.0, 0.1998}};

    List<DecisionAnalysis.ChangeCondition> out = DecisionAnalysis.changeConditions(mean, v);

    DecisionAnalysis.ChangeCondition first = out.get(0);
    // 첫 기준의 가중치를 올리면 0.5에서 두 번째 선택지가 먼저 1위가 된다
    assertThat(first.threshold()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(first.newTop()).isEqualTo(1);
    // 그 가중치 바로 위(0.5001)에서 실제 1위도 두 번째 선택지다
    double[] w = {0.5001, 0.4999};
    assertThat(DecisionAnalysis.ranking(w, v).get(0)).isEqualTo(1);
  }
}
