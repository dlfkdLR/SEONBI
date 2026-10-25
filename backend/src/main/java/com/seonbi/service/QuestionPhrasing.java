package com.seonbi.service;

import com.seonbi.ai.CallType;
import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmCalls.PhrasedQuestion;
import com.seonbi.ai.LlmRouter;
import com.seonbi.domain.Decision;
import com.seonbi.domain.Question;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 질문 문장 다듬기(phrase_question)를 DB 트랜잭션 밖에서 한다 (개발기획서 6.2, 12.2).
 *
 * <p>질문은 먼저 서버 템플릿 문장으로 만들어 저장·커밋한다(결정 행 잠금과 DB 연결도 이때 풀린다). 그 응답에 담긴 새 질문을 여기서 받아 (1) 읽기 전용으로 필요한
 * 입력을 모으고, (2) 트랜잭션 없이 AI를 부르고, (3) 다시 짧게 잠가서 그 사이에 질문이 답변되지 않았고 문장이 그대로일 때만 AI 문장으로 바꾼다. 기다리는 동안
 * 다른 요청이 같은 결정을 편집할 수 있고, AI가 실패·검증 탈락하면 템플릿 문장 그대로 답한다.
 */
@Service
public class QuestionPhrasing {

  private final EntityManager em;
  private final DecisionResolver resolver;
  private final QuestionFlowService flow;
  private final AiUsageService usage;
  private final ObjectProvider<LlmCalls> llm;
  private final ObjectProvider<LlmRouter> router;
  private final TransactionTemplate tx;

  public QuestionPhrasing(
      EntityManager em,
      DecisionResolver resolver,
      QuestionFlowService flow,
      AiUsageService usage,
      ObjectProvider<LlmCalls> llm,
      ObjectProvider<LlmRouter> router,
      PlatformTransactionManager transactionManager) {
    this.em = em;
    this.resolver = resolver;
    this.flow = flow;
    this.usage = usage;
    this.llm = llm;
    this.router = router;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** 1단계에서 모아 둔 호출 입력. 엔티티가 아니라 값만 담는다. */
  private record Plan(
      Map<String, Object> forAi,
      Map<String, Object> templateDisplay,
      List<List<ScenarioCheck.Expect>> expected) {}

  /**
   * 응답 payload의 새 질문(question 또는 next.question)을 AI 문장으로 바꾼다. 바꿀 수 없으면 payload를 그대로 돌려준다. 서비스 호출이
   * 끝난(커밋된) 뒤 불러야 한다.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> polish(UUID decisionId, UUID clientId, Map<String, Object> payload) {
    Map<String, Object> holder = payload;
    if (!(holder.get("question") instanceof Map<?, ?>)
        && holder.get("next") instanceof Map<?, ?> n) {
      holder = (Map<String, Object>) n;
    }
    if (!(holder.get("question") instanceof Map<?, ?> shown) || !wantsAi(decisionId)) {
      return payload;
    }
    UUID questionId = UUID.fromString(String.valueOf(shown.get("id")));

    Plan plan = tx.execute(status -> plan(decisionId, clientId, questionId));
    if (plan == null) {
      return payload;
    }
    Optional<PhrasedQuestion> phrased;
    try { // 트랜잭션 밖
      phrased = llm.getObject().phraseQuestion(plan.forAi(), usage.recorder(clientId, decisionId));
    } catch (RuntimeException e) { // AI 문제로 질문 응답이 실패하면 안 된다(6.3)
      return payload;
    }
    if (phrased.isEmpty()) {
      return payload;
    }
    Map<String, Object> updated =
        tx.execute(status -> apply(decisionId, clientId, questionId, plan, phrased.get()));
    if (updated != null) {
      holder.put("question", updated);
    }
    return payload;
  }

  private boolean wantsAi(UUID decisionId) {
    LlmCalls calls = llm.getIfAvailable();
    LlmRouter r = router.getIfAvailable();
    // 실제 제공자가 없으면(fake) 서버 템플릿 문장을 그대로 쓴다. 비용 상한(O03)에 걸려도 마찬가지다
    return calls != null
        && r != null
        && !"fake".equals(r.settings().primaryFor(CallType.PHRASE_QUESTION).provider())
        && usage.autoAllowed(decisionId);
  }

  // 1단계: 아직 템플릿 문장이고 답변이 없는 엔진 질문만 대상이다
  private Plan plan(UUID decisionId, UUID clientId, UUID questionId) {
    Decision d = resolver.get(decisionId, clientId);
    Question q = em.find(Question.class, questionId);
    if (q == null || !eligible(q, d)) {
      return null;
    }
    Map<String, Object> forAi = new LinkedHashMap<>();
    forAi.put("id", q.getId().toString());
    forAi.put("seq", q.getSeq());
    forAi.putAll(q.getDisplay());
    return new Plan(
        QuestionFlowService.deepCopy(forAi),
        QuestionFlowService.deepCopy(q.getDisplay()),
        flow.expectationsFor(d, q));
  }

  private boolean eligible(Question q, Decision d) {
    return q.getDecision().getId().equals(d.getId())
        && "engine".equals(q.getOrigin())
        && "template".equals(q.getPhrasingSource())
        && q.getDisplay() != null
        && !answered(q);
  }

  private boolean answered(Question q) {
    return !em.createQuery("select a.id from Answer a where a.question.id=:q", UUID.class)
        .setParameter("q", q.getId())
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  // 3단계: 그 사이 답변됐거나 문장이 바뀌었으면 버린다
  private Map<String, Object> apply(
      UUID decisionId, UUID clientId, UUID questionId, Plan plan, PhrasedQuestion phrased) {
    Decision d = resolver.getForUpdate(decisionId, clientId);
    Question q = em.find(Question.class, questionId);
    if (q == null || !eligible(q, d) || !plan.templateDisplay().equals(q.getDisplay())) {
      return null;
    }
    QuestionFlowService.Phrased out =
        QuestionFlowService.phrase(
            plan.forAi(), plan.templateDisplay(), plan.expected(), spec -> Optional.of(phrased));
    if (!"ai".equals(out.source())) {
      return null;
    }
    q.setDisplay(out.display());
    q.setPhrasingSource(out.source());
    return QuestionFlowService.questionObject(q);
  }
}
