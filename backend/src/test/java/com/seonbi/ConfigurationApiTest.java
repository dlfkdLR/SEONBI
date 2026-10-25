package com.seonbi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.seonbi.domain.Decision;
import com.seonbi.repository.DecisionRepository;
import com.seonbi.service.ConfigurationHooks;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Import(ConfigurationApiTest.Hooks.class)
class ConfigurationApiTest extends FoundationTest {
  @Autowired ObjectMapper mapper;
  @Autowired DecisionRepository repository;

  @TestConfiguration
  static class Hooks {
    @Bean
    ConfigurationHooks.CriterionAdded transition() {
      return (d, id) -> {
        String previous = d.getStatus();
        d.setStatus("QUESTIONING");
        d.setQuestionLimit(3);
        return "QUESTIONING".equals(previous) ? null : "QUESTIONING";
      };
    }
  }

  String cid = UUID.randomUUID().toString();

  JsonNode call(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
    request.header("X-Client-Id", cid);
    if (body != null)
      request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    String text =
        mvc.perform(request)
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return text.isEmpty() ? mapper.nullNode() : mapper.readTree(text);
  }

  String create() throws Exception {
    return call(post("/api/v1/decisions"), Map.of("title", "노트북", "category", "purchase"), 201)
        .path("id")
        .asText();
  }

  String option(String base, String name) throws Exception {
    return call(post(base + "/options"), Map.of("name", name), 201).path("id").asText();
  }

  String criterion(String base, String name, String type, String mode) throws Exception {
    Map<String, Object> body = new HashMap<>(Map.of("name", name, "type", type));
    if (type.equals("numeric")) body.put("direction", "lower_better");
    if (mode != null) body.put("inputMode", mode);
    return call(post(base + "/criteria"), body, 201).path("id").asText();
  }

