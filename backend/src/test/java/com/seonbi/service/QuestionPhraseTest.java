package com.seonbi.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuestionPhraseTest {

  private static final String TEMPLATE = "끌림이(가) 2단계 더 높음, 수학 부담 1단계 더 나쁨";

  // AI가 "A안"처럼 내용 없는 설명을 주면 템플릿 문장을 그대로 둬야 한다
  @Test
  void emptyLabelsAreRejected() {
    assertFalse(QuestionFlowService.informative(TEMPLATE, "A안"));
    assertFalse(QuestionFlowService.informative(TEMPLATE, ""));
    assertFalse(QuestionFlowService.informative(TEMPLATE, null));
  }

  @Test
  void sentenceThatKeepsCriterionNamesIsAccepted() {
    assertTrue(QuestionFlowService.informative(TEMPLATE, "끌림은 2단계 높지만 수학 부담이 1단계 더 큰 학과"));
  }

  @Test
  void longSentenceWithoutCriterionNamesIsRejected() {
    assertFalse(QuestionFlowService.informative(TEMPLATE, "아주 좋은 선택지 입니다 정말 그렇습니다"));
  }
}
