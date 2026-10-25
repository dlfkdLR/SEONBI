package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
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

  // 값이 모두 같아 물을 질문이 없어도 막지 않고 질문 없이 바로 결과로 보낸다 (리뷰 R06)
  @Test
  void 물을_질문이_없으면_질문_없이_ANALYZED가_된다() throws Exception {
    String id =
        postJ(
                "/api/v1/decisions",
                Map.of(
                    "title",
                    "다 똑같은 선택",
                    "category",
                    "etc",
                    "options",
                    List.of(Map.of("name", "가"), Map.of("name", "나"), Map.of("name", "다"))),
                201)
            .path("id")
            .asText();
    String base = "/api/v1/decisions/" + id;
    List<String> options = new java.util.ArrayList<>();
    getJ(base + "/options", 200).path("items").forEach(o -> options.add(o.path("id").asText()));
    List<Object> items = new java.util.ArrayList<>();
    for (String name : List.of("하나", "둘", "셋")) {
      String cr =
          postJ(
                  base + "/criteria",
                  Map.of("name", name, "type", "numeric", "direction", "higher_better"),
                  201)
              .path("id")
              .asText();
      for (String o : options) {
        items.add(Map.of("optionId", o, "criterionId", cr, "value", 5));
      }
    }
    call(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "/values"),
        Map.of("items", items),
        200);

    JsonNode started = postJ(base + "/questions/start", null, 200);
    assertThat(started.path("status").asText()).isEqualTo("ANALYZED");
    assertThat(started.path("question").isNull()).isTrue();
    assertThat(started.path("reason").asText()).isEqualTo("NO_QUESTION_CANDIDATES");
    assertThat(getJ(base, 200).path("status").asText()).isEqualTo("ANALYZED");
    assertThat(getJ(base + "/analysis", 200).path("rankings").size()).isEqualTo(3);
  }
}
