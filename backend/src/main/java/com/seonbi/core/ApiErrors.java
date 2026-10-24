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
        .contentType(MediaType.APPLICATION_JSON)
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
    Map<String, Object> fields = new LinkedHashMap<>();
    if (e instanceof MethodArgumentTypeMismatchException mismatch)
      fields.put(mismatch.getName(), "자료형이 올바르지 않습니다.");
    else if (e
        instanceof org.springframework.web.bind.MissingServletRequestParameterException missing)
      fields.put(missing.getParameterName(), "필수 항목입니다.");
    else if (e instanceof jakarta.validation.ConstraintViolationException violations)
      violations
          .getConstraintViolations()
          .forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
    else if (e instanceof HttpMessageNotReadableException unreadable) {
      String field = "body";
      Throwable cause = unreadable.getCause();
      if (cause instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
        String path =
            mapping.getPath().stream()
                .map(
                    ref ->
                        ref.getFieldName() != null
                            ? ref.getFieldName()
                            : "[" + ref.getIndex() + "]")
                .collect(java.util.stream.Collectors.joining("."));
        if (!path.isBlank()) field = path;
      }
      fields.put(field, "올바른 JSON 형식과 자료형으로 입력해 주세요.");
    }
    if (fields.isEmpty()) fields.put("request", "요청 항목을 확인해 주세요.");
    return invalid(fields);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Envelope> integrity(DataIntegrityViolationException e) {
    return invalid(Map.of("request", "데이터 제약조건을 확인해 주세요."));
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
        .headers(response.getHeaders())
        .allow(supported == null ? new HttpMethod[0] : supported.toArray(HttpMethod[]::new))
        .body(response.getBody());
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Envelope> unsupportedMedia(
      org.springframework.web.HttpMediaTypeNotSupportedException e) {
    return flow(
        new FlowError(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "UNSUPPORTED_MEDIA_TYPE",
            "지원하지 않는 요청 데이터 형식입니다.",
            Map.of("Content-Type", "application/json으로 요청해 주세요.")));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<Envelope> unacceptableMedia(
      org.springframework.web.HttpMediaTypeNotAcceptableException e) {
    return flow(
        new FlowError(
            HttpStatus.NOT_ACCEPTABLE,
            "NOT_ACCEPTABLE",
            "지원하지 않는 응답 데이터 형식입니다.",
            Map.of("Accept", "application/json 응답을 허용해 주세요.")));
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
