package com.ims.domain.sales;

import java.time.Instant;

/** Customer return header. */
public final class SalesReturn {

    public static final String REASON_DEFECTIVE = "DEFECTIVE";
    public static final String REASON_WRONG_ITEM = "WRONG_ITEM";
    public static final String REASON_CUSTOMER_REMORSE = "CUSTOMER_REMORSE";
    public static final String REASON_DAMAGED_IN_TRANSIT = "DAMAGED_IN_TRANSIT";
    public static final String REASON_OTHER = "OTHER";

    public static java.util.Set<String> allowedReasons() {
        return java.util.Set.of(REASON_DEFECTIVE, REASON_WRONG_ITEM,
                REASON_CUSTOMER_REMORSE, REASON_DAMAGED_IN_TRANSIT, REASON_OTHER);
    }

    private Long id;
    private String returnNo;
    private Long salesOrderId;
    private Long warehouseId;
    private Long receivedBy;
    private Instant receivedAt;
    private String reason;
    private String note;

    public SalesReturn() {
    }

    public SalesReturn(Long salesOrderId, Long warehouseId, Long receivedBy,
                       String reason, String note) {
        this.salesOrderId = salesOrderId;
        this.warehouseId = warehouseId;
        this.receivedBy = receivedBy;
        this.reason = reason;
        this.note = note;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReturnNo() { return returnNo; }
    public void setReturnNo(String returnNo) { this.returnNo = returnNo; }

    public Long getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(Long salesOrderId) { this.salesOrderId = salesOrderId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public Long getReceivedBy() { return receivedBy; }
    public void setReceivedBy(Long receivedBy) { this.receivedBy = receivedBy; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
