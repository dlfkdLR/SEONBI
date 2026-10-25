package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.Check;

@Entity
@Table(
    name = "decisions",
    indexes = {
      @Index(columnList = "client_id, updated_at DESC"),
      @Index(columnList = "updated_at DESC, id DESC") // 공유 목록 정렬
    })
@Check(
    constraints =
        "category in ('purchase','career','study','activity','living','etc') and status in ('DRAFT','QUESTIONING','ANALYZED','FINALIZED') and char_length(description) <= 1000")
public class Decision {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @Column(name = "client_id", nullable = false)
  private UUID clientId;

  @Column(name = "title", length = 100, nullable = false)
  private String title;

  @Column(name = "description", columnDefinition = "text")
  private String description;

  @Column(name = "category", length = 20, nullable = false)
  private String category;

  @Column(name = "status", length = 20, nullable = false)
  private String status = "DRAFT";

  @Column(name = "rng_seed", nullable = false)
  private Long rngSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();

  @Column(name = "question_limit")
  private Integer questionLimit;

  @Column(name = "version", nullable = false)
  private Integer version = 1;

  @Column(name = "created_at")
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at")
  private Instant updatedAt = Instant.now();

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
  }

  public UUID getClientId() {
    return clientId;
  }

  public void setClientId(UUID value) {
    this.clientId = value;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String value) {
    this.title = value;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String value) {
    this.description = value;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String value) {
    this.category = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public Long getRngSeed() {
    return rngSeed;
  }

  public void setRngSeed(Long value) {
    this.rngSeed = value;
  }

  public Integer getQuestionLimit() {
    return questionLimit;
  }

  public void setQuestionLimit(Integer value) {
    this.questionLimit = value;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer value) {
    this.version = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant value) {
    this.updatedAt = value;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant value) {
    this.deletedAt = value;
  }
}
