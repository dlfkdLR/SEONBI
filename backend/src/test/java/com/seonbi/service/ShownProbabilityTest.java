package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShownProbabilityTest {

  // 어림값이 100%여도 화면에는 99%까지만 보여 준다
  @Test
  void shownProbabilityIsCappedAt99Percent() {
    assertThat(AnalysisHelpers.shown(1.0)).isEqualTo(0.99);
    assertThat(AnalysisHelpers.shown(0.995)).isEqualTo(0.99);
    assertThat(AnalysisHelpers.shown(0.73)).isEqualTo(0.73);
  }

  @Test
  void dominanceNeedsEveryCriterionAtLeastAsGoodAndOneBetter() {
    double[][] dominated = {{1.0, 0.5, 1.0}, {0.0, 0.5, 0.2}};
    assertThat(AnalysisHelpers.dominatesAll(dominated, 0)).isTrue();
    assertThat(AnalysisHelpers.dominatesAll(dominated, 1)).isFalse();
    double[][] tradeOff = {{1.0, 0.0}, {0.0, 1.0}};
    assertThat(AnalysisHelpers.dominatesAll(tradeOff, 0)).isFalse();
    double[][] allEqual = {{0.5, 0.5}, {0.5, 0.5}};
    assertThat(AnalysisHelpers.dominatesAll(allEqual, 0)).isFalse();
  }
}
