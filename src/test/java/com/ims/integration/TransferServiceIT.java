package com.ims.integration;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.InsufficientStockException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.transfer.dto.TransferDtos.CreateRequest;
import com.ims.domain.transfer.dto.TransferDtos.Response;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import com.ims.domain.product.dto.ProductDtos;
import com.ims.domain.supplier.dto.SupplierDtos;
import com.ims.domain.warehouse.dto.WarehouseDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Two-phase inter-warehouse transfers. */
class TransferServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;

    static WarehouseDtos.Response whA;
    static WarehouseDtos.Response whB;
    static String sku;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        fx.category(users.manager, "T-CAT", null);
        SupplierDtos.Response supplier = fx.supplier(users.manager, "T-SUP");
        whA = fx.warehouse(users.manager, "T-WHA");
        whB = fx.warehouse(users.manager, "T-WHB");
        sku = "T-SKU";
        fx.product(users.manager, sku, "T-CAT", new BigDecimal("40.00"),
                new BigDecimal("80.00"), BigDecimal.ZERO);
        fx.receiveStock(users.manager, "T-SUP", "T-WHA", sku, 100,
                new BigDecimal("40.00"));
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private BigDecimal onHandIn(String warehouseCode) {
        var page = env.app.stockQueryService.stockLevels(users.viewer, sku, null, null,
                com.ims.common.util.PageRequest.of(1, 50, null, null));
        return page.content().stream()
                .filter(l -> warehouseCode.equals(l.warehouseCode()))
                .map(l -> l.qtyOnHand())
                .findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    void fullTwoPhaseTransfer() {
        Response transfer = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", new BigDecimal("30"), null));
        assertNotNull(transfer.transferNo());
        assertEquals("PENDING", transfer.status());

        assertEquals(new BigDecimal("100.000"), onHandIn("T-WHA"));
        transfer = env.app.transferService.dispatch(users.staff, transfer.id());
        assertEquals("DISPATCHED", transfer.status());
        assertEquals(new BigDecimal("70.000"), onHandIn("T-WHA"));
        assertEquals(0, BigDecimal.ZERO.compareTo(onHandIn("T-WHB")));

        transfer = env.app.transferService.receive(users.staff, transfer.id());
        assertEquals("RECEIVED", transfer.status());
        assertEquals(new BigDecimal("70.000"), onHandIn("T-WHA"));
        assertEquals(new BigDecimal("30.000"), onHandIn("T-WHB"));
    }

    @Test
    void dispatchFailsWhenInsufficientStock() {
        Response transfer = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", new BigDecimal("10000"), null));
        assertThrows(InsufficientStockException.class, () ->
                env.app.transferService.dispatch(users.staff, transfer.id()));
        assertEquals("PENDING",
                env.app.transferService.get(users.staff, transfer.id()).status(),
                "failed dispatch must not change state");
    }

    @Test
    void dispatchCannotTakeReservedStock() {
        // reserve some stock via a sales order
        var line = new com.ims.domain.sales.dto.SalesDtos.LineRequest(sku,
                new BigDecimal("50"), new BigDecimal("80.00"), null, null);
        var so = env.app.salesOrderService.create(users.staff,
                new com.ims.domain.sales.dto.SalesDtos.CreateRequest(
                        fx.customer(users.manager, fx.unique("CUS")).code(), "T-WHA",
                        null, null, java.util.List.of(line)));
        env.app.salesOrderService.confirm(users.staff, so.id());

        Response transfer = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", new BigDecimal("60"), null));
        assertThrows(InsufficientStockException.class, () ->
                env.app.transferService.dispatch(users.staff, transfer.id()));
        env.app.transferService.cancel(users.staff, transfer.id());
        env.app.salesOrderService.cancel(users.staff, so.id());
    }

    @Test
    void receiveRequiresDispatched() {
        Response transfer = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", BigDecimal.ONE, null));
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.transferService.receive(users.staff, transfer.id()));
    }

    @Test
    void dispatchTwiceRejected() {
        Response transfer = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", BigDecimal.ONE, null));
        env.app.transferService.dispatch(users.staff, transfer.id());
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.transferService.dispatch(users.staff, transfer.id()));
    }

    @Test
    void cancelOnlyWhilePending() {
        Response pending = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", BigDecimal.ONE, null));
        assertEquals("CANCELLED",
                env.app.transferService.cancel(users.staff, pending.id()).status());

        Response dispatched = env.app.transferService.create(users.staff,
                new CreateRequest(sku, "T-WHA", "T-WHB", BigDecimal.ONE, null));
        env.app.transferService.dispatch(users.staff, dispatched.id());
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.transferService.cancel(users.staff, dispatched.id()));
    }

    @Test
    void sameSourceAndTargetRejected() {
        assertThrows(ValidationException.class, () ->
                env.app.transferService.create(users.staff,
                        new CreateRequest(sku, "T-WHA", "T-WHA", BigDecimal.ONE, null)));
    }

    @Test
    void viewerCannotCreateTransfer() {
        assertThrows(com.ims.common.exception.ForbiddenException.class, () ->
                env.app.transferService.create(users.viewer,
                        new CreateRequest(sku, "T-WHA", "T-WHB", BigDecimal.ONE, null)));
    }

    @Test
    void ledgerHasBothLegsWithTransferRef() {
        var movements = env.app.stockQueryService.movements(users.viewer, null, null,
                "TRANSFER_OUT", "TRANSFER", null, null, null,
                com.ims.common.util.PageRequest.of(1, 50, null, null));
        assertEquals(true, movements.totalElements() > 0);
        movements = env.app.stockQueryService.movements(users.viewer, null, null,
                "TRANSFER_IN", "TRANSFER", null, null, null,
                com.ims.common.util.PageRequest.of(1, 50, null, null));
        assertEquals(true, movements.totalElements() > 0);
    }
}
