package com.seonbi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "ai_usage_logs", indexes = @Index(columnList = "client_id, created_at"))
public class AiUsageLog {
  @Id
  @Column(name = "id")
  private UUID id = com.seonbi.core.Ids.next();

  @Column(name = "client_id")
  private UUID clientId;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "decision_id", nullable = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Decision decision;

  @Column(name = "call_type", length = 30)
  private String callType;

  @Column(name = "provider", length = 20)
  private String provider;

  @Column(name = "model", length = 50)
  private String model;

  @Column(name = "input_tokens")
  private Integer inputTokens;

  @Column(name = "output_tokens")
  private Integer outputTokens;

  @Column(name = "latency_ms")
  private Integer latencyMs;

  @Column(name = "success")
  private Boolean success;

  @Column(name = "created_at")
  private Instant createdAt = Instant.now();

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
  }

  public UUID getClientId() {
    return clientId;
  }

  public void setClientId(UUID value) {
    this.clientId = value;
  }

  public Decision getDecision() {
    return decision;
  }

  public void setDecision(Decision value) {
    this.decision = value;
  }

  public String getCallType() {
    return callType;
  }

  public void setCallType(String value) {
    this.callType = value;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String value) {
    this.provider = value;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String value) {
    this.model = value;
  }

  public Integer getInputTokens() {
    return inputTokens;
  }

  public void setInputTokens(Integer value) {
    this.inputTokens = value;
  }

  public Integer getOutputTokens() {
    return outputTokens;
  }

  public void setOutputTokens(Integer value) {
    this.outputTokens = value;
  }

  public Integer getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(Integer value) {
    this.latencyMs = value;
  }

  public Boolean getSuccess() {
    return success;
  }

  public void setSuccess(Boolean value) {
    this.success = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }
}
