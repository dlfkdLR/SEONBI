package com.seonbi.service;

import com.seonbi.core.FlowError;
import com.seonbi.service.StateRules.Violation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** A의 서비스가 던지는 오류를 7.1 형식(FlowError)으로 만드는 모음. */
final class FlowErrors {

  private FlowErrors() {}

  private static String message(StateRules.ErrorCode code) {
    return switch (code) {
      case DECISION_FINALIZED -> "확정된 결정은 수정할 수 없습니다.";
      case INVALID_STATE -> "현재 결정 상태에서는 할 수 없는 작업입니다.";
      case NOT_READY -> "아직 이 작업을 할 준비가 되지 않았습니다.";
    };
  }

  /** 상태 규칙 위반을 오류로 올린다. 위반이 없으면 아무것도 하지 않는다. */
  static void raiseIf(Optional<Violation> violation, Map<String, Object> details) {
    violation.ifPresent(
        v -> {
          throw new FlowError(
              HttpStatus.valueOf(v.httpStatus()), v.code().name(), message(v.code()), details);
        });
  }

  static FlowError notFound(String message, Map<String, Object> details) {
    return new FlowError(HttpStatus.NOT_FOUND, "NOT_FOUND", message, details);
  }

  static FlowError validation(String message, Map<String, Object> details) {
    return new FlowError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, details);
  }

  static FlowError conflict(String code, String message, Map<String, Object> details) {
    return new FlowError(HttpStatus.CONFLICT, code, message, details);
  }

  static FlowError notReady(String message, Map<String, Object> details) {
    return new FlowError(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_READY", message, details);
  }

  static Map<String, Object> details(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
