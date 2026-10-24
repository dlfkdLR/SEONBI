package com.seonbi.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class CRequests {
  private CRequests() {}

  public record ExtractInput(
      @NotBlank @Pattern(regexp = "other_criterion|other_condition|reason|add_option")
          String context,
      UUID questionId,
      @NotBlank @Size(max = 500) String text) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = CriterionItem.class, name = "criterion"),
    @JsonSubTypes.Type(value = ConstraintItem.class, name = "constraint"),
    @JsonSubTypes.Type(value = OptionItem.class, name = "option"),
    @JsonSubTypes.Type(value = PreferenceItem.class, name = "preference")
  })
  public sealed interface ExtractedItem
      permits CriterionItem, ConstraintItem, OptionItem, PreferenceItem {
    String type();

    String confidence();
  }

  public record CriterionItem(
      @Pattern(regexp = "criterion") String type,
      @NotBlank @Size(max = 40) String name,
      @NotBlank @Pattern(regexp = "numeric|rating|boolean") String criterionType,
      String direction,
      @Size(max = 20) String unit,
      @Size(min = 3, max = 7) List<@Size(min = 1, max = 12) String> levels,
      String inputMode,
      @Pattern(regexp = "high|medium|low") String confidence)
      implements ExtractedItem {}

  public record ConstraintItem(
      @Pattern(regexp = "constraint") String type,
      @NotBlank @Pattern(regexp = "threshold|required_tag") String kind,
      UUID criterionId,
      @Pattern(regexp = "<=|>=|==") String operator,
      JsonNode value,
      @Size(max = 60) String label,
      List<UUID> satisfiedBy,
      @Pattern(regexp = "high|medium|low") String confidence)
      implements ExtractedItem {}

  public record OptionItem(
      @Pattern(regexp = "option") String type,
      @NotBlank @Size(max = 60) String name,
      @Size(max = 1000) String description,
      @Size(max = 2048) String url,
      @Pattern(regexp = "high|medium|low") String confidence)
      implements ExtractedItem {}

  public record PreferenceItem(
      @Pattern(regexp = "preference") String type,
      @NotBlank @Size(max = 60) String criterionA,
      @NotBlank @Size(max = 60) String criterionB,
      @NotNull @Min(-5) @Max(5) Integer value,
      @Pattern(regexp = "high|medium|low") String confidence)
      implements ExtractedItem {}

  public record ConfirmInput(@NotEmpty List<@NotNull @Valid ExtractedItem> items) {}

  public record FinalChoice(@NotNull UUID optionId, @Size(max = 1000) String memo) {}
}
