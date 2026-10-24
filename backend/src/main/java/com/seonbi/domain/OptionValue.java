package com.seonbi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "option_values")
@IdClass(OptionValue.Key.class)
public class OptionValue {
  @Id
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "option_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Option option;

  @Id
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "criterion_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Criterion criterion;

  @Column(name = "value_num", columnDefinition = "numeric")
  private BigDecimal valueNum;

  @Column(name = "value_bool")
  private Boolean valueBool;

  @Column(name = "value_rank")
  private Short valueRank;

  @Column(name = "updated_at")
  private Instant updatedAt = Instant.now();

  public Option getOption() {
    return option;
  }

  public void setOption(Option value) {
    this.option = value;
  }

  public Criterion getCriterion() {
    return criterion;
  }

  public void setCriterion(Criterion value) {
    this.criterion = value;
  }

  public BigDecimal getValueNum() {
    return valueNum;
  }

  public void setValueNum(BigDecimal value) {
    this.valueNum = value;
  }

  public Boolean getValueBool() {
    return valueBool;
  }

  public void setValueBool(Boolean value) {
    this.valueBool = value;
  }

  public Short getValueRank() {
    return valueRank;
  }

  public void setValueRank(Short value) {
    this.valueRank = value;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant value) {
    this.updatedAt = value;
  }

  public static class Key implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public UUID option;
    public UUID criterion;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key k
          && Objects.equals(option, k.option)
          && Objects.equals(criterion, k.criterion);
    }

    @Override
    public int hashCode() {
      return Objects.hash(option, criterion);
    }
  }
}
