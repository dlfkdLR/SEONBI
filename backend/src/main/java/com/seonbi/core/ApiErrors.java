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
    jakarta.validation.ConstraintViolationException.class
  })
  public ResponseEntity<Envelope> malformed(Exception e) {
    return invalid(Map.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Envelope> integrity(DataIntegrityViolationException e) {
    return invalid(Map.of());
  }

  private ResponseEntity<Envelope> invalid(Map<String, Object> details) {
    return flow(
        new FlowError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력 형식이나 범위를 확인해 주세요.", details));
  }
}
