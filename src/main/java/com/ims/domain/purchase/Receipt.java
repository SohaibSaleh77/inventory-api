package com.ims.domain.purchase;

import java.time.Instant;

/** Goods receipt note (GRN) header. */
public final class Receipt {

    private Long id;
    private String receiptNo;
    private Long poId;
    private Long warehouseId;
    private Long receivedBy;
    private Instant receivedAt;
    private String note;

    public Receipt() {
    }

    public Receipt(Long poId, Long warehouseId, Long receivedBy, String note) {
        this.poId = poId;
        this.warehouseId = warehouseId;
        this.receivedBy = receivedBy;
        this.note = note;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }

    public Long getPoId() { return poId; }
    public void setPoId(Long poId) { this.poId = poId; }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public Long getReceivedBy() { return receivedBy; }
    public void setReceivedBy(Long receivedBy) { this.receivedBy = receivedBy; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
