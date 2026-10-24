package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.core.FlowError;
import com.seonbi.domain.AiUsageLog;
import com.seonbi.repository.DecisionRepository;
import com.seonbi.service.AiUsageService;
import com.seonbi.service.FinalizeService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 분석 API 39~46번, 확정 서비스, 호출 상한 (실제 DB, Fake LLM). */
class AnalysisApiTest extends FlowTestBase {

  @Autowired DecisionRepository decisions;
  @Autowired FinalizeService finalizer;
  @Autowired com.seonbi.service.ExtractionService extraction;
  @Autowired AiUsageService usage;
  @Autowired EntityManager em;
  @Autowired TransactionTemplate tx;

  /** 질문을 끝까지 답해 ANALYZED로 만든다. 마지막으로 낸 답변의 id를 돌려준다. */
  private String answerUntilDone(Setup s) throws Exception {
    JsonNode current = postJ(s.base() + "/questions/start", null, 200).path("question");
    String lastAnswer = null;
    for (int i = 0; i < 14; i++) {
      Map<String, Object> response =
          "slider_pair".equals(current.path("type").asText())
              ? Map.of("value", 3)
              : Map.of("choice", "A");
      JsonNode res =
          postJ(
              s.base() + "/answers",
              Map.of("questionId", current.path("id").asText(), "response", response),
              201);
      lastAnswer = res.path("answer").path("id").asText();
      if (res.path("next").path("done").asBoolean()) {
        return lastAnswer;
      }
      current = res.path("next").path("question");
    }
    throw new AssertionError("질문이 끝나지 않았다");
  }

  @Test
  void 상태에_따라_분석_조회가_막히거나_잠정_결과가_된다() throws Exception {
    Setup s = laptops();
    assertThat(getJ(s.base() + "/analysis", 422).path("error").path("code").asText())
        .isEqualTo("NOT_READY"); // DRAFT
    JsonNode q = postJ(s.base() + "/questions/start", null, 200).path("question");
    assertThat(getJ(s.base() + "/analysis", 422).path("error").path("code").asText())
        .isEqualTo("NOT_READY"); // 답변 0개
    postJ(
        s.base() + "/answers",
        Map.of("questionId", q.path("id").asText(), "response", Map.of("value", 3)),
        201);
    JsonNode provisional = getJ(s.base() + "/analysis", 200);
    assertThat(provisional.path("provisional").asBoolean()).isTrue();
    assertThat(provisional.path("recommendation").path("optionId").asText()).isIn(s.options());
  }

  @Test
  void 분석_결과는_7장_형식이고_부분_조회가_종합과_같다() throws Exception {
    Setup s = laptops();
    answerUntilDone(s);
    JsonNode full = getJ(s.base() + "/analysis", 200);
    assertThat(full.path("provisional").asBoolean()).isFalse();
    assertThat(full.path("rankings").size()).isEqualTo(3);
    assertThat(full.path("rankings").get(0).path("rank").asInt()).isEqualTo(1);
    assertThat(full.path("weights").size()).isEqualTo(3);
    assertThat(full.path("stability").path("level").asText()).isIn("HIGH", "MEDIUM", "LOW");
    assertThat(full.path("changeConditions").size()).isGreaterThanOrEqualTo(3); // 기준마다 가중치 항목
    assertThat(full.path("explanation").path("source").asText()).isEqualTo("template"); // 실제 제공자 없음
    assertThat(full.path("optionSummaries").size()).isEqualTo(3);
    assertThat(full.path("explanation").path("summary").asText()).contains("추천해요");

    // 40·41·44·45번은 39번 본문에서 꺼내므로 숫자가 같다
    assertThat((Object) getJ(s.base() + "/weights", 200).path("items"))
        .isEqualTo(full.path("weights"));
    assertThat(getJ(s.base() + "/weights", 200).path("intervalLevel").asDouble()).isEqualTo(0.8);
    assertThat((Object) getJ(s.base() + "/rankings", 200).path("items"))
        .isEqualTo(full.path("rankings"));
    assertThat((Object) getJ(s.base() + "/stability", 200)).isEqualTo(full.path("stability"));
    JsonNode changes = getJ(s.base() + "/change-conditions", 200);
    assertThat(changes.path("currentTop").asText())
        .isEqualTo(full.path("recommendation").path("optionId").asText());
    assertThat((Object) changes.path("items")).isEqualTo(full.path("changeConditions"));
  }

  @Test
  void 같은_버전이면_캐시를_쓰고_답변을_고치면_다시_계산한다() throws Exception {
    Setup s = laptops();
    String lastAnswer = answerUntilDone(s);
    String first = getJ(s.base() + "/analysis", 200).path("computedAt").asText();
    assertThat(getJ(s.base() + "/analysis", 200).path("computedAt").asText()).isEqualTo(first);
    call(
        patch(s.base() + "/answers/" + lastAnswer), Map.of("response", Map.of("choice", "B")), 200);
    String second = getJ(s.base() + "/analysis", 200).path("computedAt").asText();
    assertThat(second).isNotEqualTo(first);
  }

