package com.seonbi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class Requests {
  private Requests() {}

  public record OptionRequest(
      @NotBlank @Size(max = 60) String name, String description, String url) {}

  public record CreateDecision(
      @NotBlank @Size(max = 100) String title,
      @Size(max = 1000) String description,
      @NotNull @Pattern(regexp = "purchase|career|study|activity|living|etc") String category,
      @Size(max = 8) List<@NotNull @Valid OptionRequest> options) {}

  public record PatchDecision(
      @Size(min = 1, max = 100) String title,
      @Size(max = 1000) String description,
      String category) {}

  public record Duplicate(@Size(min = 1, max = 100) String title) {}

  public record PatchOption(@Size(min = 1, max = 60) String name, String description, String url) {}

  public record CriterionRequest(
      String name,
      String type,
      String direction,
      String unit,
      List<String> levels,
      String inputMode,
      BigDecimal scaleMin,
      BigDecimal scaleMax,
      String source) {}

  public record ValueItem(@NotNull UUID optionId, @NotNull UUID criterionId, JsonNode value) {}

  public record Ranking(UUID criterionId, List<List<UUID>> order) {}

  public record Values(
      List<@NotNull @Valid ValueItem> items, List<@NotNull @Valid Ranking> rankings) {}

  public record ConditionRequest(
      String kind,
      UUID criterionId,
      String operator,
      BigDecimal value,
      String label,
      List<UUID> satisfiedBy,
      String source) {}

  public record Conditions(@NotNull @Size(max = 50) List<@NotNull @Valid ConditionRequest> items) {}

  public record Count(@Min(1) @Max(8) Integer count) {}
}
