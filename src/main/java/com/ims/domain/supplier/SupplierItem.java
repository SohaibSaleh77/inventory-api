package com.ims.domain.supplier;

import java.math.BigDecimal;
import java.time.Instant;

/** Supplier-to-product mapping (supplier catalog entry with negotiated cost). */
public final class SupplierItem {

    private Long id;
    private Long supplierId;
    private Long productId;
    private String supplierSku;
    private BigDecimal unitCost;
    private boolean preferred;
    private int leadTimeDays = 7;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public SupplierItem() {
    }

    public SupplierItem(Long supplierId, Long productId, String supplierSku,
                        BigDecimal unitCost, boolean preferred, int leadTimeDays) {
        this.supplierId = supplierId;
        this.productId = productId;
        this.supplierSku = supplierSku;
        this.unitCost = unitCost;
        this.preferred = preferred;
        this.leadTimeDays = leadTimeDays;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getSupplierSku() { return supplierSku; }
    public void setSupplierSku(String supplierSku) { this.supplierSku = supplierSku; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public boolean isPreferred() { return preferred; }
    public void setPreferred(boolean preferred) { this.preferred = preferred; }

    public int getLeadTimeDays() { return leadTimeDays; }
    public void setLeadTimeDays(int leadTimeDays) { this.leadTimeDays = leadTimeDays; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
