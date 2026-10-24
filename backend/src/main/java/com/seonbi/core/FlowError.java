package com.seonbi.core;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class FlowError extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final HttpStatus status;
  private final String code;
  private final transient Map<String, Object> details;

  public FlowError(HttpStatus status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public Map<String, Object> getDetails() {
    return details;
  }
}
