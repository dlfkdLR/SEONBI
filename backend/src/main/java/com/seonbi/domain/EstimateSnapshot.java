package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "estimate_snapshots")
public class EstimateSnapshot {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @Column(name = "after_answer_count")
  private Integer afterAnswerCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "weights", columnDefinition = "jsonb")
  private List<Map<String, Object>> weights;

  @Column(name = "top_probability")
  private Float topProbability;

  @Column(name = "entropy")
  private Float entropy;

  @Column(name = "created_at")
  private Instant createdAt = Instant.now();

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
  }

  public Decision getDecision() {
    return decision;
  }

  public void setDecision(Decision value) {
    this.decision = value;
  }

  public Integer getAfterAnswerCount() {
    return afterAnswerCount;
  }

  public void setAfterAnswerCount(Integer value) {
    this.afterAnswerCount = value;
  }

  public List<Map<String, Object>> getWeights() {
    return weights;
  }

  public void setWeights(List<Map<String, Object>> value) {
    this.weights = value;
  }

  public Float getTopProbability() {
    return topProbability;
  }

  public void setTopProbability(Float value) {
    this.topProbability = value;
  }

  public Float getEntropy() {
    return entropy;
  }

  public void setEntropy(Float value) {
    this.entropy = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }
}
