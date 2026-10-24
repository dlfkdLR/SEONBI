package com.seonbi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 분석 API 응답·요청: 39~46번 (개발기획서 7.3 "분석", 4.2, 5.6~5.9).
 *
 * <p>가정:
 *
 * <ul>
 *   <li>42번 rawValue는 숫자로 읽었다(단계형·예/아니요의 원시값 표기는 명세에 없다).
 *   <li>43번 points의 21개 제한은 걸지 않는다.
 *   <li>45번 가중치 항목은 임계값이 있는 형태와 threshold: null만 있는 형태(키 4개)를 별도 record로 둔다.
 *   <li>46번 normalizedWeights 항목은 요청과 같은 {criterionId, weight}로 읽었고, rankings 항목은 예시대로 {rank,
 *       optionId, score}다. 요청의 가중치 값 범위는 명세에 없어 제한하지 않는다.
 *   <li>시각(computedAt)은 Instant(ISO 8601 UTC)이다.
 * </ul>
 */
public final class AnalysisDtos {

  private AnalysisDtos() {}

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void checkInterval(List<Double> interval) {
    require(interval != null && interval.size() == 2, "interval은 [하한, 상한]입니다");
  }

  // ── 40 ────────────────────────────────────────────────────────

  public record WeightItem(
      String criterionId,
      String name,
      double mean,
      List<Double> interval,
      Integer statedRank,
      Integer revealedRank) {
    public WeightItem {
      checkInterval(interval);
    }
  }

  /** 40번 GET /weights. */
  public record WeightsResponse(List<WeightItem> items, double intervalLevel) {}

  // ── 41 ────────────────────────────────────────────────────────

  public record RankingItem(
      int rank, String optionId, String name, double score, double topProbability) {}

  /** 41번 GET /rankings. */
  public record RankingsResponse(List<RankingItem> items) {}

  // ── 42 ────────────────────────────────────────────────────────

  public record ScoreCell(
      String criterionId, double rawValue, double normalized, double contribution) {}

  public record ScoreItem(String optionId, List<ScoreCell> cells) {}

  /** 42번 GET /scores. */
  public record ScoresResponse(List<String> criteria, List<ScoreItem> items) {}

  // ── 43 ────────────────────────────────────────────────────────

  /** scores는 optionId → 점수. */
  public record SensitivityPoint(double weight, Map<String, Double> scores) {}

  /** from은 자바 예약어는 아니지만 다른 언어와 맞추려고 JSON 이름만 from으로 둔다. */
  public record Crossover(double weight, @JsonProperty("from") String from, String to) {}

  public record SensitivityItem(
      String criterionId,
      double currentWeight,
      List<SensitivityPoint> points,
      List<Crossover> crossovers) {}

  /** 43번 GET /sensitivity. */
  public record SensitivityResponse(List<SensitivityItem> items) {}

  // ── 44 ────────────────────────────────────────────────────────

  public record Competitor(String optionId, double probability) {}

  /** 44번 GET /stability. */
  public record StabilityResponse(
      String optionId,
      double score,
      String level,
      double localPerturbationKeepRate,
      List<Competitor> competitors,
      String text) {
    public StabilityResponse {
      require(Set.of("HIGH", "MEDIUM", "LOW").contains(level), "level은 HIGH|MEDIUM|LOW입니다");
    }
  }

  // ── 45 ────────────────────────────────────────────────────────

  /** 45번 항목: 가중치 전환(임계값 있음 / 없음)과 값 전환(P1). type 값과 키 구성으로 종류를 구분한다. */
  @JsonDeserialize(using = ChangeConditionItemDeserializer.class)
  public sealed interface ChangeConditionItem
      permits WeightChangeCondition, WeightNoThresholdCondition, ValueChangeCondition {}

  @JsonDeserialize(using = JsonDeserializer.None.class) // 인터페이스의 종류 선택기를 되풀이하지 않는다
  public record WeightChangeCondition(
      String type,
      String criterionId,
      double currentWeight,
      double threshold,
      String direction,
      String newTop,
      String text)
      implements ChangeConditionItem {
    public WeightChangeCondition {
      require("weight".equals(type), "type은 weight입니다");
      require(
          "increase".equals(direction) || "decrease".equals(direction),
          "direction은 increase|decrease입니다");
    }
  }

