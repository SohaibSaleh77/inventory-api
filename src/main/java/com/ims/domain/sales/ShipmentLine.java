package com.ims.domain.sales;

import java.math.BigDecimal;

/** One shipped quantity line (ties shipments to SO lines with COGS snapshot). */
public final class ShipmentLine {

    private Long id;
    private Long shipmentId;
    private Long salesOrderLineId;
    private Long productId;
    private BigDecimal qtyShipped;
    private BigDecimal unitCostAtShip;

    public ShipmentLine() {
    }

    public ShipmentLine(Long shipmentId, Long salesOrderLineId, Long productId,
                        BigDecimal qtyShipped, BigDecimal unitCostAtShip) {
        this.shipmentId = shipmentId;
        this.salesOrderLineId = salesOrderLineId;
        this.productId = productId;
        this.qtyShipped = qtyShipped;
        this.unitCostAtShip = unitCostAtShip;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getShipmentId() { return shipmentId; }
    public void setShipmentId(Long shipmentId) { this.shipmentId = shipmentId; }

    public Long getSalesOrderLineId() { return salesOrderLineId; }
    public void setSalesOrderLineId(Long salesOrderLineId) { this.salesOrderLineId = salesOrderLineId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQtyShipped() { return qtyShipped; }
    public void setQtyShipped(BigDecimal qtyShipped) { this.qtyShipped = qtyShipped; }

    public BigDecimal getUnitCostAtShip() { return unitCostAtShip; }
    public void setUnitCostAtShip(BigDecimal unitCostAtShip) { this.unitCostAtShip = unitCostAtShip; }
}
