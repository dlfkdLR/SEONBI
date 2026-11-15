package com.seonbi.service;

import com.seonbi.core.FlowError;
import com.seonbi.domain.TransferCode;
import com.seonbi.dto.TransferDtos.ClaimResult;
import com.seonbi.dto.TransferDtos.IssuedCode;
import com.seonbi.repository.TransferCodeRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기기 간 기록 이동 (기획서 49-1·49-2번).
 *
 * <p>로그인이 없는 구조라서 "나"는 브라우저가 만든 X-Client-Id 하나뿐이다. 그래서 새 기기가 옛 기기의 ID를 이어받게 한다.
 *
 * <ul>
 *   <li>옛 기기: {@link #issue} — 10분짜리 일회용 코드를 받는다. 새로 받으면 이전 코드는 사라진다.
 *   <li>새 기기: {@link #claim} — 코드를 내면 옛 기기의 ID를 돌려받는다. 새 기기에 이미 있던 결정은 옛 ID로 합쳐서 잃지 않는다.
 * </ul>
 *
 * 코드는 헷갈리는 글자(0/O, 1/I/L)를 뺀 31글자로 10자리(약 2^49가지)이고, 서버에는 해시만 저장한다. 맞히기를 막으려고 틀린 입력은 기기별·서버 전체로 횟수를
 * 제한한다.
 */
@Service
public class TransferService {
  static final Duration TTL = Duration.ofMinutes(10);
  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  static final int CODE_LENGTH = 10;

  // 틀린 입력 제한: 같은 기기 5번 / 서버 전체 60번 (10분 창)
  static final int FAIL_LIMIT_PER_CLIENT = 5;
  static final int FAIL_LIMIT_TOTAL = 60;
  static final Duration FAIL_WINDOW = Duration.ofMinutes(10);

  private final TransferCodeRepository codes;
  private final EntityManager em;
  private final SecureRandom random = new SecureRandom();
  private final Map<UUID, Deque<Instant>> failuresByClient = new ConcurrentHashMap<>();
  private final Deque<Instant> failuresTotal = new ArrayDeque<>();

  public TransferService(TransferCodeRepository codes, EntityManager em) {
    this.codes = codes;
    this.em = em;
  }

  @Transactional
  public IssuedCode issue(UUID clientId) {
    Instant now = Instant.now();
    codes.deleteStale(clientId, now);
    codes.flush();
    String code = randomCode();
    TransferCode row = new TransferCode();
    row.setClientId(clientId);
    row.setCodeHash(hash(code));
    row.setExpiresAt(now.plus(TTL));
    codes.save(row);
    // 보여 줄 때만 가운데에 하이픈을 넣는다 (XXXXX-XXXXX)
    return new IssuedCode(
        code.substring(0, 5) + "-" + code.substring(5), row.getExpiresAt(), (int) TTL.toSeconds());
  }

  @Transactional
  public ClaimResult claim(UUID newClientId, String rawCode) {
    Instant now = Instant.now();
    checkNotBlocked(newClientId, now);
    String code = normalize(rawCode);
    TransferCode row =
        code.length() == CODE_LENGTH
            ? codes.findByCodeHashForUpdate(hash(code)).orElse(null)
            : null;
    if (row == null || row.getUsedAt() != null || !row.getExpiresAt().isAfter(now)) {
      recordFailure(newClientId, now);
      // 없는 코드·만료·이미 사용을 구분해서 알려 주면 추측에 쓰이므로 같은 응답으로 돌려준다
      throw new FlowError(
          HttpStatus.NOT_FOUND,
          "TRANSFER_CODE_INVALID",
          "코드가 올바르지 않거나 만료됐어요. 옛 기기에서 새 코드를 받아주세요.",
          Map.of());
    }
    UUID source = row.getClientId();
    row.setUsedAt(now);

    int moved = 0;
    if (!source.equals(newClientId)) {
      // 새 기기가 지금까지 만든 결정을 옛 ID 쪽으로 합친다. 같은 setup_key가 양쪽에 있으면(드문 경우) 멱등 키만 비운다
      moved =
          em.createNativeQuery(
                  """
                  UPDATE decisions d SET client_id = :source,
                    setup_key = CASE WHEN d.setup_key IS NOT NULL AND EXISTS (
                        SELECT 1 FROM decisions o WHERE o.client_id = :source AND o.setup_key = d.setup_key
                      ) THEN NULL ELSE d.setup_key END
                  WHERE d.client_id = :mine
                  """)
              .setParameter("source", source)
              .setParameter("mine", newClientId)
              .executeUpdate();
      // AI 호출 상한 집계도 이어받은 ID로 합쳐서, 옮겼다고 하루 상한을 다시 쓰지 못하게 한다
      em.createNativeQuery("UPDATE ai_usage_logs SET client_id = :source WHERE client_id = :mine")
          .setParameter("source", source)
          .setParameter("mine", newClientId)
          .executeUpdate();
    }
    Number total =
        (Number)
            em.createNativeQuery(
                    "SELECT count(*) FROM decisions WHERE client_id = :source AND deleted_at IS NULL")
                .setParameter("source", source)
                .getSingleResult();
    return new ClaimResult(source, moved, total.intValue());
  }

  private String randomCode() {
    StringBuilder sb = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++)
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    return sb.toString();
  }

  /** 하이픈·공백을 없애고 대문자로 맞춘다. 사용자가 소문자나 띄어쓰기로 적어도 된다. */
  static String normalize(String raw) {
    return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  static String hash(String code) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private synchronized void checkNotBlocked(UUID clientId, Instant now) {
    Instant from = now.minus(FAIL_WINDOW);
    while (!failuresTotal.isEmpty() && failuresTotal.peekFirst().isBefore(from))
      failuresTotal.pollFirst();
    Deque<Instant> mine = failuresByClient.get(clientId);
    if (mine != null) {
      while (!mine.isEmpty() && mine.peekFirst().isBefore(from)) mine.pollFirst();
      if (mine.isEmpty()) failuresByClient.remove(clientId);
    }
    if (failuresTotal.size() >= FAIL_LIMIT_TOTAL
        || (mine != null && mine.size() >= FAIL_LIMIT_PER_CLIENT)) {
      throw new FlowError(
          HttpStatus.TOO_MANY_REQUESTS,
          "TRANSFER_RATE_LIMITED",
          "코드를 너무 여러 번 틀렸어요. 잠시 뒤에 다시 시도해주세요.",
          Map.of("retryAfterSeconds", (int) FAIL_WINDOW.toSeconds()));
    }
  }

  private synchronized void recordFailure(UUID clientId, Instant now) {
    failuresTotal.addLast(now);
    failuresByClient.computeIfAbsent(clientId, k -> new ArrayDeque<>()).addLast(now);
  }
}
