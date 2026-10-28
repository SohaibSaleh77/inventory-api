package com.ims.domain.sales;

import java.math.BigDecimal;

/** One returned quantity on a customer return. */
public final class SalesReturnLine {

    private Long id;
    private Long salesReturnId;
    private Long salesOrderLineId;
    private Long productId;
    private BigDecimal qtyReturned;
    private boolean restock = true;
    private BigDecimal creditAmount;

    public SalesReturnLine() {
    }

    public SalesReturnLine(Long salesReturnId, Long salesOrderLineId, Long productId,
                           BigDecimal qtyReturned, boolean restock,
                           BigDecimal creditAmount) {
        this.salesReturnId = salesReturnId;
        this.salesOrderLineId = salesOrderLineId;
        this.productId = productId;
        this.qtyReturned = qtyReturned;
        this.restock = restock;
        this.creditAmount = creditAmount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSalesReturnId() { return salesReturnId; }
    public void setSalesReturnId(Long salesReturnId) { this.salesReturnId = salesReturnId; }

    public Long getSalesOrderLineId() { return salesOrderLineId; }
    public void setSalesOrderLineId(Long salesOrderLineId) { this.salesOrderLineId = salesOrderLineId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getQtyReturned() { return qtyReturned; }
    public void setQtyReturned(BigDecimal qtyReturned) { this.qtyReturned = qtyReturned; }

    public boolean isRestock() { return restock; }
    public void setRestock(boolean restock) { this.restock = restock; }

    public BigDecimal getCreditAmount() { return creditAmount; }
    public void setCreditAmount(BigDecimal creditAmount) { this.creditAmount = creditAmount; }
}
