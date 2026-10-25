package com.seonbi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 질문 API 응답·요청 (개발기획서 7.3 "질문·답변" 31~36번, 4.2). JSON 키는 camelCase(7.1)이고 필드 이름은 7.3 예시와 같다.
 *
 * <p>가정(명세에 없거나 모호한 부분):
 *
 * <ul>
 *   <li>payload는 type에 맞는 구조여야 한다. numeric/free_text의 payload 형식은 명세에 없어 자유 객체로 둔다.
 *   <li>종료 이유 코드는 5.10의 4종(CONFIDENT, LOW_INFO_GAIN, MAX_QUESTIONS, USER_STOPPED)으로 제한했다.
 *   <li>33번 metrics의 값은 예시에 null이 없어 모두 필수로 읽었다.
 * </ul>
 */
public final class QuestionDtos {

  private QuestionDtos() {}

  public static final Set<String> QUESTION_TYPES =
      Set.of("slider_pair", "ab_criteria", "ab_scenario", "numeric", "free_text");
  public static final Set<String> STOP_REASONS =
      Set.of("CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS", "USER_STOPPED");

  static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  // 명세에 없는 필드를 거부하는 매퍼 (payload 검증용)
  private static final ObjectMapper STRICT =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

  // ── payload (7.3 질문 객체) ────────────────────────────────────

  public record SliderSide(String criterionId, String label, String range) {}

  /** slider_pair: s &gt; 0이면 왼쪽이 더 중요 (5.3). */
  public record SliderPairPayload(SliderSide left, SliderSide right, int min, int max) {}

  public record CriteriaSide(String criterionId, String label) {}

  public record AbCriteriaPayload(CriteriaSide optionA, CriteriaSide optionB) {}

  public record ScenarioDelta(String criterionId, String display) {}

  public record ScenarioOption(String label, String description, List<ScenarioDelta> deltas) {}

  public record AbScenarioPayload(ScenarioOption optionA, ScenarioOption optionB) {}

  /** 질문 객체 (7.3 공통). payload는 type에 맞는 구조인지 생성할 때 검사한다. */
  public record QuestionObject(
      String id,
      int seq,
      String type,
      List<String> criteria,
      String title,
      Map<String, Object> payload,
      boolean allowFreeText,
      boolean allowSkip) {
    public QuestionObject {
      require(QUESTION_TYPES.contains(type), "알 수 없는 질문 유형입니다: " + type);
      require(payload != null, "payload는 객체여야 합니다");
      Class<?> shape =
          switch (type) {
            case "slider_pair" -> SliderPairPayload.class;
            case "ab_criteria" -> AbCriteriaPayload.class;
            case "ab_scenario" -> AbScenarioPayload.class;
            default -> null;
          };
      if (shape != null) {
        try {
          STRICT.convertValue(payload, shape);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(type + " 질문의 payload 형식이 맞지 않습니다", e);
        }
      }
    }
  }

  /** 36번 items[].question — 질문 객체 + origin (engine | free_text). */
  public record AnswerQuestionObject(
      String id,
      int seq,
      String type,
      List<String> criteria,
      String title,
      Map<String, Object> payload,
      boolean allowFreeText,
      boolean allowSkip,
      String origin) {
    public AnswerQuestionObject {
      require(
          "engine".equals(origin) || "free_text".equals(origin), "origin은 engine 또는 free_text입니다");
      new QuestionObject(
          id, seq, type, criteria, title, payload, allowFreeText, allowSkip); // 같은 검증
    }
  }

  // ── 답변 값 (36·37·38번 response) ──────────────────────────────

