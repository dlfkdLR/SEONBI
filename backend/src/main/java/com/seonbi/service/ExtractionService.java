package com.seonbi.service;

import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmCalls.ExtractResult;
import com.seonbi.ai.LlmException;
import com.seonbi.ai.Prompts.DecisionContext;
import com.seonbi.ai.Prompts.Ref;
import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.service.StateRules.Action;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 28번 자연어 조건 추출의 AI 쪽 (역할 문서 5.3). C의 컨트롤러가 이 서비스를 불러 추출 결과를 받고, free_text_inputs 저장과 응답 조립은 C가 한다.
 * 여기서는 상태 검사, 호출 상한(12.2: 28번은 사용자 호출이라 센다), LLM 호출, 오류 변환만 맡는다.
 *
 * <ul>
 *   <li>FINALIZED면 409 DECISION_FINALIZED, 상한을 넘으면 429 AI_QUOTA_EXCEEDED, LLM이 실패하면 503
 *       AI_UNAVAILABLE(fallback 없음).
 *   <li>결과의 {@code irrelevant}가 true면 C가 422 IRRELEVANT_INPUT으로 응답한다(6.3 고정 문구). ambiguity는
 *       needsClarification으로 옮긴다.
 *   <li>입력 길이(1~500자)는 C의 요청 검증에서 막는다.
 * </ul>
 */
@Service
@Transactional
public class ExtractionService {

  private final EntityManager em;
  private final LlmCalls calls;
  private final AiUsageService usage;

  public ExtractionService(EntityManager em, LlmCalls calls, AiUsageService usage) {
    this.em = em;
    this.calls = calls;
    this.usage = usage;
  }

  /**
   * @param questionContext 질문에 연결된 입력이면 그 질문의 문장(없으면 null)
   */
  public ExtractResult extract(Decision d, String text, String questionContext) {
    FlowErrors.raiseIf(
        StateRules.check(Action.FREE_INPUT, DecisionStatus.valueOf(d.getStatus())),
        FlowErrors.details("decisionId", d.getId()));
    usage.checkOrThrow(Requester.of(d), "extract_conditions"); // 넘으면 429
    try {
      return calls.extractConditions(
          context(d), text, questionContext, usage.recorder(Requester.of(d), d.getId()));
    } catch (LlmException e) {
      throw new FlowError(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "잠시 후 다시 입력해 주세요.", Map.of());
    }
  }

  private DecisionContext context(Decision d) {
    List<Ref> options =
        em
            .createQuery(
                "select o from Option o where o.decision.id=:id order by o.position",
                com.seonbi.domain.Option.class)
            .setParameter("id", d.getId())
            .getResultList()
            .stream()
            .map(o -> new Ref(o.getId().toString(), o.getName()))
            .toList();
    List<Ref> criteria =
        em
            .createQuery(
                "select c from Criterion c where c.decision.id=:id order by c.position",
                com.seonbi.domain.Criterion.class)
            .setParameter("id", d.getId())
            .getResultList()
            .stream()
            .map(c -> new Ref(c.getId().toString(), c.getName()))
            .toList();
    return new DecisionContext(
        d.getTitle(), d.getCategory(), d.getDescription(), options, criteria);
  }
}
