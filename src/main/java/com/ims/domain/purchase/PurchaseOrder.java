package com.ims.domain.purchase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Purchase order header. */
public final class PurchaseOrder {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String poNo;
    private Long supplierId;
    private Long warehouseId;
    private String status = STATUS_DRAFT;
    private LocalDate orderDate;
    private LocalDate expectedDate;
    private String currency = "USD";
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal taxTotal = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private String notes;
    private int version;
    private Long createdBy;
    private Long approvedBy;
    private Instant approvedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public PurchaseOrder() {
    }

    public PurchaseOrder(Long supplierId, Long warehouseId, LocalDate orderDate) {
        this.supplierId = supplierId;
        this.warehouseId = warehouseId;
        this.orderDate = orderDate;
    }

    /** States from which the order can still be edited. */
    public boolean isEditable() {
        return STATUS_DRAFT.equals(status);
    }

    public boolean isReceivable() {
        return STATUS_APPROVED.equals(status) || STATUS_PARTIALLY_RECEIVED.equals(status);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPoNo() { return poNo; }
    public void setPoNo(String poNo) { this.poNo = poNo; }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }

    public LocalDate getExpectedDate() { return expectedDate; }
    public void setExpectedDate(LocalDate expectedDate) { this.expectedDate = expectedDate; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getTaxTotal() { return taxTotal; }
    public void setTaxTotal(BigDecimal taxTotal) { this.taxTotal = taxTotal; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
