package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LargeNumberTest {

  @Test
  void numKeepsHugeValuesInsteadOfSaturatingToLongMax() {
    assertThat(AnalysisHelpers.num(1e20).doubleValue()).isEqualTo(1e20);
    assertThat(AnalysisHelpers.num(210.0)).isEqualTo(210L);
    assertThat(AnalysisHelpers.num(2.5)).isEqualTo(2.5);
  }
}
