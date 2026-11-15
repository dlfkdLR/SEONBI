package com.seonbi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.seonbi.core.*;
import com.seonbi.domain.Decision;
import com.seonbi.repository.DecisionRepository;
import com.seonbi.service.DecisionResolver;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FoundationTest.Probe.class)
class FoundationTest {
  // Docker가 없는 PC에서는 SEONBI_TEST_DB_URL(+_USER/_PASSWORD)로 이미 떠 있는 PostgreSQL을 대신 쓸 수 있다
  static final String EXTERNAL_DB = System.getenv("SEONBI_TEST_DB_URL");
  static final PostgreSQLContainer<?> DB =
      EXTERNAL_DB == null ? new PostgreSQLContainer<>("postgres:16.4") : null;

  static {
    if (DB != null) DB.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    if (EXTERNAL_DB != null) {
      r.add("spring.datasource.url", () -> EXTERNAL_DB);
      r.add(
          "spring.datasource.username",
          () -> System.getenv().getOrDefault("SEONBI_TEST_DB_USER", "seonbi"));
      r.add(
          "spring.datasource.password",
          () -> System.getenv().getOrDefault("SEONBI_TEST_DB_PASSWORD", ""));
      return;
    }
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired DecisionRepository decisions;
  @Autowired DecisionResolver resolver;
  @Autowired EntityManager em;

  @RestController
  @io.swagger.v3.oas.annotations.Hidden
  static class Probe {
    @GetMapping("/api/v1/test-error")
    void failure() {
      throw new IllegalStateException("private server exception");
    }

    @GetMapping("/api/v1/test-client")
    UUID client(@ClientId UUID id) {
      return id;
    }
  }

  @Test
  void healthNeedsNoClient() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.db").value("ok"));
  }

  @Test
  void clientHeaderRequiredAndCanonical() throws Exception {
    for (String header : List.of("", "bad", "1-1-1-1-1")) {
      mvc.perform(get("/api/v1/test-client").header("X-Client-Id", header))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("CLIENT_ID_REQUIRED"))
          .andExpect(jsonPath("$.error.details").isMap());
    }
    mvc.perform(get("/api/v1/test-client")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/test-client").header("X-Client-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk());
  }

  @Test
  @Transactional
  void sharedDecisionSoftDeleteAndVersion() {
    UUID client = UUID.randomUUID();
    Decision d = new Decision();
    d.setClientId(client);
    d.setTitle("노트북 선택");
    d.setCategory("purchase");
    em.persist(d);
    em.flush();
    assertThat(d.getId().version()).isEqualTo(7);
    assertThat(resolver.get(d.getId(), client).getId()).isEqualTo(d.getId());
    assertThat(resolver.get(d.getId(), UUID.randomUUID()).getId()).isEqualTo(d.getId());
    resolver.bumpVersion(d);
    assertThat(d.getVersion()).isEqualTo(2);
    d.setStatus("FINALIZED");
    assertThatThrownBy(() -> resolver.ensureNotFinalized(d)).isInstanceOf(FlowError.class);
    d.setDeletedAt(java.time.Instant.now());
    decisions.flush();
    assertThatThrownBy(() -> resolver.get(d.getId(), client)).isInstanceOf(FlowError.class);
    assertThat(decisions.findById(d.getId())).isPresent();
  }

  @Test
  void allFourteenTablesExistWithChecksAndCascade() {
    List<?> tables =
        em.createNativeQuery(
                "select table_name from information_schema.tables where table_schema='public'"
                    + " and table_name <> 'flyway_schema_history'") // Flyway 이력 테이블은 앱 테이블이 아니다
            .getResultList();
    assertThat(tables).hasSize(14); // 결정 관련 13개 + 기록 이동 코드(transfer_codes)
    Number checks =
        (Number)
            em.createNativeQuery(
                    "select count(*) from pg_constraint where contype='c' and connamespace='public'::regnamespace")
                .getSingleResult();
    assertThat(checks.intValue()).isGreaterThanOrEqualTo(6);
    Number foreignKeys =
        (Number)
            em.createNativeQuery(
                    "select count(*) from pg_constraint where contype='f' and connamespace='public'::regnamespace and confdeltype <> 'c'")
                .getSingleResult();
    assertThat(foreignKeys.intValue()).isZero();
  }

  @Test
  void requiredDecisionColumnsAreNotNullable() {
    List<?> columns =
        em.createNativeQuery(
                "select column_name,is_nullable from information_schema.columns where table_schema='public' and table_name='decisions' and column_name in ('category','status','version','rng_seed')",
                Object[].class)
            .getResultList();
    assertThat(columns).hasSize(4);
    for (Object row : columns) assertThat(((Object[]) row)[1]).isEqualTo("NO");
  }
}