  /** {choice} | {value} | {skip} 중 정확히 하나. 질문 유형과의 일치 여부는 서비스가 검사한다(400). 값이 있는 키 하나만 낸다. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AnswerValue(String choice, Integer value, Boolean skip) {
    public AnswerValue {
      int given = (choice != null ? 1 : 0) + (value != null ? 1 : 0) + (skip != null ? 1 : 0);
      require(given == 1, "choice, value, skip 중 정확히 하나만 보내야 합니다");
      require(choice == null || choice.equals("A") || choice.equals("B"), "choice는 A 또는 B입니다");
      require(value == null || (value >= -5 && value <= 5), "value는 -5~5 정수입니다");
      require(skip == null || skip, "skip은 true만 허용됩니다");
    }

    public static AnswerValue ofChoice(String choice) {
      return new AnswerValue(choice, null, null);
    }

    public static AnswerValue ofValue(int value) {
      return new AnswerValue(null, value, null);
    }

    public static AnswerValue ofSkip() {
      return new AnswerValue(null, null, true);
    }
  }

  // ── 31 ────────────────────────────────────────────────────────

  public record StartProgress(int answered, List<Integer> expectedRange) {
    public StartProgress {
      require(expectedRange != null && expectedRange.size() == 2, "expectedRange는 [최소, 최대]입니다");
    }
  }

  /** 31번 POST /questions/start 200. round는 initial | extra. */
  public record StartQuestionsResponse(
      String status, String round, QuestionObject question, StartProgress progress, String reason) {
    public StartQuestionsResponse {
      // 비교할 기준 쌍이 없으면 질문 없이 바로 ANALYZED(question=null, reason=NO_QUESTION_CANDIDATES)
      require(
          "QUESTIONING".equals(status) || "ANALYZED".equals(status),
          "status는 QUESTIONING 또는 ANALYZED입니다");
      require("ANALYZED".equals(status) || question != null, "QUESTIONING에는 question이 필요합니다");
      require("initial".equals(round) || "extra".equals(round), "round는 initial 또는 extra입니다");
    }
  }

  // ── 32 ────────────────────────────────────────────────────────

  /** 32번 GET /questions/next: 다음 질문이 있으면 Continue, 종료면 Done. */
  public sealed interface NextQuestionResponse permits NextQuestionContinue, NextQuestionDone {
    @JsonProperty("done")
    boolean done();
  }

  public record NextQuestionContinue(QuestionObject question) implements NextQuestionResponse {
    @Override
    public boolean done() {
      return false;
    }
  }

  public record NextQuestionDone(String reason) implements NextQuestionResponse {
    public NextQuestionDone {
      require(STOP_REASONS.contains(reason), "알 수 없는 종료 이유입니다: " + reason);
    }

    @Override
    public boolean done() {
      return true;
    }
  }

  // ── 33 ────────────────────────────────────────────────────────

  public record StatusMetrics(
      int answered,
      String topOption,
      double topProbability,
      double decisionEntropy,
      double bestNextInfoGain) {}

  public record RemainingUncertainty(String criterionId, List<Double> interval) {
    public RemainingUncertainty {
      require(interval != null && interval.size() == 2, "interval은 [하한, 상한]입니다");
    }
  }

  /** 33번 GET /questions/status. */
  public record StatusResponse(
      boolean shouldStop,
      String reason,
      StatusMetrics metrics,
      List<RemainingUncertainty> remainingUncertainty) {
    public StatusResponse {
      require(reason == null || STOP_REASONS.contains(reason), "알 수 없는 종료 이유입니다: " + reason);
    }
  }

  // ── 34 ────────────────────────────────────────────────────────

  /** 34번 GET /questions/progress (P2). */
  public record ProgressResponse(int answered, List<Integer> expectedRange, double confidence) {
    public ProgressResponse {
      require(expectedRange != null && expectedRange.size() == 2, "expectedRange는 [최소, 최대]입니다");
    }
  }

  // ── 35 ────────────────────────────────────────────────────────

  /** 35번 POST /questions/stop. lowConfidence는 39번과 같은 값(P_best &lt; 0.55). */
  public record StopResponse(String reason, boolean lowConfidence) {
    public StopResponse {
      require(STOP_REASONS.contains(reason), "알 수 없는 종료 이유입니다: " + reason);
    }
  }

  // ── 36 ────────────────────────────────────────────────────────

  public record AnswerListItem(
      String id,
      String questionId,
      AnswerQuestionObject question,
      AnswerValue response,
      boolean isValid,
      Instant createdAt) {}

  /** 36번 GET /answers (페이지네이션 없이 items만, 명세 그대로). */
  public record AnswerListResponse(List<AnswerListItem> items) {}
}
