package com.ims.common.exception;

import java.math.BigDecimal;

/**
 * 409 - the requested quantity exceeds the available (unreserved) stock.
 * Carries full context so callers can react precisely.
 */
public class InsufficientStockException extends ConflictException {

    private final long productId;
    private final long warehouseId;
    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientStockException(long productId, long warehouseId,
                                      BigDecimal requested, BigDecimal available) {
        super(String.format(
                "Insufficient stock for product %d in warehouse %d: requested %s, available %s",
                productId, warehouseId, requested.toPlainString(), available.toPlainString()));
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.requested = requested;
        this.available = available;
    }

    public long productId() { return productId; }
    public long warehouseId() { return warehouseId; }
    public BigDecimal requested() { return requested; }
    public BigDecimal available() { return available; }
}
