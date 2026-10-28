package com.ims.domain.stock;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One append-only ledger entry. quantity is signed: positive = stock in,
 * negative = stock out. balance_after records the resulting on-hand quantity
 * so the full ledger reconciles without replaying history.
 */
public final class StockMovement {

    public static final String OPENING_BALANCE = "OPENING_BALANCE";
    public static final String PURCHASE_RECEIPT = "PURCHASE_RECEIPT";
    public static final String SALE_SHIPMENT = "SALE_SHIPMENT";
    public static final String SALE_RETURN = "SALE_RETURN";
    public static final String TRANSFER_OUT = "TRANSFER_OUT";
    public static final String TRANSFER_IN = "TRANSFER_IN";
    public static final String ADJUSTMENT_IN = "ADJUSTMENT_IN";
    public static final String ADJUSTMENT_OUT = "ADJUSTMENT_OUT";

    private Long id;
    private String movementNo;
    private Long productId;
    private Long warehouseId;
    private String movementType;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal balanceAfter;
    private String refType;
    private Long refId;
    private String refNo;
    private String reason;
    private String note;
    private Long performedBy;
    private Instant performedAt;
    private Instant createdAt;

    public StockMovement() {
    }

    public StockMovement(String movementType, Long productId, Long warehouseId,
                         BigDecimal quantity, BigDecimal balanceAfter) {
        this.movementType = movementType;
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.balanceAfter = balanceAfter;
    }

    public static String movementTypeForDelta(java.math.BigDecimal delta) {
        return delta.signum() > 0 ? ADJUSTMENT_IN : ADJUSTMENT_OUT;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMovementNo() { return movementNo; }
    public void setMovementNo(String movementNo) { this.movementNo = movementNo; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getPerformedBy() { return performedBy; }
    public void setPerformedBy(Long performedBy) { this.performedBy = performedBy; }

    public Instant getPerformedAt() { return performedAt; }
    public void setPerformedAt(Instant performedAt) { this.performedAt = performedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
