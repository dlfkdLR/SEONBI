package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 기기 간 기록 이동 49-1·49-2번 (실제 DB). */
class TransferApiTest extends FlowTestBase {

  @Autowired EntityManager em;
  @Autowired TransactionTemplate tx;

  /** 옛 기기가 코드를 받는다. */
  private String issue() throws Exception {
    return postJ("/api/v1/transfers", null, 201).path("code").asText();
  }

  /** 새 기기(다른 client id)로 코드를 낸다. */
  private JsonNode claimAs(String newClient, String code, int expected) throws Exception {
    String old = cid;
    cid = newClient;
    try {
      return postJ("/api/v1/transfers/claim", Map.of("code", code), expected);
    } finally {
      cid = old;
    }
  }

  private long decisionsOf(String client) {
    return ((Number)
            em.createNativeQuery("select count(*) from decisions where client_id = :c")
                .setParameter("c", UUID.fromString(client))
                .getSingleResult())
        .longValue();
  }

  @Test
  void 코드를_발급하면_10분짜리_일회용_코드가_온다() throws Exception {
    JsonNode issued = postJ("/api/v1/transfers", null, 201);
    assertThat(issued.path("code").asText()).matches("[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}");
    assertThat(issued.path("expiresInSeconds").asInt()).isEqualTo(600);
    assertThat(Instant.parse(issued.path("expiresAt").asText())).isAfter(Instant.now());
  }

  @Test
  void 새_기기가_코드를_내면_옛_기기의_ID와_기록을_이어받고_새_기기의_기록은_합쳐진다() throws Exception {
    String oldDevice = cid;
    Setup mine = laptops(); // 옛 기기에서 만든 결정
    String code = issue();

    String newDevice = UUID.randomUUID().toString();
    cid = newDevice;
    laptops(); // 새 기기에서 먼저 만들어 둔 결정
    cid = oldDevice;
    assertThat(decisionsOf(newDevice)).isEqualTo(1);

    JsonNode result = claimAs(newDevice, code, 200);
    assertThat(result.path("clientId").asText()).isEqualTo(oldDevice);
    assertThat(result.path("movedDecisions").asInt()).isEqualTo(1);
    assertThat(result.path("decisionCount").asInt()).isEqualTo(2);
    // 새 기기의 결정은 옛 ID 쪽으로 합쳐졌다
    assertThat(decisionsOf(newDevice)).isZero();
    assertThat(decisionsOf(oldDevice)).isEqualTo(2);
    // 이어받은 ID로 원래 결정을 그대로 조회한다
    assertThat(getJ(mine.base(), 200).path("id").asText()).isEqualTo(mine.id());
  }

  @Test
  void 같은_코드는_한_번만_쓸_수_있다() throws Exception {
    String code = issue();
    String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
    claimAs(a, code, 200);
    JsonNode again = claimAs(b, code, 404);
    assertThat(again.path("error").path("code").asText()).isEqualTo("TRANSFER_CODE_INVALID");
  }

  @Test
  void 새_코드를_받으면_이전_코드는_쓸_수_없다() throws Exception {
    String first = issue();
    String second = issue();
    String newDevice = UUID.randomUUID().toString();
    claimAs(newDevice, first, 404);
    claimAs(newDevice, second, 200);
  }

  @Test
  void 소문자와_공백_하이픈_없는_입력도_받는다() throws Exception {
    String code = issue(); // XXXXX-XXXXX
    String sloppy = (code.substring(0, 5) + " " + code.substring(6)).toLowerCase();
    JsonNode r = claimAs(UUID.randomUUID().toString(), sloppy, 200);
    assertThat(r.path("clientId").asText()).isEqualTo(cid);
  }

  @Test
  void 만료된_코드는_거절한다() throws Exception {
    String code = issue();
    tx.executeWithoutResult(
        s ->
            em.createNativeQuery(
                    "update transfer_codes set expires_at = now() - interval '1 minute'")
                .executeUpdate());
    JsonNode r = claimAs(UUID.randomUUID().toString(), code, 404);
    assertThat(r.path("error").path("code").asText()).isEqualTo("TRANSFER_CODE_INVALID");
  }

  @Test
  void 코드를_여러_번_틀리면_잠시_막는다() throws Exception {
    String code = issue();
    String attacker = UUID.randomUUID().toString();
    for (int i = 0; i < 5; i++) claimAs(attacker, "AAAAA-BBBBB", 404);
    // 5번 틀린 뒤에는 맞는 코드도 이 기기에서는 잠시 거절한다
    JsonNode blocked = claimAs(attacker, code, 429);
    assertThat(blocked.path("error").path("code").asText()).isEqualTo("TRANSFER_RATE_LIMITED");
    // 다른 기기는 영향이 없다
    claimAs(UUID.randomUUID().toString(), code, 200);
  }

  @Test
  void 코드_원문은_저장하지_않고_해시만_저장한다() throws Exception {
    String code = issue().replace("-", "");
    Object stored =
        em.createNativeQuery("select code_hash from transfer_codes where client_id = :c")
            .setParameter("c", UUID.fromString(cid))
            .getSingleResult();
    assertThat(stored.toString()).hasSize(64).doesNotContain(code);
  }

  @Test
  void 형식이_틀린_요청은_400이고_헤더가_없으면_거절한다() throws Exception {
    postJ("/api/v1/transfers/claim", Map.of("code", ""), 400);
    String old = cid;
    cid = "not-a-uuid";
    try {
      postJ("/api/v1/transfers", null, 400);
    } finally {
      cid = old;
    }
    mvc.perform(get("/api/v1/decisions"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }
}
