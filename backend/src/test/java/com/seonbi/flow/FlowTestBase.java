package com.seonbi.flow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

/** A의 질문·분석 흐름 통합 테스트 공통 도구: 실제 PostgreSQL 위에서 B의 구성 API로 결정을 만들고 A의 API를 호출한다. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class FlowTestBase {

  // 테스트 클래스 여러 개가 한 DB 컨테이너를 같이 쓴다 (JVM이 끝나면 정리된다)
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4");

  static {
    DB.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper mapper;

  protected String cid;

  @BeforeEach
  void newClient() {
    cid = UUID.randomUUID().toString(); // 테스트마다 다른 클라이언트라 서로 영향을 주지 않는다
  }

  protected JsonNode call(MockHttpServletRequestBuilder b, Object body, int expected)
      throws Exception {
    b.header("X-Client-Id", cid).accept(MediaType.APPLICATION_JSON);
    if (body != null) {
      b.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }
    String text =
        mvc.perform(b)
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return text.isBlank() ? mapper.nullNode() : mapper.readTree(text);
  }

  protected JsonNode getJ(String path, int expected) throws Exception {
    return call(get(path), null, expected);
  }

  protected JsonNode postJ(String path, Object body, int expected) throws Exception {
    return call(post(path), body, expected);
  }

  /** 노트북 비교 예시: 선택지 3개, 기준 3개(가격·무게 숫자형, 성능 단계형), 값 모두 입력. DRAFT 상태로 돌려준다. */
  protected Setup laptops() throws Exception {
    String id =
        postJ(
                "/api/v1/decisions",
                Map.of(
                    "title",
                    "새 노트북 고르기",
                    "category",
                    "purchase",
                    "options",
                    List.of(Map.of("name", "갤럭시북"), Map.of("name", "그램"), Map.of("name", "요가"))),
                201)
            .path("id")
            .asText();
    String base = "/api/v1/decisions/" + id;
    List<String> options = new ArrayList<>();
    getJ(base + "/options", 200).path("items").forEach(o -> options.add(o.path("id").asText()));
    String price =
        postJ(
                base + "/criteria",
                Map.of("name", "가격", "type", "numeric", "direction", "lower_better", "unit", "만 원"),
                201)
            .path("id")
            .asText();
    String weight =
        postJ(
                base + "/criteria",
                Map.of("name", "무게", "type", "numeric", "direction", "lower_better", "unit", "kg"),
                201)
            .path("id")
            .asText();
    String perf =
        postJ(base + "/criteria", Map.of("name", "성능", "type", "rating"), 201).path("id").asText();
    double[][] vals = {{189, 1.23, 4}, {165, 1.19, 3}, {150, 1.45, 4}};
    List<Object> items = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      items.add(Map.of("optionId", options.get(i), "criterionId", price, "value", vals[i][0]));
      items.add(Map.of("optionId", options.get(i), "criterionId", weight, "value", vals[i][1]));
      items.add(Map.of("optionId", options.get(i), "criterionId", perf, "value", (int) vals[i][2]));
    }
    call(put(base + "/values"), Map.of("items", items), 200);
    return new Setup(id, base, options, List.of(price, weight, perf));
  }

  protected record Setup(String id, String base, List<String> options, List<String> criteria) {}
}
