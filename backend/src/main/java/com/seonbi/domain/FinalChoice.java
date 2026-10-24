package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "final_choices")
public class FinalChoice {
  @Id
  @Column(name = "decision_id")
  private UUID decisionId;

  @MapsId
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "option_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Option option;

  @Column(name = "memo", columnDefinition = "text")
  private String memo;

  @Column(name = "matched_recommendation")
  private Boolean matchedRecommendation;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "recommendation_snapshot", columnDefinition = "jsonb")
  private Map<String, Object> recommendationSnapshot;

  @Column(name = "finalized_at")
  private Instant finalizedAt;

  public Decision getDecision() {
    return decision;
  }

  public void setDecision(Decision value) {
    this.decision = value;
    this.decisionId = value.getId();
  }

  public Option getOption() {
    return option;
  }

  public void setOption(Option value) {
    this.option = value;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String value) {
    this.memo = value;
  }

  public Boolean getMatchedRecommendation() {
    return matchedRecommendation;
  }

  public void setMatchedRecommendation(Boolean value) {
    this.matchedRecommendation = value;
  }

  public Map<String, Object> getRecommendationSnapshot() {
    return recommendationSnapshot;
  }

  public void setRecommendationSnapshot(Map<String, Object> value) {
    this.recommendationSnapshot = value;
  }

  public Instant getFinalizedAt() {
    return finalizedAt;
  }

  public void setFinalizedAt(Instant value) {
    this.finalizedAt = value;
  }
}
