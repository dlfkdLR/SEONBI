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
        d.setStatus("QUESTIONING");
        d.setQuestionLimit(3);
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
    call(get(base), null, 404);
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
  void rankValidationAutomaticPlacementAndLimits() throws Exception {
    String id = create(), base = "/api/v1/decisions/" + id;
    String a = option(base, "A"), b = option(base, "B");
    String rank = criterion(base, "흥미", "rating", "rank");
    call(
        put(base + "/values"),
        Map.of("rankings", List.of(Map.of("criterionId", rank, "order", List.of(List.of(a))))),
        400);
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
    assertThat(added.path("statusChanged").asText()).isEqualTo("QUESTIONING");
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
}
