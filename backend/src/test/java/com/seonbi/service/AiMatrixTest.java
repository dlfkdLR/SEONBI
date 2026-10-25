package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seonbi.domain.Criterion;
import org.junit.jupiter.api.Test;

/** AI 장단점 요약 입력에 어느 쪽이 좋은지가 들어가는가 (리뷰 R21). */
class AiMatrixTest {

  @Test
  void standingTellsBestWorstAndMiddle() {
    double[][] v = {{1.0, 0.5}, {0.0, 0.5}, {0.4, 0.5}};
    assertThat(AnalysisService.standing(v, 0, 0)).isEqualTo("가장 유리");
    assertThat(AnalysisService.standing(v, 1, 0)).isEqualTo("가장 불리");
    assertThat(AnalysisService.standing(v, 2, 0)).isEqualTo("중간");
    assertThat(AnalysisService.standing(v, 0, 1)).isEqualTo("차이 없음");
  }

  // 같은 이름·같은 값이어도 방향이 다르면 입력이 달라야 한다
  @Test
  void betterWhenFollowsDirection() {
    Criterion price = new Criterion();
    price.setType("numeric");
    price.setDirection("lower_better");
    Criterion speed = new Criterion();
    speed.setType("numeric");
    speed.setDirection("higher_better");
    assertThat(AnalysisService.betterWhen(price)).isEqualTo("낮을수록 좋음");
    assertThat(AnalysisService.betterWhen(speed)).isEqualTo("높을수록 좋음");
  }
}
