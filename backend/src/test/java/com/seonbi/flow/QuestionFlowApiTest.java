package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.domain.Decision;
import com.seonbi.repository.DecisionRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 질문·답변 API 31~38번 흐름 (실제 DB, Fake LLM). */
class QuestionFlowApiTest extends FlowTestBase {

  @Autowired DecisionRepository decisions;

  private Map<String, Object> answerFor(JsonNode question) {
    return switch (question.path("type").asText()) {
      case "slider_pair" -> Map.of("value", 3);
      default -> Map.of("choice", "A");
    };
  }

  @Test
  void 질문_시작부터_종료까지_전체_흐름() throws Exception {
    Setup s = laptops();
    JsonNode start = postJ(s.base() + "/questions/start", null, 200);
    assertThat(start.path("status").asText()).isEqualTo("QUESTIONING");
    assertThat(start.path("round").asText()).isEqualTo("initial");
    assertThat(start.path("progress").path("answered").asInt()).isZero();
    assertThat(start.path("progress").path("expectedRange").get(0).asInt()).isEqualTo(3);
    JsonNode question = start.path("question");
    assertThat(question.path("seq").asInt()).isEqualTo(1);
    assertThat(question.path("type").asText()).isEqualTo("slider_pair");
    assertThat(question.path("payload").path("left").has("range")).isTrue();

    // 새로고침해도 같은 질문 (멱등)
    JsonNode again = postJ(s.base() + "/questions/start", null, 200);
    assertThat(again.path("question").path("id").asText()).isEqualTo(question.path("id").asText());
    assertThat(getJ(s.base() + "/questions/next", 200).path("question").path("id").asText())
        .isEqualTo(question.path("id").asText());

    // 답변을 계속 내면 종료 조건에 닿아 done=true가 된다
    Set<String> ids = new HashSet<>();
    JsonNode current = question;
    JsonNode next = null;
    for (int i = 0; i < 14; i++) {
      assertThat(ids.add(current.path("id").asText())).as("질문이 겹치지 않는다").isTrue();
      JsonNode res =
          postJ(
              s.base() + "/answers",
              Map.of("questionId", current.path("id").asText(), "response", answerFor(current)),
              201);
      assertThat(res.path("answer").path("isValid").asBoolean()).isTrue();
      assertThat(res.path("estimate").path("weights").size()).isEqualTo(3);
      next = res.path("next");
      if (next.path("done").asBoolean()) {
        break;
      }
      current = next.path("question");
    }
    assertThat(next.path("done").asBoolean()).isTrue();
    assertThat(next.path("reason").asText()).isIn("CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS");
    Decision d = decisions.findById(UUID.fromString(s.id())).orElseThrow();
    assertThat(d.getStatus()).isEqualTo("ANALYZED");
    assertThat(d.getVersion()).as("답변마다 버전이 오른다").isGreaterThan(5);

    // 종료 후: 항상 done=true, 33번은 shouldStop=true
    assertThat(getJ(s.base() + "/questions/next", 200).path("done").asBoolean()).isTrue();
    JsonNode status = getJ(s.base() + "/questions/status", 200);
    assertThat(status.path("shouldStop").asBoolean()).isTrue();
    assertThat(status.path("metrics").path("answered").asInt()).isEqualTo(ids.size());

    // 36번: 질문 순서대로, origin 포함
    JsonNode answers = getJ(s.base() + "/answers", 200).path("items");
    assertThat(answers.size()).isEqualTo(ids.size());
    assertThat(answers.get(0).path("question").path("origin").asText()).isEqualTo("engine");
    assertThat(answers.get(0).path("question").path("seq").asInt()).isEqualTo(1);
  }

