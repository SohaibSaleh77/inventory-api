package com.ims.domain.sales;

import java.math.BigDecimal;
import java.time.Instant;

/** Stock reservation held by a sales order line. */
public final class Reservation {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_RELEASED = "RELEASED";

    private Long id;
    private Long salesOrderId;
    private Long salesOrderLineId;
    private Long productId;
    private Long warehouseId;
    private BigDecimal qtyReserved;
    private String status = STATUS_ACTIVE;
    private Instant createdAt;
    private Instant releasedAt;

    public Reservation() {
    }

    public Reservation(Long salesOrderId, Long salesOrderLineId, Long productId,
                       Long warehouseId, BigDecimal qtyReserved) {
        this.salesOrderId = salesOrderId;
        this.salesOrderLineId = salesOrderLineId;
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.qtyReserved = qtyReserved;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(Long salesOrderId) { this.salesOrderId = salesOrderId; }

    public Long getSalesOrderLineId() { return salesOrderLineId; }
    public void setSalesOrderLineId(Long salesOrderLineId) { this.salesOrderLineId = salesOrderLineId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public BigDecimal getQtyReserved() { return qtyReserved; }
    public void setQtyReserved(BigDecimal qtyReserved) { this.qtyReserved = qtyReserved; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
}
