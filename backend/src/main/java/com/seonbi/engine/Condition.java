package com.seonbi.engine;

import java.util.Set;

/** 필수 조건. THRESHOLD는 기준 값 비교, REQUIRED_TAG는 충족하는 선택지 ID 목록으로 판단한다. */
public record Condition(
    String id,
    Kind kind,
    String criterionId,
    Operator operator,
    Double value,
    Set<String> satisfiedBy) {

  public enum Kind {
    THRESHOLD,
    REQUIRED_TAG
  }

  public enum Operator {
    LE,
    GE,
    EQ
  }
}
