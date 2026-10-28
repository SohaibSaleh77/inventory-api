package com.ims.domain.transfer;

import java.math.BigDecimal;
import java.time.Instant;

/** Inter-warehouse stock transfer with a two-phase lifecycle. */
public final class StockTransfer {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String transferNo;
    private Long productId;
    private Long fromWarehouseId;
    private Long toWarehouseId;
    private BigDecimal quantity;
    private String status = STATUS_PENDING;
    private String note;
    private int version;
    private Long createdBy;
    private Instant createdAt;
    private Long dispatchedBy;
    private Instant dispatchedAt;
    private Long receivedBy;
    private Instant receivedAt;

    public StockTransfer() {
    }

    public StockTransfer(Long productId, Long fromWarehouseId, Long toWarehouseId,
                         BigDecimal quantity, String note, Long createdBy) {
        this.productId = productId;
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.quantity = quantity;
        this.note = note;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTransferNo() { return transferNo; }
    public void setTransferNo(String transferNo) { this.transferNo = transferNo; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getFromWarehouseId() { return fromWarehouseId; }
    public void setFromWarehouseId(Long fromWarehouseId) { this.fromWarehouseId = fromWarehouseId; }

    public Long getToWarehouseId() { return toWarehouseId; }
    public void setToWarehouseId(Long toWarehouseId) { this.toWarehouseId = toWarehouseId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getDispatchedBy() { return dispatchedBy; }
    public void setDispatchedBy(Long dispatchedBy) { this.dispatchedBy = dispatchedBy; }

    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }

    public Long getReceivedBy() { return receivedBy; }
    public void setReceivedBy(Long receivedBy) { this.receivedBy = receivedBy; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
