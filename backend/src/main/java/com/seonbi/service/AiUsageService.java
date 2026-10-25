package com.seonbi.service;

import com.seonbi.ai.LlmUsage;
import com.seonbi.core.FlowError;
import com.seonbi.domain.AiUsageLog;
import com.seonbi.domain.Decision;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 호출 상한과 사용량 기록 (개발기획서 12.2, 역할 문서 5.4).
 *
 * <ul>
 *   <li>사용자가 직접 부르는 호출(18 recommend_options, 23 recommend_criteria, 28 extract_conditions)만 클라이언트
 *       ID당 하루 {@code AI_DAILY_QUOTA_PER_CLIENT}회(기본 50)로 센다. 세 가지를 합쳐서 센다. 서버가 자동으로 부르는
 *       phrase_question· summarize_options·explain_result는 세지 않는다(대신 결과를 캐시한다).
 *   <li>세는 방법: 오늘(UTC) {@code ai_usage_logs} 행 수 + 지금 진행 중인 호출 수. 기록은 제공자 시도 하나당 한 줄(재시도·실패 포함)이다.
 *   <li><b>원자적 예약</b>: 확인과 기록 사이에 다른 요청이 끼어들어 한도를 넘기지 않도록, 호출 전에 {@link #tryReserve}로 자리를 잡고 요청이
 *       끝나면 놓는다. 같은 서버 안에서는 클라이언트 ID별로 직렬화한다(서버를 여러 대로 늘리면 DB 잠금으로 바꿔야 한다).
 *   <li>초과 처리: 18·28은 429 AI_QUOTA_EXCEEDED, 23은 오류 대신 {@link #tryReserve}가 false를 돌려줘 호출자가 기본 기준
 *       목록으로 대체한다.
 *   <li><b>기록은 요청이 끝난 뒤 별도 트랜잭션으로 저장한다.</b> 요청이 AI_UNAVAILABLE 같은 오류로 되돌려져도 시도 기록은 남는다(비용이 생긴 시도가
 *       한도에서 사라지지 않게). 요청 트랜잭션이 결정 행을 잠근 채로 같은 시점에 외래키가 걸린 행을 넣으면 서로 기다리므로, 반드시 요청 트랜잭션이 끝난
 *       뒤(afterCompletion)에 넣는다. 기록에는 호출 종류·제공자·모델·토큰 수·지연만 담는다(프롬프트·사용자 원문 없음, 8.1).
 * </ul>
 */
@Service
public class AiUsageService {

  /** 사용자가 직접 부르는 호출 종류(상한에 센다). */
  public static final List<String> USER_CALL_TYPES =
      List.of("recommend_options", "recommend_criteria", "extract_conditions");

  /** 서버가 자동으로 부르는 호출 종류. 사용자 한도에는 세지 않지만 비용을 막으려고 결정별·전체 일일 상한을 따로 둔다. */
  public static final List<String> AUTO_CALL_TYPES =
      List.of("phrase_question", "summarize_options", "explain_result");

  private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

  private final EntityManager em;
  private final TransactionTemplate separateTx;
  private final int dailyLimit;
  private final int autoPerDecision;
  private final int autoTotal;
  // 클라이언트별 진행 중 호출 수와 직렬화용 자물쇠
  private final ConcurrentHashMap<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

  public AiUsageService(
      EntityManager em,
      PlatformTransactionManager transactionManager,
      @Value("${AI_DAILY_QUOTA_PER_CLIENT:50}") int dailyLimit,
      @Value("${AI_AUTO_DAILY_PER_DECISION:40}") int autoPerDecision,
      @Value("${AI_AUTO_DAILY_TOTAL:3000}") int autoTotal) {
    this.em = em;
    this.separateTx = new TransactionTemplate(transactionManager);
    this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.dailyLimit = dailyLimit;
    this.autoPerDecision = autoPerDecision;
    this.autoTotal = autoTotal;
  }

  /**
   * 자동 AI 호출(문장 다듬기·장단점·설명)을 지금 해도 되는가. 결정 하나가 오늘 쓴 시도 수와 서버 전체가 오늘 쓴 시도 수가 상한 아래일 때만 true다. 입력을 계속
   * 바꿔 새 분석을 만들게 하는 방식으로 비용을 키우지 못하게 한다. 막히면 호출하지 않고 템플릿 문장을 쓴다.
   */
  public boolean autoAllowed(UUID decisionId) {
    Instant since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    return Boolean.TRUE.equals(
        separateTx.execute(
            status -> {
              long total =
                  em.createQuery(
                          "select count(l) from AiUsageLog l where l.callType in :types and"
                              + " l.createdAt>=:since",
                          Long.class)
                      .setParameter("types", AUTO_CALL_TYPES)
                      .setParameter("since", since)
                      .getSingleResult();
              if (total >= autoTotal) {
                return false;
              }
              long mine =
                  em.createQuery(
                          "select count(l) from AiUsageLog l where l.decision.id=:id and"
                              + " l.callType in :types and l.createdAt>=:since",
                          Long.class)
                      .setParameter("id", decisionId)
                      .setParameter("types", AUTO_CALL_TYPES)
                      .setParameter("since", since)
                      .getSingleResult();
              return mine < autoPerDecision;
            }));
  }

  public int dailyLimit() {
    return dailyLimit;
  }

  private long countToday(UUID clientId) {
    Instant since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    // 요청 트랜잭션과 무관하게 이미 저장된 행만 세므로 새 트랜잭션에서 읽는다
    return separateTx.execute(
        status ->
            em.createQuery(
                    "select count(l) from AiUsageLog l where l.clientId=:cid and l.callType in"
                        + " :types and l.createdAt>=:since",
                    Long.class)
                .setParameter("cid", clientId)
                .setParameter("types", USER_CALL_TYPES)
                .setParameter("since", since)
                .getSingleResult());
  }

  /**
   * 호출 자리를 잡는다. 사용자가 부르는 호출이 아니면 항상 true(세지 않는다). 오늘 상한과 진행 중인 호출을 합쳐 이미 다 찼으면 false. 잡은 자리는 현재
   * 트랜잭션이 끝날 때(기록을 저장한 뒤) 자동으로 놓는다. 트랜잭션 밖에서 부르면 바로 놓는다.
   */
  public boolean tryReserve(UUID clientId, String callType) {
    if (!USER_CALL_TYPES.contains(callType)) {
      return true;
    }
    synchronized (locks.computeIfAbsent(clientId, k -> new Object())) {
      AtomicInteger running = inFlight.computeIfAbsent(clientId, k -> new AtomicInteger());
      if (countToday(clientId) + running.get() >= dailyLimit) {
        return false;
      }
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        running.incrementAndGet();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public int getOrder() {
                return Integer.MAX_VALUE; // 기록 저장(아래 PendingUsage)이 먼저 끝난 뒤에 놓는다
              }

              @Override
              public void afterCompletion(int status) {
                running.decrementAndGet();
              }
            });
      }
      return true;
    }
  }

  /** 상한 검사: 자리를 못 잡으면 429 AI_QUOTA_EXCEEDED. (23번은 오류 대신 {@link #tryReserve}로 판단해 템플릿으로 대체한다.) */
  public void checkOrThrow(UUID clientId, String callType) {
    if (!tryReserve(clientId, callType)) {
      throw new FlowError(
          HttpStatus.TOO_MANY_REQUESTS,
          "AI_QUOTA_EXCEEDED",
          "오늘 사용할 수 있는 AI 호출 횟수를 모두 사용했어요. 내일 다시 시도해 주세요.",
          Map.of("limit", dailyLimit));
    }
  }

  /**
   * LlmCalls·LlmRouter의 onUsage로 넘길 기록 콜백. 호출한 요청의 트랜잭션이 끝난 뒤(커밋이든 롤백이든) 별도 트랜잭션에서 저장한다. 저장 실패는 요청을
   * 깨뜨리지 않고 로그만 남긴다.
   */
  public Consumer<LlmUsage> recorder(UUID clientId, UUID decisionId) {
    return usage -> {
      Pending row = new Pending(clientId, decisionId, usage);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        pendingOfCurrentTx().rows.add(row);
      } else {
        save(List.of(row));
      }
    };
  }

  // 현재 트랜잭션에 딸린 대기 목록. 처음 필요할 때 만들고 트랜잭션이 끝나면 저장한다
  private PendingUsage pendingOfCurrentTx() {
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (sync instanceof PendingUsage pending) {
        return pending;
      }
    }
    PendingUsage pending = new PendingUsage();
    TransactionSynchronizationManager.registerSynchronization(pending);
    return pending;
  }

  private record Pending(UUID clientId, UUID decisionId, LlmUsage usage) {}

  private final class PendingUsage implements TransactionSynchronization {
    final List<Pending> rows = new ArrayList<>();

    @Override
    public int getOrder() {
      return Integer.MAX_VALUE - 1; // 자리 놓기보다 먼저
    }

    @Override
    public void afterCompletion(int status) {
      save(rows);
    }
  }

  private void save(List<Pending> rows) {
    if (rows.isEmpty()) {
      return;
    }
    try {
      separateTx.executeWithoutResult(
          status -> {
            for (Pending p : rows) {
              persist(p, p.decisionId());
            }
          });
    } catch (RuntimeException e) {
      // 결정 행이 사라졌거나 제약에 걸렸으면 결정 연결 없이 다시 시도한다
      log.warn("AI 사용량 기록 실패, 결정 연결 없이 다시 시도", e);
      try {
        separateTx.executeWithoutResult(
            status -> {
              for (Pending p : rows) {
                persist(p, null);
              }
            });
      } catch (RuntimeException again) {
        log.warn("AI 사용량 기록 실패", again);
      }
    }
  }

  private void persist(Pending p, UUID decisionId) {
    LlmUsage usage = p.usage();
    AiUsageLog row = new AiUsageLog();
    row.setClientId(p.clientId());
    row.setDecision(decisionId == null ? null : em.getReference(Decision.class, decisionId));
    row.setCallType(usage.callType());
    row.setProvider(usage.provider());
    row.setModel(cut(usage.model(), 50)); // 열 길이를 넘으면 커밋이 실패한다
    row.setInputTokens(usage.inputTokens());
    row.setOutputTokens(usage.outputTokens());
    row.setLatencyMs((int) usage.latencyMs());
    row.setSuccess(usage.success());
    em.persist(row);
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
