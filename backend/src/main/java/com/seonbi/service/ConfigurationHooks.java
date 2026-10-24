package com.seonbi.service;

import com.seonbi.domain.Decision;
import java.util.*;

/** Integration contracts for A's state transition and AI services. */
public final class ConfigurationHooks {
  private ConfigurationHooks() {}

  public interface CriterionAdded {
    void transitionOnCriterionAdded(Decision decision, UUID criterionId);
  }

  public interface Recommendations {
    Map<String, Object> criteria(Decision decision);

    Map<String, Object> options(Decision decision, int count);
  }
}
