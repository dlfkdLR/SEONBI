package com.seonbi.ai;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Set;

/**
 * LLM 출력 스키마 6종 (개발기획서 6.2, 7.3의 19·28번 "추출 항목 형식"). JSON 키는 camelCase다. 검증은 record 생성자에서 하고, 틀리면
 * {@link IllegalArgumentException}이 나와 {@link Json#parse}가 SchemaViolation으로 바꾼다.
 */
public final class LlmSchemas {

  private LlmSchemas() {}

  static final Set<String> DIRECTIONS = Set.of("higher_better", "lower_better", "true_better");
  static final Set<String> CRITERION_TYPES = Set.of("numeric", "rating", "boolean");
  static final Set<String> INPUT_MODES = Set.of("score", "rank");
  static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void oneOf(String value, Set<String> allowed, String field) {
    require(value != null && allowed.contains(value), field + " 값이 올바르지 않습니다");
  }

  private static void optionalOneOf(String value, Set<String> allowed, String field) {
    require(value == null || allowed.contains(value), field + " 값이 올바르지 않습니다");
  }

  /** 7.3 19번: levels는 3~7개, 각 1~12자, 중복 불가. */
  static void checkLevels(List<String> levels) {
    if (levels == null) {
      return;
    }
    require(levels.size() >= 3 && levels.size() <= 7, "levels는 3~7개여야 합니다");
    for (String label : levels) {
      require(
          label != null && !label.isBlank() && label.length() <= 12, "levels의 각 라벨은 1~12자여야 합니다");
    }
    require(Set.copyOf(levels).size() == levels.size(), "levels에 중복이 있습니다");
  }

  /** 7.3 19번: inputMode는 rating에서만 허용. */
  static void checkInputMode(String criterionType, String inputMode) {
    require(inputMode == null || "rating".equals(criterionType), "inputMode는 rating에서만 허용됩니다");
  }

  private static List<String> listOrEmpty(List<String> v) {
    return v == null ? List.of() : List.copyOf(v);
  }

  // ── extract_conditions ────────────────────────────────────────────

  /** 추출 항목. JSON의 "type" 값(criterion|constraint|option|preference)으로 종류를 구분한다. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ExtractedCriterion.class, name = "criterion"),
    @JsonSubTypes.Type(value = ExtractedConstraint.class, name = "constraint"),
    @JsonSubTypes.Type(value = ExtractedOption.class, name = "option"),
    @JsonSubTypes.Type(value = ExtractedPreference.class, name = "preference")
  })
  public sealed interface ExtractedItem
      permits ExtractedCriterion, ExtractedConstraint, ExtractedOption, ExtractedPreference {}

  /** type=criterion. 19번 요청 필드. 항목 구분자 type과 이름이 겹쳐 기준 유형은 criterionType으로 둔다. */
  public record ExtractedCriterion(
      String name,
      String criterionType,
      String direction,
      String unit,
      List<String> levels,
      String inputMode,
      String confidence)
      implements ExtractedItem {
    public ExtractedCriterion {
      require(name != null && !name.isEmpty(), "name이 비어 있습니다");
      oneOf(criterionType, CRITERION_TYPES, "criterionType");
      optionalOneOf(direction, DIRECTIONS, "direction");
      optionalOneOf(inputMode, INPUT_MODES, "inputMode");
      oneOf(confidence, CONFIDENCES, "confidence");
      checkLevels(levels);
      checkInputMode(criterionType, inputMode);
    }
  }