  @Test
  void laptopConfigurationExclusionsVersionDuplicateAndDelete() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String mac = option(base, "맥북"), lg = option(base, "그램"), samsung = option(base, "갤럭시북");
    String price = criterion(base, "가격", "numeric", null),
        performance = criterion(base, "성능", "rating", "score");
    assertThat(call(get(base), null, 200).path("readiness").path("canStartQuestions").asBoolean())
        .isFalse();
    List<Object> values = new ArrayList<>();
    for (String o : List.of(mac, lg, samsung)) {
      values.add(Map.of("optionId", o, "criterionId", price, "value", 150));
      values.add(Map.of("optionId", o, "criterionId", performance, "value", 4));
    }
    call(put(base + "/values"), Map.of("items", values), 200);
    assertThat(call(get(base), null, 200).path("readiness").path("canStartQuestions").asBoolean())
        .isTrue();
    var cond =
        call(
            put(base + "/conditions"),
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "kind",
                        "required_tag",
                        "label",
                        "Windows 지원",
                        "source",
                        "ai_recommended",
                        "satisfiedBy",
                        List.of(lg, samsung)))),
            200);
    assertThat(cond.path("excludedOptions").size()).isEqualTo(1);
    assertThat(cond.path("excludedOptions").get(0).path("optionId").asText()).isEqualTo(mac);
    Decision d = repository.findById(UUID.fromString(id)).orElseThrow();
    int version = d.getVersion();
    call(patch(base), Map.of("title", "변경 제목"), 200);
    assertThat(repository.findById(d.getId()).orElseThrow().getVersion()).isEqualTo(version);
    String old = cid;
    cid = UUID.randomUUID().toString();
    assertThat(call(get(base), null, 200).path("id").asText()).isEqualTo(id);
    cid = old;
    d = repository.findById(d.getId()).orElseThrow();
    d.setStatus("FINALIZED");
    repository.saveAndFlush(d);
    call(patch(base), Map.of("title", "금지"), 409);
    var clone = call(post(base + "/duplicate"), null, 201);
    assertThat(clone.path("status").asText()).isEqualTo("DRAFT");
    assertThat(clone.path("options").size()).isEqualTo(3);
    assertThat(clone.path("conditions").get(0).path("source").asText()).isEqualTo("ai_recommended");
    assertThat(clone.path("readiness").path("canStartQuestions").asBoolean()).isTrue();
    call(delete(base), null, 204);
    call(get(base), null, 404);
    assertThat(repository.findById(d.getId())).isPresent();
  }

  @Test
  void decisionsAreSharedAcrossBrowsersIncludingEditsAndDeletes() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    cid = UUID.randomUUID().toString();
    assertThat(call(get("/api/v1/decisions"), null, 200).path("items").toString())
        .contains(id);
    call(patch(base), Map.of("title", "공유 수정"), 200);
    assertThat(call(get(base), null, 200).path("title").asText()).isEqualTo("공유 수정");
    call(delete(base), null, 204);
    call(get(base), null, 404);
  }

  @Test
  void clearingValuesRemovesOnlyOmittedCellsAndRankings() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String a = option(base, "A"), b = option(base, "B");
    String score = criterion(base, "비용", "numeric", null);
    String rank = criterion(base, "흥미", "rating", "rank");
    call(put(base + "/values"), Map.of(
        "items", List.of(
            Map.of("optionId", a, "criterionId", score, "value", 3),
            Map.of("optionId", b, "criterionId", score, "value", 5)),
        "rankings", List.of(Map.of("criterionId", rank, "order", List.of(List.of(a), List.of(b))))), 200);
    var partial = call(put(base + "/values"), Map.of(
        "items", List.of(Map.of("optionId", b, "criterionId", score, "value", 4))), 200);
    assertThat(partial.path("items").size()).isEqualTo(2);
    assertThat(partial.path("rankings").get(0).path("order").size()).isEqualTo(2);
    var clear = new HashMap<String, Object>();
    clear.put("optionId", a);
    clear.put("criterionId", score);
    clear.put("value", null);
    var after = call(put(base + "/values"), Map.of(
        "items", List.of(clear),
        "rankings", List.of(Map.of("criterionId", rank, "order", List.of()))), 200);
    assertThat(after.path("items").size()).isEqualTo(1);
    assertThat(after.path("items").get(0).path("optionId").asText()).isEqualTo(b);
    assertThat(after.path("rankings").get(0).path("order").size()).isZero();
    assertThat(after.path("missing").size()).isEqualTo(3);
    assertThat(call(get(base + "/values"), null, 200).path("items").size()).isEqualTo(1);
  }

  @Test
  void rankValidationAutomaticPlacementAndLimits() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String a = option(base, "A"), b = option(base, "B");
    String rank = criterion(base, "흥미", "rating", "rank");
    var incomplete =
        call(
            put(base + "/values"),
            Map.of("rankings", List.of(Map.of("criterionId", rank, "order", List.of(List.of(a))))),
            400);
    assertThat(incomplete.path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(incomplete.path("error").path("details").path("criterionId").asText())
        .isEqualTo(rank);
    call(
        put(base + "/values"),
        Map.of("rankings", List.of(Map.of("criterionId", rank, "order", List.of(List.of(a, b))))),
        200);
    Decision d = repository.findById(UUID.fromString(id)).orElseThrow();
    d.setStatus("QUESTIONING");
    repository.saveAndFlush(d);
    call(delete(base + "/options/" + a), null, 409);
    var c = call(post(base + "/options"), Map.of("name", "C"), 201);
    assertThat(c.path("warning").asText()).isEqualTo("RANKING_UPDATED");
    assertThat(call(get(base + "/values"), null, 200).path("rankings").get(0).path("order").size())
        .isEqualTo(2);
    var added = call(post(base + "/criteria"), Map.of("name", "적성", "type", "rating"), 201);
    assertThat(added.path("statusChanged").isNull()).isTrue();
    for (int i = 0; i < 5; i++) option(base, "추가" + i);
    assertThat(
            call(post(base + "/options"), Map.of("name", "초과"), 409)
                .path("error")
                .path("code")
                .asText())
        .isEqualTo("LIMIT_EXCEEDED");
    for (int i = 0; i < 5; i++) criterion(base, "기준" + i, "rating", null);
    call(post(base + "/criteria"), Map.of("name", "기준 초과", "type", "rating"), 409);
  }

  @Test
  void malformedInputsFallbackAndPagination() throws Exception {
    call(post("/api/v1/decisions"), Map.of("title", "", "category", "purchase"), 400);
    String id = create(), base = "/api/v1/decisions/" + id;
    call(
        post(base + "/criteria"),
        Map.of("name", "적성", "type", "rating", "levels", List.of("a", "a", "b")),
        400);
    assertThat(call(post(base + "/criteria/recommendations"), null, 200).path("source").asText())
        .isEqualTo("template");
    create();
    var first = call(get("/api/v1/decisions?limit=1"), null, 200);
    String cursor = first.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();
    var next =
        call(get("/api/v1/decisions").param("limit", "1").param("cursor", cursor), null, 200);
    assertThat(next.path("items").get(0).path("id"))
        .isNotEqualTo(first.path("items").get(0).path("id"));
  }

  @Test
  void openApiDescribesRequestsResponsesAndClientHeader() throws Exception {
    String json =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode api = mapper.readTree(json);
    String path = "/api/v1/decisions/{decisionId}/values";
    assertThat(api.path("paths").has(path)).isTrue();
    var put = api.path("paths").path(path).path("put");
    assertThat(
            put.path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText())
        .isNotEqualTo(
            put.path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText());
    assertThat(put.path("parameters").toString()).contains("X-Client-Id");
    assertThat(
            put.path("responses")
                .path("400")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("properties")
                .path("error")
                .path("properties")
                .path("details")
                .path("additionalProperties")
                .asBoolean())
        .isTrue();
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("build/openapi.json"), json);
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void criterionEditsAndAnswerInvalidation() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String a = option(base, "A"), b = option(base, "B");
    String c = criterion(base, "적성", "rating", "score");
    call(
        put(base + "/values"),
        Map.of(
            "items",
            List.of(
                Map.of("optionId", a, "criterionId", c, "value", 5),
                Map.of("optionId", b, "criterionId", c, "value", 2))),
        200);
    var rank = call(patch(base + "/criteria/" + c), Map.of("inputMode", "rank"), 200);
    assertThat(rank.path("prefilledRanking").get(0).get(0).asText()).isEqualTo(a);
    assertThat(call(get(base + "/values"), null, 200).path("missing").size()).isEqualTo(2);
    var q = new com.seonbi.domain.Question();
    q.setDecision(repository.findById(UUID.fromString(id)).orElseThrow());
    q.setSeq(1);
    q.setType("slider_pair");
    q.setSpec(Map.of("criterionA", c));
    q.setOrigin("engine");
    q.setPhrasingSource("template");
    em.persist(q);
    var answer = new com.seonbi.domain.Answer();
    answer.setQuestion(q);
    answer.setDecision(q.getDecision());
    answer.setPreferenceKind("revealed");
    answer.setResponse(Map.of("value", 3));
    em.persist(answer);
    em.flush();
    var removed = call(delete(base + "/criteria/" + c), null, 200);
    assertThat(removed.path("invalidatedAnswers").asInt()).isEqualTo(1);
    assertThat(answer.getIsValid()).isFalse();
  }

  @Test
  void frameworkAndUnexpectedErrorsUseCommonEnvelope() throws Exception {
    var missing = call(get("/api/v1/does-not-exist"), null, 404);
    assertThat(missing.path("error").path("code").asText()).isEqualTo("NOT_FOUND");
    var unsupported = call(put("/api/v1/decisions"), Map.of(), 405);
    assertThat(unsupported.path("error").path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    mvc.perform(put("/api/v1/decisions").header("X-Client-Id", cid))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(
            header()
                .string(
                    org.springframework.http.HttpHeaders.ALLOW,
                    org.hamcrest.Matchers.containsString("GET")));
    var failure = call(get("/api/v1/test-error"), null, 500);
    assertThat(failure.path("error").path("code").asText()).isEqualTo("INTERNAL_ERROR");
    for (var error : List.of(missing, unsupported, failure)) {
      assertThat(error.path("error").path("message").asText()).isNotBlank();
      assertThat(error.path("error").path("details").isObject()).isTrue();
      assertThat(error.has("timestamp")).isFalse();
    }
    assertThat(failure.toString())
        .doesNotContain("private server exception", "IllegalStateException");
  }

  @Test
  void categoryTemplatesUseSpecifiedPresets() throws Exception {
    Map<String, Map<String, List<String>>> expected =
        Map.of(
            "purchase", Map.of("성능", List.of("매우 낮음", "낮음", "보통", "높음", "매우 높음")),
            "career", Map.of("적성", List.of("안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음")),
            "study", Map.of("난이도 적합", List.of("안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음")),
            "activity",
                Map.of(
                    "흥미",
                    List.of("별로", "그럭저럭", "끌림", "매우 끌림"),
                    "함께하는 사람",
                    List.of("매우 불만", "불만", "보통", "만족", "매우 만족")),
            "living", Map.of("분위기·환경", List.of("매우 불만", "불만", "보통", "만족", "매우 만족")),
            "etc", Map.of("만족도 기대", List.of("매우 불만", "불만", "보통", "만족", "매우 만족")));
    for (var category : expected.entrySet()) {
      String id =
          call(
                  post("/api/v1/decisions"),
                  Map.of("title", "추천 확인", "category", category.getKey()),
                  201)
              .path("id")
              .asText();
      var response = call(post("/api/v1/decisions/" + id + "/criteria/recommendations"), null, 200);
      assertThat(response.path("source").asText()).isEqualTo("template");
      for (var preset : category.getValue().entrySet()) {
        JsonNode item = null;
        for (var candidate : response.path("items"))
          if (candidate.path("name").asText().equals(preset.getKey())) item = candidate;
        assertThat(item).isNotNull();
        assertThat(item.path("levels")).isEqualTo(mapper.valueToTree(preset.getValue()));
      }
      for (var candidate : response.path("items"))
        if (candidate.path("inputMode").asText().equals("rank"))
          assertThat(candidate.path("levels").isNull()).isTrue();
    }
  }

  @Test
  void mediaTypeErrorsPreserveStatusAndJsonEnvelope() throws Exception {
    mvc.perform(
            post("/api/v1/decisions")
                .header("X-Client-Id", cid)
                .contentType("text/plain")
                .content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
        .andExpect(jsonPath("$.error.details['Content-Type']").exists());
    mvc.perform(get("/api/v1/decisions").header("X-Client-Id", cid).accept("application/xml"))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.code").value("NOT_ACCEPTABLE"))
        .andExpect(jsonPath("$.error.details.Accept").exists());
    mvc.perform(
            post("/api/v1/decisions")
                .header("X-Client-Id", cid)
                .contentType("application/json")
                .accept("application/xml")
                .content("{}"))
        .andExpect(status().isNotAcceptable())
        .andExpect(jsonPath("$.error.code").value("NOT_ACCEPTABLE"));
  }

  @Test
  void validationDetailsIdentifyFields() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String a = option(base, "같은 이름");
    assertThat(
            call(post(base + "/options"), Map.of("name", "같은 이름"), 400)
                .path("error")
                .path("details")
                .has("name"))
        .isTrue();
    assertThat(
            call(post(base + "/options"), Map.of("name", "a".repeat(61)), 400)
                .path("error")
                .path("details")
                .has("name"))
        .isTrue();
    String c = criterion(base, "적성", "rating", "score");
    for (Object invalid : List.of(6, "wrong"))
      assertThat(
              call(
                      put(base + "/values"),
                      Map.of(
                          "items",
                          List.of(Map.of("optionId", a, "criterionId", c, "value", invalid))),
                      400)
                  .path("error")
                  .path("details")
                  .has("value"))
          .isTrue();
    assertThat(
            call(get("/api/v1/decisions").param("limit", "wrong"), null, 400)
                .path("error")
                .path("details")
                .has("limit"))
        .isTrue();
    mvc.perform(
            post("/api/v1/decisions")
                .header("X-Client-Id", cid)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.body").exists());
    assertThat(
            call(
                    put(base + "/conditions"),
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "kind",
                                "threshold",
                                "criterionId",
                                c,
                                "operator",
                                "!=",
                                "value",
                                3))),
                    400)
                .path("error")
                .path("details")
                .has("operator"))
        .isTrue();
  }

  @Test
  void smallerScaleCannotInvalidateSavedScores() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String a = option(base, "A"), c = criterion(base, "적성", "rating", "score");
    call(
        put(base + "/values"),
        Map.of("items", List.of(Map.of("optionId", a, "criterionId", c, "value", 5))),
        200);
    int version = repository.findById(UUID.fromString(id)).orElseThrow().getVersion();
    var rejected =
        call(patch(base + "/criteria/" + c), Map.of("levels", List.of("낮음", "보통", "높음")), 400);
    assertThat(rejected.path("error").path("details").has("levels")).isTrue();
    assertThat(call(get(base + "/criteria"), null, 200).path("items").get(0).path("levels").size())
        .isEqualTo(5);
    assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getVersion())
        .isEqualTo(version);
    assertThat(call(get(base + "/values"), null, 200).path("items").get(0).path("value").asInt())
        .isEqualTo(5);
    call(
        put(base + "/values"),
        Map.of("items", List.of(Map.of("optionId", a, "criterionId", c, "value", 3))),
        200);
    call(patch(base + "/criteria/" + c), Map.of("levels", List.of("낮음", "보통", "높음")), 200);
  }

  @Test
  void criterionAdditionReportsOnlyActualStatusChanges() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    var draft = call(post(base + "/criteria"), Map.of("name", "기준1", "type", "rating"), 201);
    assertThat(draft.path("statusChanged").isNull()).isTrue();
    Decision d = repository.findById(UUID.fromString(id)).orElseThrow();
    d.setStatus("ANALYZED");
    repository.saveAndFlush(d);
    var changed = call(post(base + "/criteria"), Map.of("name", "기준2", "type", "rating"), 201);
    assertThat(changed.path("statusChanged").asText()).isEqualTo("QUESTIONING");
    var unchanged = call(post(base + "/criteria"), Map.of("name", "기준3", "type", "rating"), 201);
    assertThat(unchanged.path("statusChanged").isNull()).isTrue();
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void listingUsesThreeQueriesForTwentyDecisions() throws Exception {
    String chosenId = null;
    for (int i = 0; i < 20; i++) {
      String id = create(), base = "/api/v1/decisions/" + id;
      String oid = option(base, "선택지");
      if (i == 0) {
        chosenId = id;
        var f = new com.seonbi.domain.FinalChoice();
        f.setDecision(em.find(Decision.class, UUID.fromString(id)));
        f.setOption(em.find(com.seonbi.domain.Option.class, UUID.fromString(oid)));
        em.persist(f);
      }
    }
    em.flush();
    em.clear();
    var stats =
        em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
    boolean enabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    stats.clear();
    try {
      var page = call(get("/api/v1/decisions?limit=20"), null, 200);
      assertThat(page.path("items").size()).isEqualTo(20);
      assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
      boolean found = false;
      for (var item : page.path("items")) {
        assertThat(item.path("optionCount").asInt()).isEqualTo(1);
        if (item.path("id").asText().equals(chosenId)) {
          assertThat(item.path("finalChoice").path("name").asText()).isEqualTo("선택지");
          found = true;
        }
      }
      assertThat(found).isTrue();
    } finally {
      stats.setStatisticsEnabled(enabled);
    }
  }
}
