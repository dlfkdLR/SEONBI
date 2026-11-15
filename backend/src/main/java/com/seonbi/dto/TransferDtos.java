package com.seonbi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** 기록 이동(49-1·49-2번) 요청·응답. */
public final class TransferDtos {
  private TransferDtos() {}

  /** 49-1 응답. code는 이 응답에서만 볼 수 있다(서버엔 해시만 남는다). */
  public record IssuedCode(String code, Instant expiresAt, int expiresInSeconds) {}

  public record ClaimRequest(@NotBlank @Size(max = 32) String code) {}

  /** 49-2 응답. 새 기기는 clientId를 저장해 이후 모든 요청의 X-Client-Id로 쓴다. */
  public record ClaimResult(UUID clientId, int movedDecisions, int decisionCount) {}
}
