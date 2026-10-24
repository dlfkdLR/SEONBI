package com.seonbi.web;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final DataSource dataSource;

  public HealthController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public record Health(String status, String db) {}

  @GetMapping(value = "/api/health", produces = "application/json")
  public ResponseEntity<Health> health() {
    try (Connection connection = dataSource.getConnection()) {
      if (connection.isValid(2)) return ResponseEntity.ok(new Health("ok", "ok"));
    } catch (SQLException ignored) {
      /* Availability is expressed by the 503 response. */
    }
    return ResponseEntity.status(503).body(new Health("error", "error"));
  }
}
