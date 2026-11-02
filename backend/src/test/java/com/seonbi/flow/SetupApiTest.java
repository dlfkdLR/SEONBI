package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** 초기 설정 완료 저장(9-1번)과 설정 중 기준 추천(9-2번): 한 번에 저장, 롤백, 멱등성, 이후 질문 흐름 (실제 DB, Fake LLM). */
class SetupApiTest extends FlowTestBase {

  @Autowired JdbcTemplate jdbc;

  /** 후보 3개 × 기준 3개(가격·무게 숫자형, 성능 순위형)와 조건 2개를 채운 요청. 키는 요청마다 새로 만든다. */
  private Map<String, Object> request(UUID key) {
    Map<String, Object> body = new HashMap<>();
    body.put("setupKey", key.toString());
    body.put("title", "새 노트북 고르기");
    body.put("description", "통학용");
    body.put("category", "purchase");
    body.put(
        "options",
        List.of(
            Map.of("ref", "a", "name", "갤럭시북"),
            Map.of("ref", "b", "name", "그램"),
            Map.of("ref", "c", "name", "요가")));
    body.put(
        "criteria",
        List.of(
            Map.of(
                "ref",
                "price",
                "name",
                "가격",
                "type",
                "numeric",
                "direction",
                "lower_better",
                "unit",
                "만 원"),
            Map.of(
                "ref",
                "weight",
                "name",
                "무게",
                "type",
                "numeric",
                "direction",
                "lower_better",
                "unit",
                "kg"),
            Map.of("ref", "perf", "name", "성능", "type", "rating", "inputMode", "rank")));
    List<Object> values = new ArrayList<>();
    double[] price = {189, 165, 150}, weight = {1.23, 1.19, 1.45};
    String[] refs = {"a", "b", "c"};
    for (int i = 0; i < 3; i++) {
      values.add(Map.of("optionRef", refs[i], "criterionRef", "price", "value", price[i]));
      values.add(Map.of("optionRef", refs[i], "criterionRef", "weight", "value", weight[i]));
    }
    body.put("values", values);
    body.put(
        "rankings",
        List.of(Map.of("criterionRef", "perf", "order", List.of(List.of("b"), List.of("a", "c")))));
    body.put(
        "conditions",
        List.of(
            Map.of("kind", "threshold", "criterionRef", "weight", "operator", "<=", "value", 1.3),
            Map.of(
                "kind",
                "required_tag",
                "label",
                "Windows 지원",
                "satisfiedByRefs",
                List.of("a", "b"))));
    return body;
  }

  private long decisionCount() {
    return jdbc.queryForObject("select count(*) from decisions", Long.class);
  }

  private long clientDecisionCount() {
    return jdbc.queryForObject(
        "select count(*) from decisions where client_id = ?::uuid", Long.class, cid);
  }

