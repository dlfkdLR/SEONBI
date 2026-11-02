package com.seonbi.service;

import com.seonbi.domain.Decision;
import java.util.*;

/** Integration contracts for A's state transition and AI services. */
public final class ConfigurationHooks {
  private ConfigurationHooks() {}

  public interface CriterionAdded {
    String onCriterionAdded(Decision decision, UUID criterionId);
  }

  public interface Recommendations {
    Map<String, Object> criteria(Decision decision);

    Map<String, Object> options(Decision decision, int count);

    /** 결정이 아직 DB에 없는 초기 설정 단계의 기준 추천. 아무것도 저장하지 않는다. */
    Map<String, Object> criteriaDraft(
        UUID clientId, String title, String category, String purpose, List<String> optionNames);
  }
}
