package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 기록 이동 코드(일회용). 코드 원문은 저장하지 않고 해시만 둔다. */
@Entity
@Table(
    name = "transfer_codes",
    uniqueConstraints =
        @UniqueConstraint(
            name = "transfer_codes_code_hash_key",
            columnNames = {"code_hash"}),
    indexes = @Index(name = "idx_transfer_codes_client", columnList = "client_id"))
public class TransferCode {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  /** 코드를 발급한 옛 기기의 브라우저 ID. 새 기기가 이 ID를 이어받는다. */
  @Column(name = "client_id", nullable = false)
  private UUID clientId;

  @Column(name = "code_hash", length = 64, nullable = false)
  private String codeHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  public UUID getId() {
    return id;
  }

  public UUID getClientId() {
    return clientId;
  }

  public void setClientId(UUID clientId) {
    this.clientId = clientId;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public void setCodeHash(String codeHash) {
    this.codeHash = codeHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(Instant usedAt) {
    this.usedAt = usedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