  private long rows(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  @Test
  void 설정을_끝내면_한_번의_요청으로_결정과_하위_데이터가_연결돼_저장된다() throws Exception {
    JsonNode d = call(post("/api/v1/decisions/setup"), request(UUID.randomUUID()), 201);
    assertThat(d.path("id").asText()).isNotBlank();
    assertThat(d.path("status").asText()).isEqualTo("DRAFT");
    assertThat(d.path("options")).hasSize(3);
    assertThat(d.path("criteria")).hasSize(3);
    assertThat(d.path("readiness").path("canStartQuestions").asBoolean()).isTrue();

    // 서버가 발급한 ID로 다시 읽어도 같은 구조다
    JsonNode again = getJ("/api/v1/decisions/" + d.path("id").asText(), 200);
    Map<String, String> optionByName = new HashMap<>(), criterionByName = new HashMap<>();
    again
        .path("options")
        .forEach(o -> optionByName.put(o.path("name").asText(), o.path("id").asText()));
    again
        .path("criteria")
        .forEach(c -> criterionByName.put(c.path("name").asText(), c.path("id").asText()));
    // 가격 값: 그램은 165
    boolean ok = false;
    for (JsonNode v : again.path("values")) {
      if (v.path("optionId").asText().equals(optionByName.get("그램"))
          && v.path("criterionId").asText().equals(criterionByName.get("가격"))) {
        ok = v.path("value").asDouble() == 165;
      }
    }
    assertThat(ok).isTrue();
    // 성능 순위: 그램 1위, 갤럭시북·요가 공동 2위
    JsonNode order = again.path("rankings").get(0).path("order");
    assertThat(order.get(0).get(0).asText()).isEqualTo(optionByName.get("그램"));
    assertThat(order.get(1)).hasSize(2);
    // 조건: 숫자 조건은 무게 기준을, 태그 조건은 갤럭시북·그램만 충족. 요가는 둘 다 못 맞춰 제외
    assertThat(again.path("conditions")).hasSize(2);
    boolean yogaExcluded = false;
    for (JsonNode o : again.path("options")) {
      if (o.path("name").asText().equals("요가")) yogaExcluded = o.path("excluded").asBoolean();
    }
    assertThat(yogaExcluded).isTrue();
  }

  @Test
  void 값이_비어_있으면_422로_거절하고_아무것도_남지_않는다() throws Exception {
    Map<String, Object> body = request(UUID.randomUUID());
    body.put("values", List.of()); // 가격·무게 값이 없다
    long decisions = decisionCount(), options = rows("options"), criteria = rows("criteria");
    JsonNode e = call(post("/api/v1/decisions/setup"), body, 422);
    assertThat(e.path("error").path("code").asText()).isEqualTo("NOT_READY");
    assertThat(decisionCount()).isEqualTo(decisions);
    assertThat(rows("options")).isEqualTo(options);
    assertThat(rows("criteria")).isEqualTo(criteria);
  }

  @Test
  void 저장_도중_오류가_나면_이미_만든_후보와_기준까지_모두_롤백된다() throws Exception {
    Map<String, Object> body = request(UUID.randomUUID());
    List<Object> criteria = new ArrayList<>((List<?>) body.get("criteria"));
    // 후보와 첫 기준은 저장에 성공한 뒤 두 번째 기준에서 실패한다
    criteria.set(1, Map.of("ref", "weight", "name", "무게", "type", "bogus"));
    body.put("criteria", criteria);
    long decisions = decisionCount(), options = rows("options"), crit = rows("criteria");
    call(post("/api/v1/decisions/setup"), body, 400);
    assertThat(decisionCount()).isEqualTo(decisions);
    assertThat(rows("options")).isEqualTo(options);
    assertThat(rows("criteria")).isEqualTo(crit);
    assertThat(clientDecisionCount()).isZero();
  }

  @Test
  void 실패한_뒤_같은_키로_고쳐서_다시_보내면_저장된다() throws Exception {
    UUID key = UUID.randomUUID();
    Map<String, Object> broken = request(key);
    broken.put("values", List.of());
    call(post("/api/v1/decisions/setup"), broken, 422);
    assertThat(clientDecisionCount()).isZero();
    call(post("/api/v1/decisions/setup"), request(key), 201);
    assertThat(clientDecisionCount()).isEqualTo(1);
  }

  @Test
  void 존재하지_않는_ref를_가리키면_400이고_롤백된다() throws Exception {
    Map<String, Object> body = request(UUID.randomUUID());
    body.put(
        "conditions",
        List.of(Map.of("kind", "required_tag", "label", "x", "satisfiedByRefs", List.of("zzz"))));
    call(post("/api/v1/decisions/setup"), body, 400);
    assertThat(clientDecisionCount()).isZero();
  }

  @Test
  void 후보나_기준이_모자라면_400이다() throws Exception {
    Map<String, Object> body = request(UUID.randomUUID());
    body.put("options", List.of(Map.of("ref", "a", "name", "하나뿐")));
    call(post("/api/v1/decisions/setup"), body, 400);
    assertThat(clientDecisionCount()).isZero();
  }

  @Test
  void 같은_키로_다시_보내면_새로_만들지_않고_같은_결정을_돌려준다() throws Exception {
    UUID key = UUID.randomUUID();
    JsonNode first = call(post("/api/v1/decisions/setup"), request(key), 201);
    JsonNode second = call(post("/api/v1/decisions/setup"), request(key), 200);
    assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
    assertThat(clientDecisionCount()).isEqualTo(1);
    assertThat(second.path("options")).hasSize(3);
  }

  @Test
  void 동시에_같은_키로_보내도_결정은_하나만_생긴다() throws Exception {
    UUID key = UUID.randomUUID();
    Map<String, Object> body = request(key);
    String client = cid;
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        Callable<Integer> task =
            () ->
                mvc.perform(
                        post("/api/v1/decisions/setup")
                            .header("X-Client-Id", client)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        results.add(pool.submit(task));
      }
      for (Future<Integer> f : results) {
        assertThat(f.get()).isIn(200, 201);
      }
    } finally {
      pool.shutdown();
    }
    assertThat(clientDecisionCount()).isEqualTo(1);
  }

  @Test
  void 다른_브라우저가_같은_키를_써도_서로_다른_결정이다() throws Exception {
    UUID key = UUID.randomUUID();
    JsonNode mine = call(post("/api/v1/decisions/setup"), request(key), 201);
    cid = UUID.randomUUID().toString();
    JsonNode other = call(post("/api/v1/decisions/setup"), request(key), 201);
    assertThat(other.path("id").asText()).isNotEqualTo(mine.path("id").asText());
  }

  @Test
  void 설정_중_기준_추천은_아무것도_저장하지_않는다() throws Exception {
    long decisions = decisionCount();
    JsonNode r =
        call(
            post("/api/v1/decisions/criteria/recommendations"),
            Map.of("title", "새 노트북", "category", "purchase", "options", List.of("그램", "요가")),
            200);
    assertThat(r.path("items").size()).isGreaterThan(0);
    assertThat(decisionCount()).isEqualTo(decisions);
  }

  @Test
  void 설정을_끝낸_결정으로_질문을_시작해_분석까지_이어진다() throws Exception {
    JsonNode d = call(post("/api/v1/decisions/setup"), request(UUID.randomUUID()), 201);
    String base = "/api/v1/decisions/" + d.path("id").asText();
    JsonNode started = postJ(base + "/questions/start", null, 200);
    assertThat(started.path("status").asText()).isIn("QUESTIONING", "ANALYZED");
    if (started.path("status").asText().equals("QUESTIONING")) {
      postJ(base + "/questions/stop", null, 200);
    }
    assertThat(getJ(base + "/analysis", 200).path("rankings").size()).isGreaterThan(0);
  }

  @Test
  void 기존_결정_생성_수정_복제_삭제는_그대로_동작한다() throws Exception {
    JsonNode created = postJ("/api/v1/decisions", Map.of("title", "예전 방식", "category", "etc"), 201);
    String id = created.path("id").asText();
    call(patch("/api/v1/decisions/" + id), Map.of("title", "고침"), 200);
    JsonNode clone = postJ("/api/v1/decisions/" + id + "/duplicate", null, 201);
    assertThat(clone.path("title").asText()).isEqualTo("고침 (복사본)");
    call(delete("/api/v1/decisions/" + id), null, 204);
    getJ("/api/v1/decisions/" + id, 404);
  }
}