  @Test
  void 점수_민감도_시뮬레이션() throws Exception {
    Setup s = laptops();
    answerUntilDone(s);
    JsonNode scores = getJ(s.base() + "/scores", 200);
    assertThat(scores.path("criteria").size()).isEqualTo(3);
    assertThat(scores.path("items").get(0).path("cells").size()).isEqualTo(3);

    JsonNode all = getJ(s.base() + "/sensitivity", 200).path("items");
    assertThat(all.size()).isEqualTo(3);
    assertThat(all.get(0).path("points").size()).isEqualTo(21);
    JsonNode one =
        getJ(s.base() + "/sensitivity?criterionId=" + s.criteria().get(0), 200).path("items");
    assertThat(one.size()).isEqualTo(1);
    getJ(s.base() + "/sensitivity?criterionId=" + UUID.randomUUID(), 400);

    List<Object> weights =
        List.of(
            Map.of("criterionId", s.criteria().get(0), "weight", 5),
            Map.of("criterionId", s.criteria().get(1), "weight", 3),
            Map.of("criterionId", s.criteria().get(2), "weight", 2));
    JsonNode sim = postJ(s.base() + "/simulations", Map.of("weights", weights), 200);
    assertThat(sim.path("normalizedWeights").get(0).path("weight").asDouble()).isEqualTo(0.5);
    assertThat(sim.path("rankings").size()).isEqualTo(3);
    assertThat(sim.path("changedFromCurrent").isBoolean()).isTrue();
    // 누락·음수는 400
    postJ(s.base() + "/simulations", Map.of("weights", weights.subList(0, 2)), 400);
    postJ(
        s.base() + "/simulations",
        Map.of(
            "weights",
            List.of(
                Map.of("criterionId", s.criteria().get(0), "weight", -1),
                Map.of("criterionId", s.criteria().get(1), "weight", 1),
                Map.of("criterionId", s.criteria().get(2), "weight", 1))),
        400);
  }

  @Test
  void 확정하면_스냅샷을_저장하고_이후_조회는_확정_결과를_돌려준다() throws Exception {
    Setup s = laptops();
    answerUntilDone(s);
    JsonNode before = getJ(s.base() + "/analysis", 200);
    UUID decisionId = UUID.fromString(s.id());
    UUID cid2 = UUID.fromString(cid);
    UUID chosen = UUID.fromString(before.path("recommendation").path("optionId").asText());

    Map<String, Object> done = finalizer.finalizeDecision(decisionId, cid2, chosen, "배터리 때문에");
    assertThat(done.get("matchedRecommendation")).isEqualTo(true);
    assertThat(done.get("status")).isEqualTo("FINALIZED");
    assertThat(decisions.findById(decisionId).orElseThrow().getStatus()).isEqualTo("FINALIZED");

    JsonNode after = getJ(s.base() + "/analysis", 200);
    assertThat(after.path("provisional").asBoolean()).isFalse();
    assertThat((Object) after.path("rankings")).isEqualTo(before.path("rankings"));
    // 확정 뒤에는 질문·구성 수정이 막힌다
    postJ(s.base() + "/questions/start", null, 409);
    assertThatThrownBy(() -> finalizer.finalizeDecision(decisionId, cid2, chosen, null))
        .isInstanceOfSatisfying(
            FlowError.class, e -> assertThat(e.getCode()).isEqualTo("DECISION_FINALIZED"));
  }

