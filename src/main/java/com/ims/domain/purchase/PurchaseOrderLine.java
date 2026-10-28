package com.ims.domain.purchase;

import java.math.BigDecimal;

/** Purchase order line. */
public final class PurchaseOrderLine {

    private Long id;
    private Long poId;
    private int lineNo;
    private Long productId;
    private BigDecimal qtyOrdered;
    private BigDecimal qtyReceived = BigDecimal.ZERO;
    private BigDecimal unitCost;
    private BigDecimal taxPct = BigDecimal.ZERO;
    private BigDecimal lineTotal;

    public PurchaseOrderLine() {
    }

    public PurchaseOrderLine(Long poId, int lineNo, Long productId,
                             BigDecimal qtyOrdered, BigDecimal unitCost,
                             BigDecimal taxPct, BigDecimal lineTotal) {
        this.poId = poId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.qtyOrdered = qtyOrdered;
        this.unitCost = unitCost;
        this.taxPct = taxPct;
        this.lineTotal = lineTotal;
    }

    public BigDecimal qtyOutstanding() {
        return qtyOrdered.subtract(qtyReceived);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPoId() { return poId; }
    public void setPoId(Long poId) { this.poId = poId; }

    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQtyOrdered() { return qtyOrdered; }
    public void setQtyOrdered(BigDecimal qtyOrdered) { this.qtyOrdered = qtyOrdered; }

    public BigDecimal getQtyReceived() { return qtyReceived; }
    public void setQtyReceived(BigDecimal qtyReceived) { this.qtyReceived = qtyReceived; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getTaxPct() { return taxPct; }
    public void setTaxPct(BigDecimal taxPct) { this.taxPct = taxPct; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
}
