package com.ims.domain.stocktake;

import java.math.BigDecimal;
import java.time.Instant;

/** One count-sheet row: the frozen book quantity vs the physical count. */
public final class StocktakeLine {

    private Long id;
    private long stocktakeId;
    private long productId;
    private BigDecimal expectedQty;
    private BigDecimal countedQty;
    private int recounts;
    private Long countedBy;
    private Instant countedAt;
    private Instant createdAt;

    // ---- display-only fields (joined, never persisted) ----
    private String productSku;
    private String productName;
    private String countedByUsername;

    public StocktakeLine() {
    }

    public StocktakeLine(long stocktakeId, long productId, BigDecimal expectedQty) {
        this.stocktakeId = stocktakeId;
        this.productId = productId;
        this.expectedQty = expectedQty;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getStocktakeId() { return stocktakeId; }
    public void setStocktakeId(long stocktakeId) { this.stocktakeId = stocktakeId; }

    public long getProductId() { return productId; }
    public void setProductId(long productId) { this.productId = productId; }

    public BigDecimal getExpectedQty() { return expectedQty; }
    public void setExpectedQty(BigDecimal expectedQty) { this.expectedQty = expectedQty; }

    public BigDecimal getCountedQty() { return countedQty; }
    public void setCountedQty(BigDecimal countedQty) { this.countedQty = countedQty; }

    public int getRecounts() { return recounts; }
    public void setRecounts(int recounts) { this.recounts = recounts; }

    public Long getCountedBy() { return countedBy; }
    public void setCountedBy(Long countedBy) { this.countedBy = countedBy; }

    public Instant getCountedAt() { return countedAt; }
    public void setCountedAt(Instant countedAt) { this.countedAt = countedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getProductSku() { return productSku; }
    public void setProductSku(String productSku) { this.productSku = productSku; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getCountedByUsername() { return countedByUsername; }
    public void setCountedByUsername(String countedByUsername) { this.countedByUsername = countedByUsername; }

    /** counted - expected; null while the line is uncounted. */
    public BigDecimal variance() {
        return countedQty == null ? null
                : countedQty.subtract(expectedQty == null ? BigDecimal.ZERO
                        : expectedQty);
    }
}
