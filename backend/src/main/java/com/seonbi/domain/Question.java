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
    name = "questions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"decision_id", "seq"}),
    indexes = @Index(columnList = "decision_id, seq"))
@Check(
    constraints =
        "type in ('slider_pair','ab_criteria','ab_scenario','numeric','free_text') and phrasing_source in ('ai','template') and origin in ('engine','free_text')")
public class Question {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @Column(name = "seq")
  private Integer seq;

  @Column(name = "type", length = 20)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "spec", columnDefinition = "jsonb")
  private Map<String, Object> spec;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "display", columnDefinition = "jsonb")
  private Map<String, Object> display;

  @Column(name = "phrasing_source", length = 10)
  private String phrasingSource;

  @Column(name = "origin", length = 10)
  private String origin;

  @Column(name = "info_gain")
  private Float infoGain;

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

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer value) {
    this.seq = value;
  }

  public String getType() {
    return type;
  }

  public void setType(String value) {
    this.type = value;
  }

  public Map<String, Object> getSpec() {
    return spec;
  }

  public void setSpec(Map<String, Object> value) {
    this.spec = value;
  }

  public Map<String, Object> getDisplay() {
    return display;
  }

  public void setDisplay(Map<String, Object> value) {
    this.display = value;
  }

  public String getPhrasingSource() {
    return phrasingSource;
  }

  public void setPhrasingSource(String value) {
    this.phrasingSource = value;
  }

  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String value) {
    this.origin = value;
  }

  public Float getInfoGain() {
    return infoGain;
  }

  public void setInfoGain(Float value) {
    this.infoGain = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }
}
