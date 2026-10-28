package com.ims.domain.sales;

import java.math.BigDecimal;
import java.time.Instant;

/** Shipment (fulfilment) header. */
public final class Shipment {

    private Long id;
    private String shipmentNo;
    private Long salesOrderId;
    private Long warehouseId;
    private Long shippedBy;
    private Instant shippedAt;
    private String carrier;
    private String trackingNo;
    private String note;

    public Shipment() {
    }

    public Shipment(Long salesOrderId, Long warehouseId, Long shippedBy,
                    String carrier, String trackingNo, String note) {
        this.salesOrderId = salesOrderId;
        this.warehouseId = warehouseId;
        this.shippedBy = shippedBy;
        this.carrier = carrier;
        this.trackingNo = trackingNo;
        this.note = note;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getShipmentNo() { return shipmentNo; }
    public void setShipmentNo(String shipmentNo) { this.shipmentNo = shipmentNo; }

    public Long getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(Long salesOrderId) { this.salesOrderId = salesOrderId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public Long getShippedBy() { return shippedBy; }
    public void setShippedBy(Long shippedBy) { this.shippedBy = shippedBy; }

    public Instant getShippedAt() { return shippedAt; }
    public void setShippedAt(Instant shippedAt) { this.shippedAt = shippedAt; }

    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }

    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
