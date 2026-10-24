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
@Table(name = "free_text_inputs")
@Check(constraints = "status in ('PENDING','CONFIRMED','REJECTED','IRRELEVANT')")
public class FreeTextInput {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "question_id", nullable = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Question question;

  @Column(name = "context", length = 20)
  private String context;

  @Column(name = "text", length = 500)
  private String text;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "extracted", columnDefinition = "jsonb")
  private List<Map<String, Object>> extracted;

  @Column(name = "status", length = 20)
  private String status;

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

  public Question getQuestion() {
    return question;
  }

  public void setQuestion(Question value) {
    this.question = value;
  }

  public String getContext() {
    return context;
  }

  public void setContext(String value) {
    this.context = value;
  }

  public String getText() {
    return text;
  }

  public void setText(String value) {
    this.text = value;
  }

  public List<Map<String, Object>> getExtracted() {
    return extracted;
  }

  public void setExtracted(List<Map<String, Object>> value) {
    this.extracted = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }
}