  @Test
  void 같은_질문에_두_번_답하면_409이고_수정은_38번으로() throws Exception {
    Setup s = laptops();
    JsonNode q = postJ(s.base() + "/questions/start", null, 200).path("question");
    JsonNode first =
        postJ(
            s.base() + "/answers",
            Map.of("questionId", q.path("id").asText(), "response", Map.of("value", 2)),
            201);
    JsonNode dup =
        postJ(
            s.base() + "/answers",
            Map.of("questionId", q.path("id").asText(), "response", Map.of("value", 4)),
            409);
    assertThat(dup.path("error").path("code").asText()).isEqualTo("QUESTION_ALREADY_ANSWERED");

    int version = decisions.findById(UUID.fromString(s.id())).orElseThrow().getVersion();
    JsonNode edited =
        call(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                s.base() + "/answers/" + first.path("answer").path("id").asText()),
            Map.of("response", Map.of("value", -4)),
            200);
    assertThat(edited.path("estimate").path("weights").size()).isEqualTo(3);
    assertThat(decisions.findById(UUID.fromString(s.id())).orElseThrow().getVersion())
        .isGreaterThan(version);
    assertThat(
            getJ(s.base() + "/answers", 200)
                .path("items")
                .get(0)
                .path("response")
                .path("value")
                .asInt())
        .isEqualTo(-4);
  }

  @Test
  void 답변_형식이_질문_유형과_맞지_않으면_400() throws Exception {
    Setup s = laptops();
    JsonNode q = postJ(s.base() + "/questions/start", null, 200).path("question"); // slider_pair
    String qid = q.path("id").asText();
    JsonNode bad =
        postJ(
            s.base() + "/answers",
            Map.of("questionId", qid, "response", Map.of("choice", "A")),
            400);
    assertThat(bad.path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");
    postJ(
        s.base() + "/answers",
        Map.of("questionId", UUID.randomUUID().toString(), "response", Map.of("skip", true)),
        404);
    // 범위를 넘는 값은 DTO 검증에서 400
    postJ(s.base() + "/answers", Map.of("questionId", qid, "response", Map.of("value", 9)), 400);
    // 건너뛰기는 된다
    postJ(s.base() + "/answers", Map.of("questionId", qid, "response", Map.of("skip", true)), 201);
  }

  @Test
  void 상태_제약_DRAFT와_준비_안_됨() throws Exception {
    // 값이 비어 있으면 31번은 422 NOT_READY(missing 포함), 32·33번은 DRAFT에서 422
    String id =
        postJ("/api/v1/decisions", Map.of("title", "빈 결정", "category", "etc"), 201)
            .path("id")
            .asText();
    String base = "/api/v1/decisions/" + id;
    JsonNode notReady = postJ(base + "/questions/start", null, 422);
    assertThat(notReady.path("error").path("code").asText()).isEqualTo("NOT_READY");
    assertThat(notReady.path("error").path("details").path("missing").toString())
        .contains("OPTIONS_LT_2");
    assertThat(getJ(base + "/questions/next", 422).path("error").path("code").asText())
        .isEqualTo("NOT_READY");
    assertThat(getJ(base + "/questions/status", 422).path("error").path("code").asText())
        .isEqualTo("NOT_READY");
    // 답변·조기 종료는 DRAFT에서 409 INVALID_STATE
    postJ(base + "/questions/stop", null, 409);
    // 남의 결정은 404
    String other = cid;
    cid = UUID.randomUUID().toString();
    getJ(base + "/questions/next", 404);
    cid = other;
  }

  @Test
  void 조기_종료는_ANALYZED로_바꾸고_확신이_낮으면_표시한다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    JsonNode stop = postJ(s.base() + "/questions/stop", null, 200);
    assertThat(stop.path("reason").asText()).isEqualTo("USER_STOPPED");
    assertThat(stop.path("lowConfidence").isBoolean()).isTrue();
    assertThat(decisions.findById(UUID.fromString(s.id())).orElseThrow().getStatus())
        .isEqualTo("ANALYZED");
    // ANALYZED에서는 답변 제출이 409
    postJ(
        s.base() + "/answers",
        Map.of("questionId", UUID.randomUUID().toString(), "response", Map.of("skip", true)),
        409);
    // 다시 질문을 시작하면 추가 라운드
    JsonNode extra = postJ(s.base() + "/questions/start", null, 200);
    assertThat(extra.path("round").asText()).isEqualTo("extra");
    Decision d = decisions.findById(UUID.fromString(s.id())).orElseThrow();
    assertThat(d.getStatus()).isEqualTo("QUESTIONING");
    assertThat(d.getQuestionLimit()).isEqualTo(3); // 답변 0개 + 3
  }

  @Test
  void 분석_뒤_기준을_추가하면_추가_라운드로_돌아간다() throws Exception {
    Setup s = laptops();
    JsonNode q = postJ(s.base() + "/questions/start", null, 200).path("question");
    postJ(
        s.base() + "/answers",
        Map.of("questionId", q.path("id").asText(), "response", Map.of("value", 3)),
        201);
    postJ(s.base() + "/questions/stop", null, 200);

    JsonNode added =
        postJ(
            s.base() + "/criteria",
            Map.of("name", "배터리", "type", "numeric", "direction", "higher_better"),
            201);
    assertThat(added.path("statusChanged").asText()).isEqualTo("QUESTIONING");
    Decision d = decisions.findById(UUID.fromString(s.id())).orElseThrow();
    assertThat(d.getStatus()).isEqualTo("QUESTIONING");
    assertThat(d.getQuestionLimit()).isEqualTo(4); // 답변 1개 + 3

    // 이미 QUESTIONING이면 상태는 안 바뀌어 statusChanged가 null이다
    JsonNode second =
        postJ(
            s.base() + "/criteria",
            Map.of("name", "저장공간", "type", "numeric", "direction", "higher_better"),
            201);
    assertThat(second.path("statusChanged").isNull()).isTrue();
  }

  @Test
  void DRAFT에서_기준을_추가해도_상태는_그대로다() throws Exception {
    Setup s = laptops();
    JsonNode added =
        postJ(
            s.base() + "/criteria",
            Map.of("name", "배터리", "type", "numeric", "direction", "higher_better"),
            201);
    assertThat(added.path("statusChanged").isNull() || added.path("statusChanged").isMissingNode())
        .isTrue();
    assertThat(decisions.findById(UUID.fromString(s.id())).orElseThrow().getStatus())
        .isEqualTo("DRAFT");
  }
}
