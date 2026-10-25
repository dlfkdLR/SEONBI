package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 자유 입력 28~30번, 최종 선택 47번, 내보내기 48번 (실제 DB, Fake LLM). */
class InputsAndResultApiTest extends FlowTestBase {

  private JsonNode analyzed(Setup s) throws Exception {
    postJ(s.base() + "/questions/start", null, 200);
    postJ(s.base() + "/questions/stop", null, 200);
    return getJ(s.base() + "/analysis", 200);
  }

  // ── 28~30 ─────────────────────────────────────────────────────

  @Test
  void 자유_입력은_추출해_PENDING으로_두고_거절하면_다시_처리할_수_없다() throws Exception {
    Setup s = laptops();
    JsonNode input =
        postJ(s.base() + "/inputs", Map.of("context", "reason", "text", "윈도우가 필수예요"), 201);
    assertThat(input.path("status").asText()).isEqualTo("PENDING");
    assertThat(input.path("extracted").size()).isGreaterThan(0);
    String id = input.path("id").asText();

    assertThat(postJ(s.base() + "/inputs/" + id + "/reject", null, 200).path("status").asText())
        .isEqualTo("REJECTED");
    postJ(s.base() + "/inputs/" + id + "/reject", null, 409); // PENDING이 아니면 INVALID_STATE
    postJ(
        s.base() + "/inputs/" + id + "/confirm",
        Map.of("items", List.of(Map.of("type", "option", "name", "맥북"))),
        409);
  }

  @Test
  void 확정하면_기준_선택지_조건_선호가_실제로_만들어진다() throws Exception {
    Setup s = laptops();
    String id =
        postJ(s.base() + "/inputs", Map.of("context", "other_condition", "text", "배터리가 중요해요"), 201)
            .path("id")
            .asText();

    JsonNode done =
        postJ(
            s.base() + "/inputs/" + id + "/confirm",
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "type",
                        "criterion",
                        "name",
                        "배터리",
                        "criterionType",
                        "numeric",
                        "direction",
                        "higher_better",
                        "unit",
                        "시간"),
                    Map.of("type", "option", "name", "맥북 에어"),
                    Map.of(
                        "type",
                        "constraint",
                        "kind",
                        "required_tag",
                        "label",
                        "Windows 지원",
                        "satisfiedBy",
                        List.of(s.options().get(0))),
                    Map.of(
                        "type",
                        "preference",
                        "criterionA",
                        "배터리",
                        "criterionB",
                        "가격",
                        "value",
                        3))),
            200);
    assertThat(done.path("status").asText()).isEqualTo("CONFIRMED");
    JsonNode created = done.path("created");
    assertThat(created.path("criteria").size()).isEqualTo(1);
    assertThat(created.path("options").size()).isEqualTo(1);
    assertThat(created.path("conditions").size()).isEqualTo(1);
    assertThat(created.path("answers").size()).isEqualTo(1);

    JsonNode detail = getJ(s.base(), 200);
    assertThat(detail.path("criteria").size()).isEqualTo(4);
    assertThat(detail.path("options").size()).isEqualTo(4);
    assertThat(detail.path("conditions").size()).isEqualTo(1);
    // 가상 슬라이더 질문의 답은 36번 목록에 free_text로 보인다
    JsonNode answers = getJ(s.base() + "/answers", 200).path("items");
    assertThat(answers.size()).isEqualTo(1);
    assertThat(answers.get(0).path("question").path("origin").asText()).isEqualTo("free_text");
    // 다시 확정하면 409
    postJ(
        s.base() + "/inputs/" + id + "/confirm",
        Map.of("items", List.of(Map.of("type", "option", "name", "x"))),
        409);
  }

  @Test
  void 없는_입력과_잘못된_요청은_오류로_응답한다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/inputs/" + java.util.UUID.randomUUID() + "/reject", null, 404);
    postJ(s.base() + "/inputs", Map.of("context", "unknown", "text", "가격"), 400);
    postJ(s.base() + "/inputs", Map.of("context", "reason", "text", ""), 400);
  }

  // ── 47 · 48 ───────────────────────────────────────────────────

  @Test
  void 최종_선택은_ANALYZED에서만_되고_확정되면_수정할_수_없다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/final-choice", Map.of("optionId", s.options().get(0)), 409); // DRAFT
    JsonNode analysis = analyzed(s);
    String top = analysis.path("recommendation").path("optionId").asText();

    JsonNode done =
        postJ(s.base() + "/final-choice", Map.of("optionId", top, "memo", "배터리 때문에"), 201);
    assertThat(done.path("status").asText()).isEqualTo("FINALIZED");
    assertThat(done.path("matchedRecommendation").asBoolean()).isTrue();
    assertThat(done.path("finalizedAt").asText()).isNotBlank();
    postJ(s.base() + "/final-choice", Map.of("optionId", top), 409); // 이미 확정
    postJ(s.base() + "/inputs", Map.of("context", "reason", "text", "추가"), 409);
  }

  @Test
  void 내보내기는_Markdown_파일이고_분석_전에는_422다() throws Exception {
    Setup s = laptops();
    getJ(s.base() + "/export?format=md", 422);
    analyzed(s);
    getJ(s.base() + "/export?format=pdf", 400);

    String body =
        mvc.perform(get(s.base() + "/export?format=md").header("X-Client-Id", cid))
            .andExpect(status().isOk())
            .andExpect(
                result ->
                    assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                        .isEqualTo("attachment; filename=\"decision-" + s.id() + ".md\""))
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body)
        .contains("# 새 노트북 고르기")
        .contains("| 선택지 |")
        .contains("## 중요도")
        .contains("## 순위 · 1위 확률")
        .contains("## 결과 안정성")
        .doesNotContain("## 최종 선택");

    // 확정하면 최종 선택과 메모가 들어간다
    String top = getJ(s.base() + "/analysis", 200).path("recommendation").path("optionId").asText();
    postJ(s.base() + "/final-choice", Map.of("optionId", top, "memo", "가벼워서"), 201);
    String finalBody =
        mvc.perform(get(s.base() + "/export").header("X-Client-Id", cid))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(finalBody).contains("## 최종 선택").contains("메모: 가벼워서");
  }
}