  /** type=constraint. 27번 항목 필드. value는 숫자 또는 true/false다. */
  public record ExtractedConstraint(
      String kind,
      String criterionId,
      String operator,
      Object value,
      String label,
      List<String> satisfiedBy,
      String confidence)
      implements ExtractedItem {
    public ExtractedConstraint {
      oneOf(kind, Set.of("threshold", "required_tag"), "kind");
      optionalOneOf(operator, Set.of("<=", ">=", "=="), "operator");
      oneOf(confidence, CONFIDENCES, "confidence");
      require(
          value == null || value instanceof Number || value instanceof Boolean,
          "value는 숫자 또는 불리언이어야 합니다");
      if ("threshold".equals(kind)) {
        require(
            criterionId != null && operator != null && value != null,
            "threshold에는 criterionId·operator·value가 필요합니다");
      } else {
        require(label != null && !label.isEmpty(), "required_tag에는 label이 필요합니다");
      }
    }
  }

  /** type=option. 14번 요청 필드. */
  public record ExtractedOption(String name, String description, String url, String confidence)
      implements ExtractedItem {
    public ExtractedOption {
      require(name != null && !name.isEmpty(), "name이 비어 있습니다");
      oneOf(confidence, CONFIDENCES, "confidence");
    }
  }

  /** type=preference. value는 -5~+5, 양수면 A가 더 중요. */
  public record ExtractedPreference(
      String criterionA, String criterionB, int value, String confidence) implements ExtractedItem {
    public ExtractedPreference {
      require(criterionA != null && !criterionA.isEmpty(), "criterionA가 비어 있습니다");
      require(criterionB != null && !criterionB.isEmpty(), "criterionB가 비어 있습니다");
      require(value >= -5 && value <= 5, "value는 -5~5여야 합니다");
      oneOf(confidence, CONFIDENCES, "confidence");
    }
  }

  public record ExtractConditionsOutput(
      boolean relevant, List<ExtractedItem> items, String ambiguity) {
    public ExtractConditionsOutput {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  // ── recommend_criteria ────────────────────────────────────────────

  public record RecommendCriterion(
      String name,
      String type,
      String direction,
      String unit,
      List<String> levels,
      String inputMode,
      String reason) {
    public RecommendCriterion {
      require(name != null && !name.isEmpty(), "name이 비어 있습니다");
      oneOf(type, CRITERION_TYPES, "type");
      optionalOneOf(direction, DIRECTIONS, "direction");
      optionalOneOf(inputMode, INPUT_MODES, "inputMode");
      checkLevels(levels);
      checkInputMode(type, inputMode);
    }
  }

  public record RecommendCriteriaOutput(List<RecommendCriterion> criteria) {
    public RecommendCriteriaOutput {
      require(
          criteria != null && criteria.size() >= 3 && criteria.size() <= 6, "criteria는 3~6개여야 합니다");
      criteria = List.copyOf(criteria);
    }
  }

  // ── recommend_options (P2) ────────────────────────────────────────

  public record RecommendedOption(String name, String reason) {
    public RecommendedOption {
      require(name != null && !name.isEmpty(), "name이 비어 있습니다");
    }
  }

  public record RecommendOptionsOutput(List<RecommendedOption> options) {
    public RecommendOptionsOutput {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  // ── phrase_question ───────────────────────────────────────────────

  public record PhraseQuestionOutput(
      String title, String optionA, String optionB, String leftLabel, String rightLabel) {
    public PhraseQuestionOutput {
      require(title != null && !title.isEmpty(), "title이 비어 있습니다");
    }
  }

  // ── summarize_options ─────────────────────────────────────────────

  public record OptionProsCons(String optionId, List<String> pros, List<String> cons) {
    public OptionProsCons {
      require(optionId != null, "optionId가 없습니다");
      pros = listOrEmpty(pros);
      cons = listOrEmpty(cons);
    }
  }

  public record SummarizeOptionsOutput(List<OptionProsCons> options) {
    public SummarizeOptionsOutput {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  // ── explain_result ────────────────────────────────────────────────

  public record ExplainResultOutput(String summary, List<String> reasons, List<String> caveats) {
    public ExplainResultOutput {
      require(summary != null && !summary.isEmpty(), "summary가 비어 있습니다");
      reasons = listOrEmpty(reasons);
      caveats = listOrEmpty(caveats);
    }
  }
}
