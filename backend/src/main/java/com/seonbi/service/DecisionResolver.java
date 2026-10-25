package com.seonbi.service;

import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.repository.DecisionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecisionResolver {
  private final DecisionRepository decisions;

  private final EntityManager em;

  public DecisionResolver(DecisionRepository decisions, EntityManager em) {
    this.decisions = decisions;
    this.em = em;
  }

  @Transactional(readOnly = true)
  public Decision get(UUID decisionId, UUID clientId) {
    return decisions
        .findByIdAndDeletedAtIsNull(decisionId)
        .orElseThrow(
            () -> new FlowError(HttpStatus.NOT_FOUND, "NOT_FOUND", "결정을 찾을 수 없습니다.", Map.of()));
  }

  /**
   * 쓰기용 조회: 행을 잠그면서 최신 값으로 다시 읽는다. 먼저 읽고 나중에 잠그면 그 사이에 다른 요청이 바꾼 값(버전·삭제 여부)을 덮어쓴다(예: 삭제한 결정이 편집
   * 저장으로 되살아남, 버전 증가 유실). 잠금을 얻은 뒤에 삭제됐으면 404다. 호출하는 쪽 트랜잭션 안에서 부른다.
   */
  public Decision getForUpdate(UUID decisionId, UUID clientId) {
    Decision d = get(decisionId, clientId);
    em.refresh(d, LockModeType.PESSIMISTIC_WRITE);
    if (d.getDeletedAt() != null) {
      throw new FlowError(HttpStatus.NOT_FOUND, "NOT_FOUND", "결정을 찾을 수 없습니다.", Map.of());
    }
    return d;
  }

  public void ensureNotFinalized(Decision decision) {
    if ("FINALIZED".equals(decision.getStatus()))
      throw new FlowError(
          HttpStatus.CONFLICT,
          "DECISION_FINALIZED",
          "확정된 결정은 수정할 수 없습니다.",
          Map.of("decisionId", decision.getId()));
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void bumpVersion(Decision decision) {
    decision.setVersion(decision.getVersion() + 1);
    decision.setUpdatedAt(Instant.now());
  }
}
