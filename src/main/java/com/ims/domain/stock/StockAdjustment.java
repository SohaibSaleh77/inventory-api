package com.ims.domain.stock;

import java.math.BigDecimal;
import java.time.Instant;

/** A documented stock correction - always paired with a ledger movement. */
public final class StockAdjustment {

    public static final String REASON_DAMAGE = "DAMAGE";
    public static final String REASON_LOSS = "LOSS";
    public static final String REASON_THEFT = "THEFT";
    public static final String REASON_EXPIRY = "EXPIRY";
    public static final String REASON_COUNT_CORRECTION = "COUNT_CORRECTION";
    public static final String REASON_QUALITY_HOLD = "QUALITY_HOLD";
    public static final String REASON_FOUND = "FOUND";
    public static final String REASON_RETURN_TO_VENDOR = "RETURN_TO_VENDOR";
    public static final String REASON_OTHER = "OTHER";

    public static java.util.Set<String> allowedReasons() {
        return java.util.Set.of(REASON_DAMAGE, REASON_LOSS, REASON_THEFT, REASON_EXPIRY,
                REASON_COUNT_CORRECTION, REASON_QUALITY_HOLD, REASON_FOUND,
                REASON_RETURN_TO_VENDOR, REASON_OTHER);
    }

    private Long id;
    private String adjustmentNo;
    private Long warehouseId;
    private Long productId;
    private BigDecimal quantityDelta;
    private String reason;
    private String note;
    private Long createdBy;
    private Instant createdAt;

    public StockAdjustment() {
    }

    public StockAdjustment(Long warehouseId, Long productId, BigDecimal quantityDelta,
                           String reason, String note, Long createdBy) {
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.quantityDelta = quantityDelta;
        this.reason = reason;
        this.note = note;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAdjustmentNo() { return adjustmentNo; }
    public void setAdjustmentNo(String adjustmentNo) { this.adjustmentNo = adjustmentNo; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQuantityDelta() { return quantityDelta; }
    public void setQuantityDelta(BigDecimal quantityDelta) { this.quantityDelta = quantityDelta; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
