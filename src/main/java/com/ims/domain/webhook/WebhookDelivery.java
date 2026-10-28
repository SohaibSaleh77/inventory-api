package com.ims.domain.webhook;

import java.time.Instant;

/** One queued or delivered webhook notification (transactional outbox row). */
public final class WebhookDelivery {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private Long id;
    private long webhookId;
    private String eventType;
    private String payload;
    private String status = PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private Integer lastStatusCode;
    private String lastError;
    private Instant deliveredAt;
    private Instant createdAt;

    public WebhookDelivery() {
    }

    public WebhookDelivery(long webhookId, String eventType, String payload,
                           Instant nextAttemptAt) {
        this.webhookId = webhookId;
        this.eventType = eventType;
        this.payload = payload;
        this.nextAttemptAt = nextAttemptAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getWebhookId() { return webhookId; }
    public void setWebhookId(long webhookId) { this.webhookId = webhookId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public Integer getLastStatusCode() { return lastStatusCode; }
    public void setLastStatusCode(Integer lastStatusCode) { this.lastStatusCode = lastStatusCode; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
