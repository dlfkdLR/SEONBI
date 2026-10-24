package com.seonbi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "conditions")
@Check(
    constraints =
        "kind in ('threshold','required_tag') and source in ('user','ai_recommended','free_text')")
public class Condition {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @Column(name = "kind", length = 20)
  private String kind;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "criterion_id", nullable = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Criterion criterion;

  @Column(name = "operator", length = 2)
  private String operator;

  @Column(name = "value", columnDefinition = "numeric")
  private BigDecimal value;

  @Column(name = "label", length = 60)
  private String label;

  @Column(name = "source", length = 20)
  private String source;

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

  public String getKind() {
    return kind;
  }

  public void setKind(String value) {
    this.kind = value;
  }

  public Criterion getCriterion() {
    return criterion;
  }

  public void setCriterion(Criterion value) {
    this.criterion = value;
  }

  public String getOperator() {
    return operator;
  }

  public void setOperator(String value) {
    this.operator = value;
  }

  public BigDecimal getValue() {
    return value;
  }

  public void setValue(BigDecimal value) {
    this.value = value;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String value) {
    this.label = value;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String value) {
    this.source = value;
  }
}
