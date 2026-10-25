package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seonbi.ai.Json;
import com.seonbi.ai.LlmException;
import com.seonbi.engine.Posterior;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 코드 리뷰(R05·R10·R11·R13·R19·R20·R23)에서 확인된 문제의 회귀 테스트. */
class ReviewFixesTest extends FlowTestBase {

  @Test
  void 시뮬레이션의_잘못된_항목은_500이_아니라_400이다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    postJ(s.base() + "/questions/stop", null, 200); // 시뮬레이션은 분석된 결정에서만 된다
    postJ(
        s.base() + "/simulations",
        Map.of("weights", java.util.Collections.singletonList(null)),
        400);
    postJ(s.base() + "/simulations", Map.of("weights", List.of(Map.of("weight", 1))), 400);
  }

  @Test
  void 가중치_합이_넘치면_400이다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    postJ(s.base() + "/questions/stop", null, 200);
    List<Object> items =
        s.criteria().stream().map(c -> (Object) Map.of("criterionId", c, "weight", 1e308)).toList();
    postJ(s.base() + "/simulations", Map.of("weights", items), 400);
  }

  @Test
  void 모든_선택지가_제외되면_질문_상태는_500이_아니라_준비_안_됨이다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    call(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
            s.base() + "/conditions"),
        Map.of(
            "items",
            List.of(Map.of("kind", "required_tag", "label", "Linux 지원", "satisfiedBy", List.of()))),
        200);
    getJ(s.base() + "/questions/status", 422);
    getJ(s.base() + "/questions/progress", 422);
  }

  @Test
  void 질문_시작은_수정_시각을_갱신한다() throws Exception {
    Setup s = laptops();
    String before = getJ(s.base(), 200).path("updatedAt").asText();
    Thread.sleep(15);
    postJ(s.base() + "/questions/start", null, 200);
    assertThat(getJ(s.base(), 200).path("updatedAt").asText()).isNotEqualTo(before);
  }

  @Test
  void JSON_null_응답은_스키마_위반이다() {
    assertThatThrownBy(() -> Json.parse(Map.class, "null")).isInstanceOf(LlmException.class);
  }

  @Test
  void 입자_수_환경변수는_범위_안이면_따르고_아니면_기본값이다() {
    assertThat(Posterior.particlesFromEnv("1000")).isEqualTo(1000);
    assertThat(Posterior.particlesFromEnv("16")).isEqualTo(4000);
    assertThat(Posterior.particlesFromEnv("abc")).isEqualTo(4000);
    assertThat(Posterior.particlesFromEnv(null)).isEqualTo(4000);
  }
}
