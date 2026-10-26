package com.seonbi.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "상태 확인", description = "서버와 DB 연결 확인 (0번).")
@RestController
public class HealthController {
  private final DataSource dataSource;

  public HealthController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public record Health(String status, String db) {}

  @GetMapping(value = "/api/health", produces = "application/json")
  @Operation(
      summary = "0. 헬스 체크",
      description = "서버가 DB에 연결할 수 있으면 200, 아니면 503. X-Client-Id 헤더가 필요 없다.")
  public ResponseEntity<Health> health() {
    try (Connection connection = dataSource.getConnection()) {
      if (connection.isValid(2)) return ResponseEntity.ok(new Health("ok", "ok"));
    } catch (SQLException ignored) {
      /* Availability is expressed by the 503 response. */
    }
    return ResponseEntity.status(503).body(new Health("error", "error"));
  }
}
