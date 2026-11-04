package com.mortar.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One audit record: who ({@code actor}), did what ({@code action}) to which entity
 * ({@code entity}, {@code entityId}) and when ({@code timestamp}, UTC).
 */
@Entity
@Table(
    name = "audit_log",
    indexes = {
      @Index(name = "idx_audit_log_entity", columnList = "entity, entity_id"),
      @Index(name = "idx_audit_log_occurred_at", columnList = "occurred_at")
    })
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 255)
  private String actor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private AuditAction action;

  @Column(nullable = false, length = 64)
  private String entity;

  @Column(name = "entity_id")
  private Long entityId;

  @Column(name = "occurred_at", nullable = false)
  private Instant timestamp;

  protected AuditLog() {}

  public AuditLog(String actor, AuditAction action, String entity, Long entityId, Instant timestamp) {
    this.actor = actor;
    this.action = action;
    this.entity = entity;
    this.entityId = entityId;
    this.timestamp = timestamp;
  }

  public Long getId() { return id; }
  public String getActor() { return actor; }
  public AuditAction getAction() { return action; }
  public String getEntity() { return entity; }
  public Long getEntityId() { return entityId; }
  public Instant getTimestamp() { return timestamp; }
}
