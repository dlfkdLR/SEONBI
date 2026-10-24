package com.seonbi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "criteria",
    uniqueConstraints = @UniqueConstraint(columnNames = {"decision_id", "name"}))
@Check(
    constraints =
        "type in ('numeric','rating','boolean') and direction in ('higher_better','lower_better','true_better') and input_mode in ('score','rank') and source in ('user','ai_recommended','free_text')")
public class Criterion {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "decision_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @Column(name = "name", length = 40)
  private String name;

  @Column(name = "type", length = 20)
  private String type;

  @Column(name = "direction", length = 20)
  private String direction;

  @Column(name = "unit", length = 20)
  private String unit;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "levels", columnDefinition = "jsonb")
  private List<String> levels;

  @Column(name = "input_mode", length = 10)
  @org.hibernate.annotations.ColumnDefault("'score'")
  private String inputMode = "score";

  @Column(name = "scale_min", columnDefinition = "numeric")
  private BigDecimal scaleMin;

  @Column(name = "scale_max", columnDefinition = "numeric")
  private BigDecimal scaleMax;

  @Column(name = "scale_frozen")
  private Boolean scaleFrozen = false;

  @Column(name = "position")
  private Integer position;

  @Column(name = "source", length = 20)
  private String source = "user";

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

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public String getType() {
    return type;
  }

  public void setType(String value) {
    this.type = value;
  }

  public String getDirection() {
    return direction;
  }

  public void setDirection(String value) {
    this.direction = value;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String value) {
    this.unit = value;
  }

  public List<String> getLevels() {
    return levels;
  }

  public void setLevels(List<String> value) {
    this.levels = value;
  }

  public String getInputMode() {
    return inputMode;
  }

  public void setInputMode(String value) {
    this.inputMode = value;
  }

  public BigDecimal getScaleMin() {
    return scaleMin;
  }

  public void setScaleMin(BigDecimal value) {
    this.scaleMin = value;
  }

  public BigDecimal getScaleMax() {
    return scaleMax;
  }

  public void setScaleMax(BigDecimal value) {
    this.scaleMax = value;
  }

  public Boolean getScaleFrozen() {
    return scaleFrozen;
  }

  public void setScaleFrozen(Boolean value) {
    this.scaleFrozen = value;
  }

  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer value) {
    this.position = value;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String value) {
    this.source = value;
  }
}
