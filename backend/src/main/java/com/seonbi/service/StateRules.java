package com.seonbi.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 결정 상태 머신 규칙 (개발기획서 4.2). 순수 함수만 두고 I/O는 하지 않는다.
 *
 * <ul>
 *   <li>{@link #check}: "API별 상태 제약" 표의 칸을 그대로 옮긴 검사. 위반이면 {@link Violation}, 허용이면 비어 있다.
 *   <li>{@code after*}: 전이 결과 상태. 허용되지 않는 전이는 {@link InvalidTransition}을 낸다(먼저 check를 했다면 일어나지
 *       않는다).
 *   <li>QUESTIONING → DRAFT 전이는 없다. 한번 질문을 시작한 결정은 DRAFT로 돌아가지 않는다.
 * </ul>
 */
public final class StateRules {

  /** 4.2 "API별 상태 제약" 표의 행 (괄호는 API 번호). */
  public enum Action {
    CONFIG_EDIT, // 12, 14~17, 19~22, 25, 27
    FREE_INPUT, // 28~30
    QUESTION_START, // 31
    QUESTION_READ, // 32·33·34
    ANSWER_SUBMIT_OR_STOP, // 37·35
    ANSWER_EDIT, // 38
    ANALYSIS_READ, // 39~45
    SIMULATE, // 46
    FINALIZE, // 47
    EXPORT, // 48
    DELETE_OR_DUPLICATE, // 13·49
    READ // 10·11·15·20·24·26·36 — 표에 없는 조회, 모든 상태에서 가능
  }

  /** 오류 코드 (7.1). HTTP 상태와 한 쌍이다. */
  public enum ErrorCode {
    DECISION_FINALIZED,
    INVALID_STATE,
    NOT_READY
  }

  /** 첫 라운드인지 추가 질문 라운드인지 (31번 응답의 round). */
  public enum RoundKind {
    INITIAL,
    EXTRA;

    public String json() {
      return name().toLowerCase();
    }
  }

  public record Violation(int httpStatus, ErrorCode code) {}

  /** 허용되지 않는 상태 전이. */
  public static class InvalidTransition extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public InvalidTransition(String message) {
      super(message);
    }
  }

  /** QUESTIONING 중 분석 조회에 필요한 답변 수 (4.2). */
  public static final int MIN_ANSWERS_FOR_ANALYSIS = 1;

  private static final Violation FINALIZED = new Violation(409, ErrorCode.DECISION_FINALIZED);
  private static final Violation INVALID_STATE = new Violation(409, ErrorCode.INVALID_STATE);
  private static final Violation NOT_READY = new Violation(422, ErrorCode.NOT_READY);

  private static final DecisionStatus DRAFT = DecisionStatus.DRAFT;
  private static final DecisionStatus QUESTIONING = DecisionStatus.QUESTIONING;
  private static final DecisionStatus ANALYZED = DecisionStatus.ANALYZED;
  private static final DecisionStatus FINAL = DecisionStatus.FINALIZED;

  // 표의 칸을 그대로 옮긴 것. 값이 없으면 가능. ANALYSIS_READ·SIMULATE의 QUESTIONING 칸은 답변 수로 따로 판단한다
  private static final Map<Action, Map<DecisionStatus, Violation>> TABLE =
      new EnumMap<>(Action.class);

  static {
    TABLE.put(Action.CONFIG_EDIT, Map.of(FINAL, FINALIZED));
    TABLE.put(Action.FREE_INPUT, Map.of(FINAL, FINALIZED));
    // DRAFT는 readiness가 필요하지만 그 검사는 B 담당이다
    TABLE.put(Action.QUESTION_START, Map.of(FINAL, FINALIZED));
    TABLE.put(Action.QUESTION_READ, Map.of(DRAFT, NOT_READY));
    TABLE.put(
        Action.ANSWER_SUBMIT_OR_STOP,
        Map.of(DRAFT, INVALID_STATE, ANALYZED, INVALID_STATE, FINAL, FINALIZED));
    TABLE.put(Action.ANSWER_EDIT, Map.of(DRAFT, INVALID_STATE, FINAL, FINALIZED));
    TABLE.put(Action.ANALYSIS_READ, Map.of(DRAFT, NOT_READY));
    TABLE.put(Action.SIMULATE, Map.of(DRAFT, NOT_READY));
    TABLE.put(
        Action.FINALIZE,
        Map.of(DRAFT, INVALID_STATE, QUESTIONING, INVALID_STATE, FINAL, FINALIZED));
    TABLE.put(Action.EXPORT, Map.of(DRAFT, NOT_READY, QUESTIONING, NOT_READY));
    TABLE.put(Action.DELETE_OR_DUPLICATE, Map.of());
    TABLE.put(Action.READ, Map.of());
  }

  private StateRules() {}

  /**
   * 작업이 이 상태에서 허용되면 비어 있고, 아니면 위반(HTTP 상태·오류 코드)이다. answered는 QUESTIONING 중 분석 조회·시뮬레이션에서만 쓴다: 0개면
   * 422 NOT_READY. 세는 기준은 호출하는 쪽이 정한다(유효 답변 수, 자유 입력의 가상 답변도 추정에 쓰이므로 포함).
   */
  public static Optional<Violation> check(Action action, DecisionStatus status, int answered) {
    boolean needsAnswers = action == Action.ANALYSIS_READ || action == Action.SIMULATE;
    if (needsAnswers && status == QUESTIONING && answered < MIN_ANSWERS_FOR_ANALYSIS) {
      return Optional.of(NOT_READY);
    }
    return Optional.ofNullable(TABLE.get(action).get(status));
  }

  public static Optional<Violation> check(Action action, DecisionStatus status) {
    return check(action, status, 0);
  }

  /** 분석 응답의 provisional. QUESTIONING에서 답변이 1개 이상일 때만 잠정 결과다. */
  public static boolean isProvisional(DecisionStatus status, int answered) {
    return status == QUESTIONING && answered >= MIN_ANSWERS_FOR_ANALYSIS;
  }

  /** 31번. DRAFT → QUESTIONING, QUESTIONING 유지(멱등), ANALYZED → QUESTIONING(추가 질문 라운드). */
  public static DecisionStatus afterQuestionStart(DecisionStatus status) {
    if (status == FINAL) {
      throw new InvalidTransition("FINALIZED에서는 질문을 시작할 수 없다");
    }
    return QUESTIONING;
  }

  /** 37·32·35번의 종료. QUESTIONING → ANALYZED. 이미 ANALYZED면 그대로(32번이 done=true만 돌려주는 경우). */
  public static DecisionStatus afterStopCondition(DecisionStatus status) {
    if (status == QUESTIONING || status == ANALYZED) {
      return ANALYZED;
    }
    throw new InvalidTransition(status + "에서는 질문을 끝낼 수 없다");
  }

  /** 19·29번의 기준 추가. ANALYZED → QUESTIONING(추가 라운드), DRAFT·QUESTIONING은 그대로. */
  public static DecisionStatus afterCriterionAdded(DecisionStatus status) {
    if (status == FINAL) {
      throw new InvalidTransition("FINALIZED에서는 기준을 추가할 수 없다");
    }
    return status == ANALYZED ? QUESTIONING : status;
  }

  /** 47번. ANALYZED → FINALIZED만 된다. */
  public static DecisionStatus afterFinalize(DecisionStatus status) {
    if (status == ANALYZED) {
      return FINAL;
    }
    throw new InvalidTransition(status + "에서는 확정할 수 없다");
  }

  /**
   * 31번 응답의 round. ANALYZED에서 호출하면 추가 라운드(EXTRA), DRAFT에서는 첫 라운드(INITIAL). QUESTIONING에서 31번을 다시
   * 부르면(멱등) 상태만으로는 첫 라운드인지 추가 라운드인지 알 수 없다. 명세에 이를 가르는 필드가 없어 호출하는 쪽이 extraRoundActive로 알려 준다.
   */
  public static RoundKind roundKind(DecisionStatus status, boolean extraRoundActive) {
    if (status == FINAL) {
      throw new InvalidTransition("FINALIZED에서는 질문 라운드가 없다");
    }
    if (status == ANALYZED || (status == QUESTIONING && extraRoundActive)) {
      return RoundKind.EXTRA;
    }
    return RoundKind.INITIAL;
  }
}
