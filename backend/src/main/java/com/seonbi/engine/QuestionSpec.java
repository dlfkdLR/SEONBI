package com.seonbi.engine;

import java.util.Arrays;

/** 질문 계산용 스펙. 기준은 호출 시점 기준 목록의 인덱스로 가리킨다. */
public sealed interface QuestionSpec {

  int a();

  int b();

  /** 양방향 슬라이더. s &gt; 0이면 왼쪽(a)이 더 중요. */
  record SliderSpec(int a, int b) implements QuestionSpec {}

  /** A = 기준 a, B = 기준 b 중 더 중요한 쪽 고르기. */
  record CriteriaABSpec(int a, int b) implements QuestionSpec {}

  /**
   * 가상 선택지 A/B. delta는 정규화 값 차이 v_A − v_B (길이 K), rawA·rawB는 화면용 차이. 배열을 가지므로 equals/hashCode를 값
   * 기준으로 직접 정의한다.
   */
  record ScenarioSpec(int a, int b, double[] delta, double rawA, double rawB)
      implements QuestionSpec {
    @Override
    public boolean equals(Object o) {
      return o instanceof ScenarioSpec s
          && a == s.a
          && b == s.b
          && Arrays.equals(delta, s.delta)
          && Double.compare(rawA, s.rawA) == 0
          && Double.compare(rawB, s.rawB) == 0;
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(delta)
          + 7 * a
          + 13 * b
          + Double.hashCode(rawA)
          + Double.hashCode(rawB);
    }

    @Override
    public String toString() {
      return "ScenarioSpec[a="
          + a
          + ", b="
          + b
          + ", delta="
          + Arrays.toString(delta)
          + ", rawA="
          + rawA
          + ", rawB="
          + rawB
          + "]";
    }
  }
}
