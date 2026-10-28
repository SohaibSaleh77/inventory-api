package com.ims.domain.stock;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Materialized stock level of one product in one warehouse.
 * qtyOnHand is the ledger truth; qtyReserved is held by open reservations.
 * Invariant: qtyReserved <= qtyOnHand (enforced by CHECK + service locks).
 */
public final class StockItem {

    private Long id;
    private Long productId;
    private Long warehouseId;
    private BigDecimal qtyOnHand = BigDecimal.ZERO;
    private BigDecimal qtyReserved = BigDecimal.ZERO;
    private BigDecimal avgUnitCost = BigDecimal.ZERO;
    private Instant lastCountedAt;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public StockItem() {
    }

    public StockItem(Long productId, Long warehouseId) {
        this.productId = productId;
        this.warehouseId = warehouseId;
    }

    public BigDecimal qtyAvailable() {
        return qtyOnHand.subtract(qtyReserved);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public BigDecimal getQtyOnHand() { return qtyOnHand; }
    public void setQtyOnHand(BigDecimal qtyOnHand) { this.qtyOnHand = qtyOnHand; }

    public BigDecimal getQtyReserved() { return qtyReserved; }
    public void setQtyReserved(BigDecimal qtyReserved) { this.qtyReserved = qtyReserved; }

    public BigDecimal getAvgUnitCost() { return avgUnitCost; }
    public void setAvgUnitCost(BigDecimal avgUnitCost) { this.avgUnitCost = avgUnitCost; }

    public Instant getLastCountedAt() { return lastCountedAt; }
    public void setLastCountedAt(Instant lastCountedAt) { this.lastCountedAt = lastCountedAt; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
