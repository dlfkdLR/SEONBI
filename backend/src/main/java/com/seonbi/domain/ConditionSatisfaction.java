package com.seonbi.domain;

import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "condition_satisfactions")
@IdClass(ConditionSatisfaction.Key.class)
public class ConditionSatisfaction {
  @Id
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "condition_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Condition condition;

  @Id
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "option_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Option option;

  @Column(name = "satisfied")
  private Boolean satisfied;

  public Condition getCondition() {
    return condition;
  }

  public void setCondition(Condition value) {
    this.condition = value;
  }

  public Option getOption() {
    return option;
  }

  public void setOption(Option value) {
    this.option = value;
  }

  public Boolean getSatisfied() {
    return satisfied;
  }

  public void setSatisfied(Boolean value) {
    this.satisfied = value;
  }

  public static class Key implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public UUID condition;
    public UUID option;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key k
          && Objects.equals(condition, k.condition)
          && Objects.equals(option, k.option);
    }

    @Override
    public int hashCode() {
      return Objects.hash(condition, option);
    }
  }
}
