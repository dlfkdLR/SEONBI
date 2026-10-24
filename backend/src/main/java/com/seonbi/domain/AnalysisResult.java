package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_results")
public class AnalysisResult {
  @Id
  @Column(name = "decision_id")
  private UUID decisionId;

  @MapsId
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @Column(name = "decision_version")
  private Integer decisionVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "explanation", columnDefinition = "jsonb")
  private Map<String, Object> explanation;

  @Column(name = "computed_at")
  private Instant computedAt;

  public Decision getDecision() {
    return decision;
  }

  public void setDecision(Decision value) {
    this.decision = value;
    this.decisionId = value.getId();
  }

  public Integer getDecisionVersion() {
    return decisionVersion;
  }

  public void setDecisionVersion(Integer value) {
    this.decisionVersion = value;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public void setPayload(Map<String, Object> value) {
    this.payload = value;
  }

  public Map<String, Object> getExplanation() {
    return explanation;
  }

  public void setExplanation(Map<String, Object> value) {
    this.explanation = value;
  }

  public Instant getComputedAt() {
    return computedAt;
  }

  public void setComputedAt(Instant value) {
    this.computedAt = value;
  }
}
