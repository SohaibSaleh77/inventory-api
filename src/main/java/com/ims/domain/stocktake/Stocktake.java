package com.ims.domain.stocktake;

import java.time.Instant;

/** A physical inventory count session for one warehouse. */
public final class Stocktake {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_POSTED = "POSTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String stocktakeNo;
    private long warehouseId;
    private String name;
    private boolean blind;
    private String status = STATUS_DRAFT;
    private String note;
    private Long createdBy;
    private Instant openedAt;
    private Instant closedAt;
    private Instant postedAt;
    private Long postedBy;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public Stocktake() {
    }

    public Stocktake(String stocktakeNo, long warehouseId, String name,
                     boolean blind, String note, Long createdBy) {
        this.stocktakeNo = stocktakeNo;
        this.warehouseId = warehouseId;
        this.name = name;
        this.blind = blind;
        this.note = note;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStocktakeNo() { return stocktakeNo; }
    public void setStocktakeNo(String stocktakeNo) { this.stocktakeNo = stocktakeNo; }

    public long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(long warehouseId) { this.warehouseId = warehouseId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isBlind() { return blind; }
    public void setBlind(boolean blind) { this.blind = blind; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }

    public Long getPostedBy() { return postedBy; }
    public void setPostedBy(Long postedBy) { this.postedBy = postedBy; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
