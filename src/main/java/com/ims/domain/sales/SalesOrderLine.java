package com.ims.domain.sales;

import java.math.BigDecimal;

/** Sales order line. */
public final class SalesOrderLine {

    private Long id;
    private Long soId;
    private int lineNo;
    private Long productId;
    private BigDecimal qtyOrdered;
    private BigDecimal qtyReserved = BigDecimal.ZERO;
    private BigDecimal qtyFulfilled = BigDecimal.ZERO;
    private BigDecimal unitPrice;
    private BigDecimal discountPct = BigDecimal.ZERO;
    private BigDecimal taxPct = BigDecimal.ZERO;
    private BigDecimal lineTotal;

    public SalesOrderLine() {
    }

    public SalesOrderLine(Long soId, int lineNo, Long productId, BigDecimal qtyOrdered,
                          BigDecimal unitPrice, BigDecimal discountPct, BigDecimal taxPct,
                          BigDecimal lineTotal) {
        this.soId = soId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.qtyOrdered = qtyOrdered;
        this.unitPrice = unitPrice;
        this.discountPct = discountPct;
        this.taxPct = taxPct;
        this.lineTotal = lineTotal;
    }

    public BigDecimal qtyOutstanding() {
        return qtyOrdered.subtract(qtyFulfilled);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSoId() { return soId; }
    public void setSoId(Long soId) { this.soId = soId; }

    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQtyOrdered() { return qtyOrdered; }
    public void setQtyOrdered(BigDecimal qtyOrdered) { this.qtyOrdered = qtyOrdered; }

    public BigDecimal getQtyReserved() { return qtyReserved; }
    public void setQtyReserved(BigDecimal qtyReserved) { this.qtyReserved = qtyReserved; }

    public BigDecimal getQtyFulfilled() { return qtyFulfilled; }
    public void setQtyFulfilled(BigDecimal qtyFulfilled) { this.qtyFulfilled = qtyFulfilled; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getDiscountPct() { return discountPct; }
    public void setDiscountPct(BigDecimal discountPct) { this.discountPct = discountPct; }

    public BigDecimal getTaxPct() { return taxPct; }
    public void setTaxPct(BigDecimal taxPct) { this.taxPct = taxPct; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
}
