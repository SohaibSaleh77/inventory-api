package com.ims.domain.category;

import java.time.Instant;

/** Category entity - forms a tree via parent_id. */
public final class Category {

    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private String description;
    private boolean active = true;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public Category() {
    }

    public Category(Long parentId, String code, String name, String description) {
        this.parentId = parentId;
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
