package com.ims.domain.purchase;

import java.math.BigDecimal;

/** One received quantity on a goods receipt note. */
public final class ReceiptLine {

    private Long id;
    private Long receiptId;
    private Long poLineId;
    private Long productId;
    private BigDecimal qtyAccepted;
    private BigDecimal qtyRejected = BigDecimal.ZERO;
    private BigDecimal unitCost;

    public ReceiptLine() {
    }

    public ReceiptLine(Long receiptId, Long poLineId, Long productId,
                       BigDecimal qtyAccepted, BigDecimal qtyRejected,
                       BigDecimal unitCost) {
        this.receiptId = receiptId;
        this.poLineId = poLineId;
        this.productId = productId;
        this.qtyAccepted = qtyAccepted;
        this.qtyRejected = qtyRejected;
        this.unitCost = unitCost;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }

    public Long getPoLineId() { return poLineId; }
    public void setPoLineId(Long poLineId) { this.poLineId = poLineId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQtyAccepted() { return qtyAccepted; }
    public void setQtyAccepted(BigDecimal qtyAccepted) { this.qtyAccepted = qtyAccepted; }

    public BigDecimal getQtyRejected() { return qtyRejected; }
    public void setQtyRejected(BigDecimal qtyRejected) { this.qtyRejected = qtyRejected; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
}
