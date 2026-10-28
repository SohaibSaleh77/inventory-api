package com.ims.domain.audit;

import java.time.Instant;

/** Immutable audit log record. */
public final class AuditLog {

    private Long id;
    private Instant occurredAt;
    private Long actorId;
    private String actorUsername;
    private String action;
    private String entityType;
    private Long entityId;
    private String entityNo;
    private String beforeJson;
    private String afterJson;
    private String ipAddress;
    private String detail;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }

    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public String getEntityNo() { return entityNo; }
    public void setEntityNo(String entityNo) { this.entityNo = entityNo; }

    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }

    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
