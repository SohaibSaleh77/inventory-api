package com.ims.domain.webhook;

import java.time.Instant;

/** An outbound webhook endpoint: URL, signing secret and subscribed events. */
public final class Webhook {

    private Long id;
    private String name;
    private String url;
    private String secret;
    private String events;
    private boolean active;
    private Long createdBy;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public Webhook() {
    }

    public Webhook(String name, String url, String secret, String events,
                   Long createdBy) {
        this.name = name;
        this.url = url;
        this.secret = secret;
        this.events = events;
        this.createdBy = createdBy;
        this.active = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public String getEvents() { return events; }
    public void setEvents(String events) { this.events = events; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Comma-separated subscribed event codes. */
    public java.util.Set<String> eventSet() {
        return WebhookEvents.parse(events);
    }
}
