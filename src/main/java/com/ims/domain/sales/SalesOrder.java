package com.ims.domain.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Sales order header. */
public final class SalesOrder {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PARTIALLY_FULFILLED = "PARTIALLY_FULFILLED";
    public static final String STATUS_FULFILLED = "FULFILLED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String soNo;
    private Long customerId;
    private Long warehouseId;
    private String status = STATUS_DRAFT;
    private LocalDate orderDate;
    private LocalDate requestedDate;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal discountTotal = BigDecimal.ZERO;
    private BigDecimal taxTotal = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private String notes;
    private int version;
    private Long createdBy;
    private Long confirmedBy;
    private Instant confirmedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public SalesOrder() {
    }

    public SalesOrder(Long customerId, Long warehouseId, LocalDate orderDate) {
        this.customerId = customerId;
        this.warehouseId = warehouseId;
        this.orderDate = orderDate;
    }

    public boolean isEditable() {
        return STATUS_DRAFT.equals(status);
    }

    public boolean isFulfillable() {
        return STATUS_CONFIRMED.equals(status) || STATUS_PARTIALLY_FULFILLED.equals(status);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSoNo() { return soNo; }
    public void setSoNo(String soNo) { this.soNo = soNo; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }

    public LocalDate getRequestedDate() { return requestedDate; }
    public void setRequestedDate(LocalDate requestedDate) { this.requestedDate = requestedDate; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountTotal() { return discountTotal; }
    public void setDiscountTotal(BigDecimal discountTotal) { this.discountTotal = discountTotal; }

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

    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
