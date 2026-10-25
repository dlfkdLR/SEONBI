package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "answers",
    uniqueConstraints = @UniqueConstraint(columnNames = "question_id"),
    indexes = @Index(columnList = "decision_id"))
@Check(constraints = "preference_kind in ('stated','revealed','none')")
public class Answer {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "question_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Question question;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response", columnDefinition = "jsonb")
  private Map<String, Object> response;

  @Column(name = "preference_kind", length = 20)
  private String preferenceKind;

  @Column(name = "is_valid")
  @org.hibernate.annotations.ColumnDefault("true")
  private Boolean isValid = true;

  @Column(name = "created_at")
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at")
  private Instant updatedAt = Instant.now();

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
  }

  public Question getQuestion() {
    return question;
  }

  public void setQuestion(Question value) {
    this.question = value;
  }

  public Decision getDecision() {
    return decision;
  }

  public void setDecision(Decision value) {
    this.decision = value;
  }

  public Map<String, Object> getResponse() {
    return response;
  }

  public void setResponse(Map<String, Object> value) {
    this.response = value;
  }

  public String getPreferenceKind() {
    return preferenceKind;
  }

  public void setPreferenceKind(String value) {
    this.preferenceKind = value;
  }

  public Boolean getIsValid() {
    return isValid;
  }

  public void setIsValid(Boolean value) {
    this.isValid = value;
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
}
