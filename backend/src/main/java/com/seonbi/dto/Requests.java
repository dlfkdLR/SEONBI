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

  /** 초기 설정 완료(9-1번)의 선택지. ref는 이 요청 안에서 선택지를 가리키는 임시 이름이고, 서버가 진짜 ID를 발급한다. */
  public record SetupOption(
      @NotBlank @Size(max = 64) String ref,
      @NotBlank @Size(max = 60) String name,
      String description,
      String url) {}

  public record SetupCriterion(
      @NotBlank @Size(max = 64) String ref,
      String name,
      String type,
      String direction,
      String unit,
      List<String> levels,
      String inputMode,
      BigDecimal scaleMin,
      BigDecimal scaleMax,
      String source) {}

  public record SetupValue(
      @NotBlank String optionRef, @NotBlank String criterionRef, JsonNode value) {}

  public record SetupRanking(@NotBlank String criterionRef, List<List<@NotBlank String>> order) {}

  public record SetupCondition(
      String kind,
      String criterionRef,
      String operator,
      BigDecimal value,
      String label,
      List<@NotBlank String> satisfiedByRefs,
      String source) {}

  /** 초기 설정 5단계를 끝낸 뒤 한 번에 보내는 요청. setupKey는 같은 요청을 다시 보내도 결정이 하나만 생기게 하는 멱등 키(브라우저가 만든 UUID)다. */
  public record SetupDecision(
      @NotNull UUID setupKey,
      @NotBlank @Size(max = 100) String title,
      @Size(max = 1000) String description,
      @NotNull @Pattern(regexp = "purchase|career|study|activity|living|etc") String category,
      @NotNull @Size(min = 2, max = 8) List<@NotNull @Valid SetupOption> options,
      @NotNull @Size(min = 2, max = 7) List<@NotNull @Valid SetupCriterion> criteria,
      @Size(max = 56) List<@NotNull @Valid SetupValue> values,
      @Size(max = 7) List<@NotNull @Valid SetupRanking> rankings,
      @Size(max = 50) List<@NotNull @Valid SetupCondition> conditions) {}

  /** 결정이 아직 없는 초기 설정 3단계에서 기준 추천을 받기 위한 요청. */
  public record RecommendCriteriaDraft(
      @NotBlank @Size(max = 100) String title,
      @Size(max = 1000) String description,
      @NotNull @Pattern(regexp = "purchase|career|study|activity|living|etc") String category,
      @NotNull @Size(max = 8) List<@NotBlank @Size(max = 60) String> options) {}

  public record Count(@Min(1) @Max(8) Integer count) {}
}
