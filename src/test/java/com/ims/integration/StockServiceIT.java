package com.ims.integration;

import com.ims.common.exception.InsufficientStockException;
import com.ims.domain.stock.StockItem;
import com.ims.domain.stock.StockMovement;
import com.ims.domain.stock.StockService;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.InvariantVerifier;
import com.ims.testing.TestUsers;
import com.ims.domain.warehouse.dto.WarehouseDtos;
import com.ims.domain.product.dto.ProductDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core stock mechanics: atomic in/out, negative-stock prevention, reserved
 * accounting, moving average cost, ledger consistency. The methods form a
 * narrative chain and are ordered accordingly.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StockServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;
    static StockService stockService;

    static WarehouseDtos.Response whA;
    static WarehouseDtos.Response whB;
    static ProductDtos.Response product;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        stockService = env.app.stockService;

        fx.category(users.manager, fx.unique("CAT"), null);
        whA = fx.warehouse(users.manager, fx.unique("WH"));
        whB = fx.warehouse(users.manager, fx.unique("WH"));
        product = fx.product(users.manager, fx.unique("SKU"), null,
                new BigDecimal("100.00"), new BigDecimal("150.00"), BigDecimal.ZERO);
    }

    @AfterAll
    static void tearDown() {
        // verify invariants after everything this class did
        List<InvariantVerifier.Violation> violations = new InvariantVerifier(env).verifyAll();
        assertEquals(List.of(), violations, () -> "invariants broken: " + violations);
        env.truncateAll();
    }

    private StockItem stock(long productId, long warehouseId) {
        return stockService.getStock(users.viewer, productId, warehouseId);
    }

    @Test
    @Order(1)
    void inboundCreatesRowAndUpdatesMovingAverage() {
        env.transactions.inTransaction(conn -> stockService.applyStockChange(
                conn, users.manager, product.id(), whA.id(), new BigDecimal("10"),
                new BigDecimal("100.00"), false, StockMovement.PURCHASE_RECEIPT,
                "TEST", 1L, "TEST-1", null, null));
        StockItem item = stock(product.id(), whA.id());
        assertEquals(new BigDecimal("10.000"), item.getQtyOnHand());
        assertEquals(new BigDecimal("100.0000"), item.getAvgUnitCost());

        // second receipt at different cost -> blended average
        env.transactions.inTransaction(conn -> stockService.applyStockChange(
                conn, users.manager, product.id(), whA.id(), new BigDecimal("10"),
                new BigDecimal("200.00"), false, StockMovement.PURCHASE_RECEIPT,
                "TEST", 2L, "TEST-2", null, null));
        assertEquals(new BigDecimal("150.0000"), stock(product.id(), whA.id())
                .getAvgUnitCost(), "(10*100+10*200)/20 = 150");
    }

    @Test
    @Order(2)
    void outboundReducesOnHandAndCannotGoNegative() {
        env.transactions.inTransaction(conn -> stockService.applyStockChange(
                conn, users.manager, product.id(), whA.id(), new BigDecimal("-15"),
                null, false, StockMovement.SALE_SHIPMENT, "TEST", 3L, "TEST-3",
                null, null));
        assertEquals(new BigDecimal("5.000"), stock(product.id(), whA.id()).getQtyOnHand());

        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> env.transactions.inTransaction(conn -> stockService.applyStockChange(
                        conn, users.manager, product.id(), whA.id(), new BigDecimal("-6"),
                                null, false, StockMovement.SALE_SHIPMENT, "TEST", 4L,
                                "TEST-4", null, null)));
        assertEquals(0, BigDecimal.valueOf(5).compareTo(e.available()));
    }

    @Test
    @Order(3)
    void outboundCannotConsumeReservedQuantity() {
        // reserve 3 of the 5 on hand
        env.transactions.inTransaction(conn -> {
            stockService.reserve(conn, product.id(), whA.id(), new BigDecimal("3"));
            return null;
        });
        // only 2 available now
        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> env.transactions.inTransaction(conn -> stockService.applyStockChange(
                        conn, users.manager, product.id(), whA.id(), new BigDecimal("-3"),
                                null, false, StockMovement.TRANSFER_OUT, "TEST", 5L,
                                "TEST-5", null, null)));
        assertEquals(0, BigDecimal.valueOf(2).compareTo(e.available()));
    }

    @Test
    @Order(4)
    void fulfillmentConsumesReservedPool() {
        // reserved=3, on_hand=5: fulfil 3 via reserved pool
        env.transactions.inTransaction(conn -> stockService.applyStockChange(
                conn, users.manager, product.id(), whA.id(), new BigDecimal("-3"),
                null, true, StockMovement.SALE_SHIPMENT, "TEST", 6L, "TEST-6",
                null, null));
        StockItem item = stock(product.id(), whA.id());
        assertEquals(new BigDecimal("2.000"), item.getQtyOnHand());
        assertEquals(new BigDecimal("0.000"), item.getQtyReserved());
    }

    @Test
    @Order(5)
    void reservedUnderflowIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> env.transactions.inTransaction(conn -> {
                    stockService.releaseReservation(conn, product.id(), whA.id(),
                            new BigDecimal("99"));
                    return null;
                }));
    }

    @Test
    @Order(6)
    void zeroDeltaIsIllegal() {
        assertThrows(IllegalArgumentException.class,
                () -> env.transactions.inTransaction(conn -> stockService.applyStockChange(
                        conn, users.manager, product.id(), whA.id(), BigDecimal.ZERO,
                                null, false, StockMovement.ADJUSTMENT_IN, "TEST", 7L,
                                "TEST-7", null, null)));
    }

    @Test
    @Order(7)
    void ledgerReconcilesWithOnHand() {
        BigDecimal ledgerSum = env.transactions.withConnection(conn ->
                env.app.stockMovementDao.sumQuantity(conn, product.id(), whA.id()));
        assertEquals(0, stock(product.id(), whA.id()).getQtyOnHand()
                        .compareTo(ledgerSum),
                "SUM(movements) must equal qty_on_hand");
    }

    @Test
    @Order(8)
    void differentWarehousesAreIndependent() {
        env.transactions.inTransaction(conn -> stockService.applyStockChange(
                conn, users.manager, product.id(), whB.id(), new BigDecimal("7"),
                new BigDecimal("111.00"), false, StockMovement.PURCHASE_RECEIPT,
                "TEST", 8L, "TEST-8", null, null));
        assertEquals(new BigDecimal("7.000"), stock(product.id(), whB.id()).getQtyOnHand());
        assertEquals(new BigDecimal("2.000"), stock(product.id(), whA.id()).getQtyOnHand());
    }
}
