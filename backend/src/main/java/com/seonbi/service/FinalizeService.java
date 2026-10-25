package com.seonbi.service;

import static com.seonbi.service.FlowErrors.details;

import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.domain.FinalChoice;
import com.seonbi.domain.Option;
import com.seonbi.service.StateRules.Action;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최종 선택 확정 로직 (개발기획서 4.2, 7.3 47번). API(컨트롤러)는 C가 소유하고 상태 로직은 A가 맡는다. C의 컨트롤러가 {@link
 * #finalizeDecision}을 부른다.
 *
 * <ul>
 *   <li>ANALYZED에서만 된다: DRAFT·QUESTIONING은 409 INVALID_STATE, FINALIZED는 409 DECISION_FINALIZED.
 *   <li>상태 검사가 먼저, 그다음 선택지 검사: 모르는 선택지 404 NOT_FOUND, 제외된 선택지 409 OPTION_EXCLUDED.
 *   <li>recommendation_snapshot에는 확정 시점의 39번 응답 전체(provisional=false, computedAt 포함)를 저장한다. 이후 39번은
 *       이 스냅샷을 그대로 돌려준다.
 *   <li>matched_recommendation = 고른 선택지가 39번의 추천(1위)과 같은지.
 * </ul>
 */
@Service
@Transactional
public class FinalizeService {

  private final EntityManager em;
  private final DecisionResolver resolver;
  private final AnalysisService analysis;

  public FinalizeService(EntityManager em, DecisionResolver resolver, AnalysisService analysis) {
    this.em = em;
    this.resolver = resolver;
    this.analysis = analysis;
  }

  /** 47번. 7.3의 201 응답 본문을 돌려준다. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> finalizeDecision(
      UUID decisionId, UUID clientId, UUID optionId, String memo) {
    Decision d = resolver.getForUpdate(decisionId, clientId);
    Map<String, Object> details = details("decisionId", d.getId());
    FlowErrors.raiseIf(
        StateRules.check(Action.FINALIZE, DecisionStatus.valueOf(d.getStatus())), details);
    Option option =
        em.createQuery(
                "select o from Option o where o.id=:oid and o.decision.id=:did", Option.class)
            .setParameter("oid", optionId)
            .setParameter("did", d.getId())
            .getResultStream()
            .findFirst()
            .orElseThrow(
                () ->
                    FlowErrors.notFound(
                        "알 수 없는 선택지입니다.",
                        details("decisionId", d.getId(), "optionId", optionId.toString())));
    Map<String, Object> result = analysis.analysisOf(d);
    boolean excluded =
        ((List<Map<String, Object>>) result.get("excludedOptions"))
            .stream().anyMatch(e -> optionId.toString().equals(e.get("optionId")));
    if (excluded) {
      throw new FlowError(
          HttpStatus.CONFLICT,
          "OPTION_EXCLUDED",
          "필수 조건을 만족하지 못해 제외된 선택지는 고를 수 없습니다.",
          details("decisionId", d.getId(), "optionId", optionId.toString()));
    }
    result.put("provisional", false);
    boolean matched =
        optionId
            .toString()
            .equals(((Map<String, Object>) result.get("recommendation")).get("optionId"));
    Instant now = Instant.now();
    FinalChoice row = new FinalChoice();
    row.setDecision(d);
    row.setOption(option);
    row.setMemo(memo);
    row.setMatchedRecommendation(matched);
    row.setRecommendationSnapshot(result);
    row.setFinalizedAt(now);
    em.persist(row);
    d.setStatus(StateRules.afterFinalize(DecisionStatus.valueOf(d.getStatus())).name());
    d.setUpdatedAt(now);
    return details(
        "decisionId", d.getId().toString(),
        "optionId", optionId.toString(),
        "matchedRecommendation", matched,
        "finalizedAt", now.toString(),
        "status", d.getStatus());
  }
}
