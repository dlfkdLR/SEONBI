package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.seonbi.ai.LlmUsage;
import com.seonbi.domain.AiUsageLog;
import com.seonbi.service.AiUsageService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** AI 사용량 기록과 한도 예약 (리뷰 R03·R17). 한도를 3으로 낮춰 실제 PostgreSQL에서 확인한다. */
@TestPropertySource(properties = {"AI_DAILY_QUOTA_PER_CLIENT=3", "AI_AUTO_DAILY_PER_DECISION=2"})
class UsageReservationTest extends FlowTestBase {

  @Autowired AiUsageService usage;
  @Autowired PlatformTransactionManager txm;
  @Autowired EntityManager em;

  private long rows(UUID client) {
    return new TransactionTemplate(txm)
        .execute(
            s ->
                em.createQuery("select count(l) from AiUsageLog l where l.clientId=:c", Long.class)
                    .setParameter("c", client)
                    .getSingleResult());
  }

  private static LlmUsage failedAttempt() {
    return new LlmUsage("gemini", "m", 5, 0, 12, false, "recommend_options");
  }

  // 요청이 오류로 되돌려져도 시도 기록은 남아 한도에서 사라지지 않는다
  @Test
  void 롤백된_요청의_사용량도_남는다() {
    UUID client = UUID.randomUUID();
    try {
      new TransactionTemplate(txm)
          .executeWithoutResult(
              s -> {
                assertThat(usage.tryReserve(client, "recommend_options")).isTrue();
                usage.recorder(client, null).accept(failedAttempt());
                throw new IllegalStateException("AI_UNAVAILABLE 대신");
              });
    } catch (IllegalStateException expected) {
      // 일부러 되돌린다
    }
    assertThat(rows(client)).isEqualTo(1);
  }

  // 한도 3에 요청 6개가 동시에 예약해도 3개만 통과한다 (확인과 기록 사이에 끼어들 틈이 없다)
  @Test
  void 동시에_예약해도_한도를_넘기지_않는다() throws Exception {
    UUID client = UUID.randomUUID();
    int threads = 6;
    CountDownLatch allAsked = new CountDownLatch(threads);
    AtomicInteger granted = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () ->
                  new TransactionTemplate(txm)
                      .executeWithoutResult(
                          s -> {
                            if (usage.tryReserve(client, "recommend_options")) {
                              granted.incrementAndGet();
                            }
                            allAsked.countDown();
                            try {
                              // 모두 예약을 시도할 때까지 요청(트랜잭션)을 끝내지 않는다
                              allAsked.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          })));
    }
    for (var f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();
    assertThat(granted.get()).isEqualTo(usage.dailyLimit());
  }

  @Test
  void 긴_모델_이름도_저장된다() {
    UUID client = UUID.randomUUID();
    usage
        .recorder(client, null)
        .accept(new LlmUsage("gemini", "m".repeat(80), 1, 1, 1, true, "recommend_options"));
    assertThat(rows(client)).isEqualTo(1);
    int length =
        new TransactionTemplate(txm)
            .execute(
                st ->
                    em.createQuery(
                            "select l from AiUsageLog l where l.clientId=:c", AiUsageLog.class)
                        .setParameter("c", client)
                        .getSingleResult()
                        .getModel()
                        .length());
    assertThat(length).isEqualTo(50);
  }

  // 자동 AI 호출(문장 다듬기 등)은 결정별 일일 상한을 넘으면 더 부르지 않는다 (리뷰 O03)
  @Test
  void 자동_AI_호출은_결정별_상한이_있다() throws Exception {
    Setup a = laptops();
    Setup b = laptops();
    UUID decision = UUID.fromString(a.id());
    assertThat(usage.autoAllowed(decision)).isTrue();
    for (int i = 0; i < 2; i++) {
      usage
          .recorder(UUID.randomUUID(), decision)
          .accept(new LlmUsage("gemini", "m", 1, 1, 1, true, "explain_result"));
    }
    assertThat(usage.autoAllowed(decision)).isFalse();
    assertThat(usage.autoAllowed(UUID.fromString(b.id()))).isTrue(); // 다른 결정은 영향 없음
    // 사용자 직접 호출 기록은 자동 호출 상한에 세지 않는다
    usage
        .recorder(UUID.randomUUID(), UUID.fromString(b.id()))
        .accept(new LlmUsage("gemini", "m", 1, 1, 1, true, "recommend_options"));
    assertThat(usage.autoAllowed(UUID.fromString(b.id()))).isTrue();
  }
}