  @Test
  void 분석_전에는_확정할_수_없다() throws Exception {
    Setup s = laptops();
    UUID id = UUID.fromString(s.id());
    UUID opt = UUID.fromString(s.options().get(0));
    assertThatThrownBy(() -> finalizer.finalizeDecision(id, UUID.fromString(cid), opt, null))
        .isInstanceOfSatisfying(
            FlowError.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATE"));
    answerUntilDone(s);
    assertThatThrownBy(
            () -> finalizer.finalizeDecision(id, UUID.fromString(cid), UUID.randomUUID(), null))
        .isInstanceOfSatisfying(
            FlowError.class, e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
  }

  @Test
  void 필수_조건으로_제외된_선택지는_확정할_수_없고_추천에서도_빠진다() throws Exception {
    Setup s = laptops();
    // 가격 160 이하만 허용 → 갤럭시북(189)·그램(165) 제외, 요가(150)만 남는다. 선택지가 하나라 질문 후보가 없다
    call(
        put(s.base() + "/conditions"),
        Map.of(
            "items",
            List.of(
                Map.of(
                    "kind",
                    "threshold",
                    "criterionId",
                    s.criteria().get(0),
                    "operator",
                    "<=",
                    "value",
                    170))),
        200);
    JsonNode started = postJ(s.base() + "/questions/start", null, 200);
    assertThat(started.path("status").asText()).isEqualTo("QUESTIONING");
    String qid = started.path("question").path("id").asText();
    postJ(s.base() + "/answers", Map.of("questionId", qid, "response", Map.of("value", 2)), 201);
    JsonNode analysis = getJ(s.base() + "/analysis", 200);
    assertThat(analysis.path("excludedOptions").size()).isEqualTo(1);
    assertThat(analysis.path("excludedOptions").get(0).path("reasons").get(0).asText())
        .contains("가격")
        .contains("초과");
    String excluded = analysis.path("excludedOptions").get(0).path("optionId").asText();
    assertThat(analysis.path("rankings").size()).isEqualTo(2);
    com.seonbi.domain.Decision d = decisions.findById(UUID.fromString(s.id())).orElseThrow();
    d.setStatus("ANALYZED");
    decisions.saveAndFlush(d);
    assertThatThrownBy(
            () ->
                finalizer.finalizeDecision(
                    UUID.fromString(s.id()), UUID.fromString(cid), UUID.fromString(excluded), null))
        .isInstanceOfSatisfying(
            FlowError.class, e -> assertThat(e.getCode()).isEqualTo("OPTION_EXCLUDED"));
  }

  @Test
  void AI_호출_상한은_사용자_호출_세_가지만_합쳐서_센다() {
    UUID client = UUID.randomUUID();
    assertThat(usage.exceeded(client, "extract_conditions")).isFalse();
    tx.executeWithoutResult(
        status -> {
          for (int i = 0; i < usage.dailyLimit(); i++) {
            AiUsageLog row = new AiUsageLog();
            row.setClientId(client);
            row.setCallType(
                i % 3 == 0
                    ? "recommend_criteria"
                    : i % 3 == 1 ? "recommend_options" : "extract_conditions");
            row.setProvider("fake");
            row.setModel("default");
            row.setInputTokens(0);
            row.setOutputTokens(0);
            row.setLatencyMs(0);
            row.setSuccess(true);
            em.persist(row);
          }
          // 자동 호출은 세지 않는다
          AiUsageLog auto = new AiUsageLog();
          auto.setClientId(UUID.randomUUID());
          auto.setCallType("phrase_question");
          auto.setProvider("fake");
          auto.setModel("default");
          auto.setSuccess(true);
          em.persist(auto);
        });
    assertThat(usage.exceeded(client, "extract_conditions")).isTrue();
    assertThat(usage.exceeded(client, "phrase_question")).isFalse();
    assertThat(usage.exceeded(UUID.randomUUID(), "extract_conditions")).isFalse();
    assertThatThrownBy(() -> usage.checkOrThrow(client, "recommend_options"))
        .isInstanceOfSatisfying(
            FlowError.class,
            e -> {
              assertThat(e.getCode()).isEqualTo("AI_QUOTA_EXCEEDED");
              assertThat(e.getStatus().value()).isEqualTo(429);
            });
    assertThat(usage.exceeded(client, "recommend_criteria"))
        .as("23번은 오류 대신 template으로 대체")
        .isTrue();
  }

  @Test
  void 질문_문장_다듬기_호출이_사용량으로_남는다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    Long count =
        em.createQuery(
                "select count(l) from AiUsageLog l where l.decision.id=:id and l.callType='phrase_question'",
                Long.class)
            .setParameter("id", UUID.fromString(s.id()))
            .getSingleResult();
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  @Test
  void 자유_입력_추출은_상한을_세고_확정된_결정에서는_막힌다() throws Exception {
    Setup s = laptops();
    com.seonbi.domain.Decision d = decisions.findById(UUID.fromString(s.id())).orElseThrow();
    var result =
        tx.execute(
            status ->
                extraction.extract(decisions.findById(d.getId()).orElseThrow(), "윈도우가 필수예요", null));
    assertThat(result.irrelevant()).isFalse();
    assertThat(result.items()).isNotEmpty();
    Long count =
        em.createQuery(
                "select count(l) from AiUsageLog l where l.decision.id=:id and l.callType='extract_conditions'",
                Long.class)
            .setParameter("id", d.getId())
            .getSingleResult();
    assertThat(count).as("28번 호출이 상한 집계에 남는다").isEqualTo(1);

    d.setStatus("FINALIZED");
    decisions.saveAndFlush(d);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        extraction.extract(decisions.findById(d.getId()).orElseThrow(), "x", null)))
        .isInstanceOfSatisfying(
            FlowError.class, e -> assertThat(e.getCode()).isEqualTo("DECISION_FINALIZED"));
  }
}
