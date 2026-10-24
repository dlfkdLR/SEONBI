package com.seonbi.core;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiErrors.class);

  public record ErrorBody(String code, String message, Map<String, Object> details) {}

  public record Envelope(ErrorBody error) {}

  @ExceptionHandler(FlowError.class)
  public ResponseEntity<Envelope> flow(FlowError e) {
    return ResponseEntity.status(e.getStatus())
        .body(new Envelope(new ErrorBody(e.getCode(), e.getMessage(), e.getDetails())));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Envelope> validation(MethodArgumentNotValidException e) {
    Map<String, Object> fields = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
    return invalid(fields);
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    jakarta.validation.ConstraintViolationException.class,
    org.springframework.web.bind.ServletRequestBindingException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class
  })
  public ResponseEntity<Envelope> malformed(Exception e) {
    return invalid(Map.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Envelope> integrity(DataIntegrityViolationException e) {
    return invalid(Map.of());
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<Envelope> notFound(Exception e) {
    return flow(new FlowError(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", Map.of()));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Envelope> methodNotAllowed(
      org.springframework.web.HttpRequestMethodNotSupportedException e) {
    var supported = e.getSupportedHttpMethods();
    var response =
        flow(
            new FlowError(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "지원하지 않는 HTTP 메서드입니다.",
                Map.of()));
    return ResponseEntity.status(response.getStatusCode())
        .allow(supported == null ? new HttpMethod[0] : supported.toArray(HttpMethod[]::new))
        .body(response.getBody());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Envelope> unexpected(Exception e) {
    LOG.error("Unhandled request exception", e);
    return flow(
        new FlowError(
            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버에서 오류가 발생했습니다.", Map.of()));
  }

  private ResponseEntity<Envelope> invalid(Map<String, Object> details) {
    return flow(
        new FlowError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력 형식이나 범위를 확인해 주세요.", details));
  }
}
