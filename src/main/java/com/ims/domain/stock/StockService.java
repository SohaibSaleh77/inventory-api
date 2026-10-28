package com.ims.domain.stock;

import com.ims.common.exception.InsufficientStockException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.util.Quantities;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.webhook.WebhookService;
import com.ims.security.AuthContext;
import com.ims.security.Permission;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Core inventory operations. Every quantity change in the system flows
 * through {@link #applyStockChange}: the stock row is locked FOR UPDATE,
 * invariants are re-verified under the lock, the row is updated, and a ledger
 * entry is written - all inside the caller's transaction.
 *
 * <p>On-hand can never go negative (service check + DB CHECK), and reserved
 * can never exceed on-hand.
 */
public final class StockService {

    public static final String REF_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String REF_SALES_ORDER = "SALES_ORDER";
    public static final String REF_TRANSFER = "TRANSFER";
    public static final String REF_ADJUSTMENT = "ADJUSTMENT";
    public static final String REF_RECEIPT = "RECEIPT";
    public static final String REF_SHIPMENT = "SHIPMENT";
    public static final String REF_RETURN = "RETURN";
    public static final String REF_STOCKTAKE = "STOCKTAKE";

    private final TransactionManager tm;
    private final StockItemDao stockItemDao;
    private final StockMovementDao movementDao;
    private final DocumentNumbers documentNumbers;
    private final WebhookService webhookService;

    public StockService(TransactionManager tm, StockItemDao stockItemDao,
                        StockMovementDao movementDao, DocumentNumbers documentNumbers,
                        WebhookService webhookService) {
        this.tm = tm;
        this.stockItemDao = stockItemDao;
        this.movementDao = movementDao;
        this.documentNumbers = documentNumbers;
        this.webhookService = webhookService;
    }

    /**
     * One atomic stock change.
     *
     * @param quantityDelta signed change: positive = in, negative = out
     * @param inboundCost   unit cost applied to moving average (inbound only, else null)
     * @param deductFromReserved if true, the outbound amount is taken from the
     *                           reserved pool (fulfilment), not from available
     */
    public AppliedStockChange applyStockChange(Connection conn, AuthContext actor,
                                               long productId, long warehouseId,
                                               BigDecimal quantityDelta,
                                               BigDecimal inboundCost,
                                               boolean deductFromReserved,
                                               String movementType, String refType,
                                               Long refId, String refNo, String reason,
                                               String note) throws SQLException {
        if (quantityDelta.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("quantityDelta must not be zero");
        }

        StockItem item = stockItemDao.getOrCreate(conn, productId, warehouseId);
        BigDecimal onHand = Quantities.of(item.getQtyOnHand());
        BigDecimal reserved = Quantities.of(item.getQtyReserved());
        BigDecimal delta = Quantities.of(quantityDelta);

        BigDecimal newOnHand;
        BigDecimal newReserved = reserved;

        if (delta.signum() > 0) {
            // Inbound: increases on-hand; cost feeds the moving average
            newOnHand = onHand.add(delta);
            if (inboundCost != null && inboundCost.signum() >= 0) {
                BigDecimal newAvg = Quantities.movingAverage(
                        onHand, item.getAvgUnitCost(), delta, inboundCost);
                item.setAvgUnitCost(newAvg);
            }
        } else {
            BigDecimal outQty = delta.negate();
            if (deductFromReserved) {
                // Fulfilment: consume reserved stock first
                if (reserved.compareTo(outQty) < 0) {
                    throw new InsufficientStockException(productId, warehouseId,
                            outQty, reserved);
                }
                newReserved = reserved.subtract(outQty);
                newOnHand = onHand.subtract(outQty);
            } else {
                // Shrink from available (unreserved) stock
                BigDecimal available = onHand.subtract(reserved);
                if (available.compareTo(outQty) < 0) {
                    throw new InsufficientStockException(productId, warehouseId,
                            outQty, available);
                }
                newOnHand = onHand.subtract(outQty);
            }
        }

        if (newOnHand.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientStockException(productId, warehouseId,
                    delta.negate(), onHand);
        }
        if (newReserved.compareTo(newOnHand) > 0) {
            throw new InsufficientStockException(productId, warehouseId,
                    newReserved, newOnHand);
        }

        item.setQtyOnHand(Quantities.of(newOnHand));
        item.setQtyReserved(newReserved);
        stockItemDao.updateQuantities(conn, item);

        StockMovement movement = new StockMovement(movementType, productId, warehouseId,
                delta, item.getQtyOnHand());
        movement.setUnitCost(delta.signum() > 0 ? inboundCost : item.getAvgUnitCost());
        movement.setRefType(refType);
        movement.setRefId(refId);
        movement.setRefNo(refNo);
        movement.setReason(reason);
        movement.setNote(note);
        movement.setPerformedBy(actor == null ? null : actor.userId());
        movement.setPerformedAt(Instant.now());
        movementDao.insert(conn, documentNumbers, movement);

        // Outbox a STOCK_LOW notification when the movement crosses the
        // product's reorder point downward (no-op when nobody subscribes).
        BigDecimal previousAvailable = onHand.subtract(reserved);
        BigDecimal newAvailable = Quantities.of(item.getQtyOnHand())
                .subtract(item.getQtyReserved());
        webhookService.enqueueStockLowIfCrossed(conn, productId, warehouseId,
                previousAvailable, newAvailable);

        return new AppliedStockChange(item, movement);
    }

    public record AppliedStockChange(StockItem stockItem, StockMovement movement) {
    }

    /** Reserve available stock for a sales order line (increases qty_reserved). */
    public void reserve(Connection conn, long productId, long warehouseId,
                        BigDecimal qty) throws SQLException {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("reserve qty must be positive");
        }
        StockItem item = stockItemDao.getOrCreate(conn, productId, warehouseId);
        BigDecimal available = Quantities.of(item.getQtyOnHand())
                .subtract(Quantities.of(item.getQtyReserved()));
        if (available.compareTo(qty) < 0) {
            throw new InsufficientStockException(productId, warehouseId, qty, available);
        }
        item.setQtyReserved(Quantities.of(item.getQtyReserved()).add(qty));
        stockItemDao.updateQuantities(conn, item);
        // Reserving shrinks the available-to-promise pool without moving
        // stock; a downward threshold crossing still deserves a STOCK_LOW.
        BigDecimal newAvailable = Quantities.of(item.getQtyOnHand())
                .subtract(item.getQtyReserved());
        webhookService.enqueueStockLowIfCrossed(conn, productId, warehouseId,
                available, newAvailable);
    }

    /** Release a reservation (cancel / reduce before fulfilment). */
    public void releaseReservation(Connection conn, long productId, long warehouseId,
                                   BigDecimal qty) throws SQLException {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("release qty must be positive");
        }
        StockItem item = stockItemDao.lockByProductAndWarehouse(conn, productId, warehouseId)
                .orElseThrow(() -> new NotFoundException(
                        "Stock for product " + productId + " in warehouse " + warehouseId));
        BigDecimal reserved = Quantities.of(item.getQtyReserved());
        if (reserved.compareTo(qty) < 0) {
            // Defensive: reserved accounting should never underflow
            throw new IllegalStateException(
                    "Reservation underflow for product " + productId
                            + " warehouse " + warehouseId
                            + ": reserved=" + reserved + " release=" + qty);
        }
        item.setQtyReserved(reserved.subtract(qty));
        stockItemDao.updateQuantities(conn, item);
    }

    // ---- read APIs ----

    public StockItem getStock(AuthContext actor, long productId, long warehouseId) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> stockItemDao
                .findByProductAndWarehouse(conn, productId, warehouseId)
                .orElseGet(() -> {
                    StockItem empty = new StockItem(productId, warehouseId);
                    empty.setQtyOnHand(BigDecimal.ZERO);
                    empty.setQtyReserved(BigDecimal.ZERO);
                    return empty;
                }));
    }

    public java.util.List<StockItem> getStockAcrossWarehouses(AuthContext actor,
                                                              long productId) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn ->
                stockItemDao.findNonZeroForProduct(conn, productId));
    }
}
