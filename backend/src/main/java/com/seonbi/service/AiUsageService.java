package com.seonbi.service;

import com.seonbi.ai.LlmUsage;
import com.seonbi.core.FlowError;
import com.seonbi.domain.AiUsageLog;
import com.seonbi.domain.Decision;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI 호출 상한과 사용량 기록 (개발기획서 12.2, 역할 문서 5.4).
 *
 * <ul>
 *   <li>사용자가 직접 부르는 호출(18 recommend_options, 23 recommend_criteria, 28 extract_conditions)만 클라이언트
 *       ID당 하루 {@code AI_DAILY_QUOTA_PER_CLIENT}회(기본 50)로 센다. 세 가지를 합쳐서 센다. 서버가 자동으로 부르는
 *       phrase_question· summarize_options·explain_result는 세지 않는다(대신 결과를 캐시한다).
 *   <li>세는 방법: 오늘(UTC) {@code ai_usage_logs} 행 수. 기록은 AI 호출마다(제공자 시도 하나당 한 줄, 실패 포함) 남기므로 "횟수"는 제공자
 *       호출 시도 수다 (명세에 시도/요청 구분 없음, 가장 단순한 읽기).
 *   <li>초과 처리: 18·28은 429 AI_QUOTA_EXCEEDED, 23은 오류 대신 {@link #exceeded}가 true를 돌려줘 호출자가 기본 기준 목록으로
 *       대체한다.
 *   <li>기록은 요청 트랜잭션이 되돌려져도 남도록 별도 트랜잭션으로 저장한다. 기록에는 호출 종류·제공자·모델·토큰 수·지연만 담는다(프롬프트· 사용자 원문 없음,
 *       8.1).
 * </ul>
 */
@Service
public class AiUsageService {

  /** 사용자가 직접 부르는 호출 종류(상한에 센다). */
  public static final List<String> USER_CALL_TYPES =
      List.of("recommend_options", "recommend_criteria", "extract_conditions");

  private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

  private final EntityManager em;
  private final int dailyLimit;

  public AiUsageService(
      EntityManager em, @Value("${AI_DAILY_QUOTA_PER_CLIENT:50}") int dailyLimit) {
    this.em = em;
    this.dailyLimit = dailyLimit;
  }

  public int dailyLimit() {
    return dailyLimit;
  }

  private long countToday(UUID clientId) {
    Instant since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    return em.createQuery(
            "select count(l) from AiUsageLog l where l.clientId=:cid and l.callType in :types"
                + " and l.createdAt>=:since",
            Long.class)
        .setParameter("cid", clientId)
        .setParameter("types", USER_CALL_TYPES)
        .setParameter("since", since)
        .getSingleResult();
  }

  /** 오늘 상한을 이미 다 썼는가. 사용자가 부르는 호출이 아니면 항상 false(세지 않는다). */
  public boolean exceeded(UUID clientId, String callType) {
    if (!USER_CALL_TYPES.contains(callType)) {
      return false;
    }
    return countToday(clientId) >= dailyLimit;
  }

  /** 상한 검사: 넘었으면 429 AI_QUOTA_EXCEEDED. (23번은 오류 대신 {@link #exceeded}로 판단해 템플릿으로 대체한다.) */
  public void checkOrThrow(UUID clientId, String callType) {
    if (exceeded(clientId, callType)) {
      throw new FlowError(
          HttpStatus.TOO_MANY_REQUESTS,
          "AI_QUOTA_EXCEEDED",
          "오늘 사용할 수 있는 AI 호출 횟수를 모두 사용했어요. 내일 다시 시도해 주세요.",
          Map.of("limit", dailyLimit));
    }
  }

  /**
   * LlmCalls·LlmRouter의 onUsage로 넘길 기록 콜백. 호출한 요청의 트랜잭션 안에서 저장한다. 별도 트랜잭션으로 저장하면 요청이 잡고 있는 결정 행의
   * 잠금과 ai_usage_logs의 외래키 검사가 서로 기다려 멈추기 때문이다. 대신 요청이 오류로 되돌려지면 그 요청의 기록도 함께 사라진다. 저장 실패는 요청을 깨뜨리지
   * 않고 로그만 남긴다.
   */
  public Consumer<LlmUsage> recorder(UUID clientId, UUID decisionId) {
    return usage -> {
      try {
        AiUsageLog row = new AiUsageLog();
        row.setClientId(clientId);
        row.setDecision(decisionId == null ? null : em.getReference(Decision.class, decisionId));
        row.setCallType(usage.callType());
        row.setProvider(usage.provider());
        row.setModel(
            usage.model() == null || usage.model().length() <= 50
                ? usage.model()
                : usage.model().substring(0, 50)); // model 열 길이(50)를 넘으면 커밋이 실패해 본 요청까지 되돌려진다
        row.setInputTokens(usage.inputTokens());
        row.setOutputTokens(usage.outputTokens());
        row.setLatencyMs((int) usage.latencyMs());
        row.setSuccess(usage.success());
        em.persist(row);
      } catch (RuntimeException e) {
        log.warn("AI 사용량 기록 실패", e);
      }
    };
  }
}
