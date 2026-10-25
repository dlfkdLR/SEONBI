package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.service.ConfigurationService;
import com.seonbi.service.DecisionResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 코드 리뷰의 B 파트 항목(R02·R07·R08·R09·R14·R15·O05) 회귀 테스트. */
class ConfigurationReviewFixesTest extends FlowTestBase {

  @Autowired ConfigurationService configuration;
  @Autowired DecisionResolver resolver;
  @Autowired PlatformTransactionManager txm;

  // ── R02 ───────────────────────────────────────────────────────

  @Test
  void 동시에_고쳐도_버전_증가가_유실되지_않는다() throws Exception {
    Setup s = laptops();
    UUID id = UUID.fromString(s.id());
    UUID client = UUID.fromString(cid);
    int before = getJ(s.base(), 200).path("id").isTextual() ? versionOf(id) : 0;
    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> done = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      done.add(
          pool.submit(
              () -> {
                go.await();
                configuration.putValues(
                    id, client, new com.seonbi.dto.Requests.Values(List.of(), List.of()));
                return null;
              }));
    }
    go.countDown();
    for (Future<?> f : done) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();
    // 편집이 줄을 서서 처리되므로 증가분이 모두 반영된다
    assertThat(versionOf(id)).isEqualTo(before + threads);
  }

  // 먼저 읽어 둔 뒤 다른 요청이 삭제했으면, 뒤늦게 잠그는 쪽은 되살리지 않고 404가 된다
  @Test
  void 삭제된_결정은_뒤늦은_편집_저장으로_되살아나지_않는다() throws Exception {
    Setup s = laptops();
    UUID id = UUID.fromString(s.id());
    UUID client = UUID.fromString(cid);
    CountDownLatch loaded = new CountDownLatch(1);
    CountDownLatch deleted = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(1);
    Future<Throwable> editor =
        pool.submit(
            () -> {
              try {
                new TransactionTemplate(txm)
                    .executeWithoutResult(
                        st -> {
                          resolver.get(id, client); // 먼저 읽는다
                          loaded.countDown();
                          try {
                            deleted.await(10, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          resolver.getForUpdate(id, client); // 잠그며 다시 읽는다
                        });
                return null;
              } catch (Throwable t) {
                return t;
              }
            });
    loaded.await(10, TimeUnit.SECONDS);
    configuration.delete(id, client);
    deleted.countDown();
    Throwable error = editor.get(20, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(error).isInstanceOf(com.seonbi.core.FlowError.class);
    assertThat(((com.seonbi.core.FlowError) error).getStatus().value()).isEqualTo(404);
  }

  private int versionOf(UUID id) {
    return new TransactionTemplate(txm)
        .execute(
            st ->
                ((Number)
                        em().createQuery("select d.version from Decision d where d.id=:id")
                            .setParameter("id", id)
                            .getSingleResult())
                    .intValue());
  }

  @Autowired jakarta.persistence.EntityManager entityManager;

  private jakarta.persistence.EntityManager em() {
    return entityManager;
  }

  // ── R08 · R09 ─────────────────────────────────────────────────

  @Test
  void 너무_크거나_정밀한_숫자는_저장하지_않는다() throws Exception {
    Setup s = laptops();
    String o = s.options().get(0);
    String price = s.criteria().get(0);
    for (String bad : List.of("1e400", "9007199254740993", "1" + "0".repeat(20))) {
      call(
          put(s.base() + "/values"),
          Map.of(
              "items",
              List.of(Map.of("optionId", o, "criterionId", price, "value", new BigDecimal(bad)))),
          400);
    }
    call(
        put(s.base() + "/values"),
        Map.of("items", List.of(Map.of("optionId", o, "criterionId", price, "value", 123456.789))),
        200);
    // 필수 조건의 기준값도 같은 범위
    call(
        put(s.base() + "/conditions"),
        Map.of(
            "items",
            List.of(
                Map.of(
                    "kind",
                    "threshold",
                    "criterionId",
                    price,
                    "operator",
                    "<=",
                    "value",
                    new BigDecimal("9007199254740993")))),
        400);
  }

  // ── R15 · R14 · R07 ───────────────────────────────────────────

  @Test
  void 기준_유형을_왕복해도_고정_척도_표시가_남지_않는다() throws Exception {
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    String price = s.criteria().get(0);
    call(patch(s.base() + "/criteria/" + price), Map.of("type", "rating"), 200);
    JsonNode back =
        call(
            patch(s.base() + "/criteria/" + price),
            Map.of("type", "numeric", "direction", "lower_better"),
            200);
    assertThat(back.path("scaleFrozen").asBoolean()).isFalse();
    assertThat(back.path("scaleMin").isNull()).isTrue();
  }

  @Test
  void 답변한_뒤_단계_수를_바꾸면_경고한다() throws Exception {
    Setup s = laptops();
    JsonNode q = postJ(s.base() + "/questions/start", null, 200).path("question");
    String perf = s.criteria().get(2);
    // 성능이 들어간 질문까지 답한다
    for (int i = 0; i < 4 && !q.path("criteria").toString().contains(perf); i++) {
      JsonNode res =
          postJ(
              s.base() + "/answers",
              Map.of("questionId", q.path("id").asText(), "response", Map.of("value", 2)),
              201);
      q = res.path("next").path("question");
    }
    postJ(
        s.base() + "/answers",
        Map.of(
            "questionId",
            q.path("id").asText(),
            "response",
            q.path("type").asText().equals("slider_pair")
                ? Map.of("value", 2)
                : Map.of("choice", "A")),
        201);
    JsonNode changed =
        call(
            patch(s.base() + "/criteria/" + perf),
            Map.of("levels", List.of("a", "b", "c", "d", "e", "f", "g")),
            200);
    assertThat(changed.path("warning").asText()).isEqualTo("SCALE_CHANGED_AFTER_ANSWERS");
  }

  @Test
  void 임계_조건이_걸린_기준은_순위_입력으로_바꿀_수_없다() throws Exception {
    Setup s = laptops();
    String price = s.criteria().get(0);
    call(
        put(s.base() + "/conditions"),
        Map.of(
            "items",
            List.of(
                Map.of("kind", "threshold", "criterionId", price, "operator", "<=", "value", 180))),
        200);
    call(
        patch(s.base() + "/criteria/" + price), Map.of("type", "rating", "inputMode", "rank"), 400);
  }

  // ── O05 ───────────────────────────────────────────────────────

  @Test
  void 필수_조건은_50개까지다() throws Exception {
    Setup s = laptops();
    List<Object> items = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      items.add(Map.of("kind", "required_tag", "label", "조건" + i, "satisfiedBy", List.of()));
    }
    call(put(s.base() + "/conditions"), Map.of("items", items), 400);
  }
}