  /** 어떻게 바뀌어도 1위가 유지되는 기준: threshold가 null이고 다른 키는 없다. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @JsonDeserialize(using = JsonDeserializer.None.class) // 인터페이스의 종류 선택기를 되풀이하지 않는다
  public record WeightNoThresholdCondition(
      String type, String criterionId, Object threshold, String text)
      implements ChangeConditionItem {
    public WeightNoThresholdCondition {
      require("weight".equals(type), "type은 weight입니다");
      require(threshold == null, "threshold는 null이어야 합니다");
    }
  }

  /** type=value (P1). */
  @JsonDeserialize(using = JsonDeserializer.None.class) // 인터페이스의 종류 선택기를 되풀이하지 않는다
  public record ValueChangeCondition(
      String type,
      String optionId,
      String criterionId,
      double currentValue,
      double threshold,
      String text)
      implements ChangeConditionItem {
    public ValueChangeCondition {
      require("value".equals(type), "type은 value입니다");
    }
  }

  /** type과 키 구성으로 45번 항목 종류를 고른다. */
  static final class ChangeConditionItemDeserializer extends JsonDeserializer<ChangeConditionItem> {
    @Override
    public ChangeConditionItem deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException {
      JsonNode n = p.readValueAsTree();
      Class<? extends ChangeConditionItem> target;
      if ("value".equals(n.path("type").asText())) {
        target = ValueChangeCondition.class;
      } else if (n.hasNonNull("threshold")) {
        target = WeightChangeCondition.class;
      } else {
        target = WeightNoThresholdCondition.class;
      }
      return p.getCodec().treeToValue(n, target);
    }
  }

  /** 45번 GET /change-conditions. */
  public record ChangeConditionsResponse(String currentTop, List<ChangeConditionItem> items) {}

  // ── 39 ────────────────────────────────────────────────────────

  public record Recommendation(String optionId, String name, double topProbability) {}

  public record HiddenPreference(
      String criterionA,
      String criterionB,
      String stated,
      String revealed,
      double strength,
      String text) {
    public HiddenPreference {
      Set<String> allowed = Set.of("A_MORE_IMPORTANT", "B_MORE_IMPORTANT");
      require(
          allowed.contains(stated) && allowed.contains(revealed), "stated·revealed 값이 올바르지 않습니다");
    }
  }

  public record ExcludedOption(String optionId, List<String> reasons) {}

  public record OptionSummary(String optionId, List<String> pros, List<String> cons) {}

  public record Explanation(
      String summary, List<String> reasons, List<String> caveats, String source) {
    public Explanation {
      require("ai".equals(source) || "template".equals(source), "source는 ai|template입니다");
    }
  }

  /** 39번 GET /analysis (종합). QUESTIONING 중에는 provisional=true. */
  public record AnalysisResponse(
      boolean provisional,
      Recommendation recommendation,
      List<RankingItem> rankings,
      List<WeightItem> weights,
      StabilityResponse stability,
      List<ChangeConditionItem> changeConditions,
      List<HiddenPreference> hiddenPreferences,
      List<ExcludedOption> excludedOptions,
      List<OptionSummary> optionSummaries,
      Explanation explanation,
      boolean lowConfidence,
      Instant computedAt) {}

  // ── 46 ────────────────────────────────────────────────────────

  public record SimulationWeight(String criterionId, double weight) {}

  /** 46번 요청. 합이 1이 아니어도 서버가 정규화한다. */
  public record SimulationRequest(List<SimulationWeight> weights) {}

  public record SimulationRankingItem(int rank, String optionId, double score) {}

  /** 46번 200 — 저장하지 않는 순수 계산. */
  public record SimulationResponse(
      List<SimulationWeight> normalizedWeights,
      List<SimulationRankingItem> rankings,
      boolean changedFromCurrent) {}
}
