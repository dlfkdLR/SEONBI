package com.seonbi.service;

import com.seonbi.ai.CallType;
import com.seonbi.ai.DefaultCriteria;
import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmCalls.CriteriaRecommendation;
import com.seonbi.ai.LlmRouter;
import com.seonbi.ai.LlmSchemas.RecommendCriterion;
import com.seonbi.ai.LlmSchemas.RecommendedOption;
import com.seonbi.ai.Prompts.DecisionContext;
import com.seonbi.ai.Prompts.Ref;
import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * B의 구성 서비스가 부르는 A의 연결 지점 (역할 문서 5.1·5.3·5.4). B는 {@link ConfigurationHooks}의 인터페이스만 알고, 이 클래스가 A의
 * 상태 전이 서비스와 AI 호출을 연결한다.
 *
 * <ul>
 *   <li>{@link #onCriterionAdded}: 19번 기준 추가 후 상태 전이와 질문 상한 처리. 반환값이 응답의 statusChanged다.
 *   <li>{@link #criteria}: 23번 기준 추천. 상한을 넘었거나 LLM이 실패하면 오류 대신 카테고리별 기본 기준 목록(source=template).
 *   <li>{@link #options}: 18번 선택지 추천(P2). 상한을 넘으면 429, LLM이 실패하면 503 AI_UNAVAILABLE.
 * </ul>
 */
@Component
public class AiHooks
    implements ConfigurationHooks.CriterionAdded, ConfigurationHooks.Recommendations {

  static final String OPTIONS_NOTICE = "AI 추천 후보입니다. 사양과 가격은 직접 확인 후 입력해 주세요.";

  private final QuestionFlowService flow;
  private final LlmCalls calls;
  private final AiUsageService usage;
  private final EntityManager em;
  private final LlmRouter router;

  public AiHooks(
      QuestionFlowService flow,
      LlmCalls calls,
      AiUsageService usage,
      EntityManager em,
      LlmRouter router) {
    this.flow = flow;
    this.calls = calls;
    this.usage = usage;
    this.em = em;
    this.router = router;
  }

  /** 이 호출에 쓰는 모델이 실제 제공자가 아닌 fake(테스트·로컬 기본)인가. fake는 AI 추천으로 치지 않는다. */
  private boolean usesFake(CallType call) {
    return "fake".equals(router.settings().primaryFor(call).provider());
  }

  @Override
  public String onCriterionAdded(Decision decision, UUID criterionId) {
    return flow.onCriterionAdded(decision, criterionId);
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

  private static Map<String, Object> criterionMap(RecommendCriterion c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", c.name());
    m.put("type", c.type());
    putIfPresent(m, "direction", c.direction());
    putIfPresent(m, "unit", c.unit());
    putIfPresent(m, "inputMode", c.inputMode());
    putIfPresent(m, "levels", c.levels());
    m.put("reason", c.reason());
    return m;
  }

  private static void putIfPresent(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value);
    }
  }

  private static Map<String, Object> criteriaResult(List<RecommendCriterion> items, String source) {
    List<Object> list = new ArrayList<>();
    items.forEach(c -> list.add(criterionMap(c)));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", list);
    out.put("source", source);
    return out;
  }

  @Override
  @Transactional
  public Map<String, Object> criteria(Decision d) {
    // 23번은 상한을 넘어도 오류 대신 기본 기준 목록을 돌려준다(12.2)
    // (실제 제공자를 설정하지 않은 로컬·테스트 환경도 같은 목록을 template으로 돌려준다)
    if (usesFake(CallType.RECOMMEND_CRITERIA)
        || !usage.tryReserve(Requester.of(d), "recommend_criteria")) {
      return criteriaResult(DefaultCriteria.forCategory(d.getCategory()), "template");
    }
    CriteriaRecommendation r =
        calls.recommendCriteria(context(d), usage.recorder(Requester.of(d), d.getId()));
    return criteriaResult(r.items(), r.source());
  }

  @Override
  @Transactional
  public Map<String, Object> options(Decision d, int count) {
    if (usesFake(CallType.RECOMMEND_OPTIONS)) { // 실제 제공자가 없으면 기능을 쓸 수 없다(6.2: 실패 시 기능 숨김)
      throw new FlowError(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "지금은 선택지 추천을 사용할 수 없어요.", Map.of());
    }
    usage.checkOrThrow(Requester.of(d), "recommend_options"); // 넘으면 429
    Optional<List<RecommendedOption>> r =
        calls.recommendOptions(context(d), count, usage.recorder(Requester.of(d), d.getId()));
    if (r.isEmpty()) {
      throw new FlowError(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "지금은 선택지 추천을 사용할 수 없어요.", Map.of());
    }
    List<Object> items = new ArrayList<>();
    for (RecommendedOption o : r.get()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", o.name());
      m.put("reason", o.reason());
      items.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", items);
    out.put("notice", OPTIONS_NOTICE);
    return out;
  }
}
