package com.seonbi.service;

import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.repository.DecisionRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecisionResolver {
  private final DecisionRepository decisions;

  public DecisionResolver(DecisionRepository decisions) {
    this.decisions = decisions;
  }

  @Transactional(readOnly = true)
  public Decision get(UUID decisionId, UUID clientId) {
    return decisions
        .findByIdAndDeletedAtIsNull(decisionId)
        .orElseThrow(
            () -> new FlowError(HttpStatus.NOT_FOUND, "NOT_FOUND", "결정을 찾을 수 없습니다.", Map.of()));
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
