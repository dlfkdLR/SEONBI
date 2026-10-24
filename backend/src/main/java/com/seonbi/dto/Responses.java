package com.seonbi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Responses {
  private Responses() {}

  public record Option(
      UUID id,
      String name,
      String description,
      String url,
      boolean excluded,
      List<String> excludedReasons,
      String warning) {}

  public record Criterion(
      UUID id,
      String name,
      String type,
      String direction,
      String unit,
      List<String> levels,
      String inputMode,
      BigDecimal scaleMin,
      BigDecimal scaleMax,
      Boolean scaleFrozen,
      String source,
      String statusChanged,
      String warning,
      List<List<UUID>> prefilledRanking) {}

  public record Value(UUID optionId, UUID criterionId, JsonNode value) {}

  public record Ranking(UUID criterionId, List<List<UUID>> order) {}

  public record Missing(UUID optionId, UUID criterionId) {}

  public record Values(List<Value> items, List<Ranking> rankings, List<Missing> missing) {}

  public record Condition(
      UUID id,
      String kind,
      UUID criterionId,
      String operator,
      BigDecimal value,
      String label,
      List<UUID> satisfiedBy,
      String source) {}

  public record ExcludedOption(UUID optionId, List<String> reasons) {}

  public record Conditions(
      List<Condition> items, List<ExcludedOption> excludedOptions, String warning) {}

  public record Readiness(boolean canStartQuestions, List<String> missing) {}

  public record CreatedDecision(
      UUID id,
      String title,
      String description,
      String category,
      String status,
      List<Option> options,
      Instant createdAt,
      Instant updatedAt) {}

  public record Decision(
      UUID id,
      String title,
      String description,
      String category,
      String status,
      List<Option> options,
      List<Criterion> criteria,
      List<Value> values,
      List<Ranking> rankings,
      List<Condition> conditions,
      Readiness readiness,
      Instant createdAt,
      Instant updatedAt) {}

  public record FinalChoice(UUID optionId, String name) {}

  public record DecisionItem(
      UUID id,
      String title,
      String status,
      int optionCount,
      FinalChoice finalChoice,
      Instant updatedAt) {}

  public record Decisions(List<DecisionItem> items, String nextCursor) {}

  public record Options(List<Option> items) {}

  public record Criteria(List<Criterion> items) {}

  public record DeletedCriterion(int invalidatedAnswers) {}

  public record RecommendedCriterion(
      String name,
      String type,
      String direction,
      String unit,
      List<String> levels,
      String inputMode,
      String reason) {}

  public record CriterionRecommendations(List<RecommendedCriterion> items, String source) {}

  public record RecommendedOption(String name, String reason) {}

  public record OptionRecommendations(List<RecommendedOption> items, String notice) {}
}
