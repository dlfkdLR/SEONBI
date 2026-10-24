package com.seonbi.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CResponses {
  private CResponses() {}

  public record ExtractInput(
      UUID id, String status, List<CRequests.ExtractedItem> extracted, String needsClarification) {}

  public record CreatedItems(
      List<UUID> conditions, List<UUID> criteria, List<UUID> options, List<UUID> answers) {}

  public record ConfirmInput(String status, CreatedItems created, String statusChanged) {}

  public record RejectInput(String status) {}

  public record FinalChoice(
      UUID decisionId,
      UUID optionId,
      boolean matchedRecommendation,
      Instant finalizedAt,
      String status) {}
}
