package com.ims.integration;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.InsufficientStockException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.sales.SalesOrder;
import com.ims.domain.sales.dto.SalesDtos.CreateRequest;
import com.ims.domain.sales.dto.SalesDtos.LineRequest;
import com.ims.domain.sales.dto.SalesDtos.Response;
import com.ims.domain.sales.dto.SalesDtos.ReturnLineRequest;
import com.ims.domain.sales.dto.SalesDtos.ReturnRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipLineRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipRequest;
import com.ims.domain.sales.dto.SalesDtos.UpdateRequest;
import com.ims.domain.stock.dto.StockDtos;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import com.ims.domain.customer.dto.CustomerDtos;
import com.ims.domain.product.dto.ProductDtos;
import com.ims.domain.supplier.dto.SupplierDtos;
import com.ims.domain.warehouse.dto.WarehouseDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sales order lifecycle: reservation, fulfilment, returns. */
class SalesOrderServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;

    static CustomerDtos.Response customer;
    static WarehouseDtos.Response warehouse;
    static ProductDtos.Response product;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        fx.category(users.manager, "S-CAT", null);
        SupplierDtos.Response supplier = fx.supplier(users.manager, "S-SUP");
        warehouse = fx.warehouse(users.manager, "S-WH");
        product = fx.product(users.manager, "S-SKU", "S-CAT",
                new BigDecimal("50.00"), new BigDecimal("99.00"), BigDecimal.TEN);
        // stock the warehouse with 100 units at 50.00
        fx.receiveStock(users.manager, "S-SUP", "S-WH", "S-SKU", 100,
                new BigDecimal("50.00"));
        customer = fx.customer(users.manager, "S-CUS");
    }

    @BeforeEach
    void resetAndRestock() {
        env.resetDocuments();
        fx.receiveStock(users.manager, "S-SUP", "S-WH", "S-SKU", 100,
                new BigDecimal("50.00"));
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private CreateRequest orderFor(int qty, BigDecimal price) {
        return new CreateRequest("S-CUS", "S-WH", null, null, List.of(
                new LineRequest("S-SKU", BigDecimal.valueOf(qty), price,
                        new BigDecimal("10"), new BigDecimal("15"))));
    }

    @Test
    void totalsIncludeDiscountAndTax() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(10,
                new BigDecimal("100.00")));
        // 10*100 with 10% discount = 900 + 15% tax = 1035
        assertEquals(new BigDecimal("900.00"), so.subtotal());
        assertEquals(new BigDecimal("100.00"), so.discountTotal());
        assertEquals(new BigDecimal("135.00"), so.taxTotal());
        assertEquals(new BigDecimal("1035.00"), so.total());
        assertEquals("DRAFT", so.status());
    }

    @Test
    void confirmReservesStockAtomically() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(20,
                new BigDecimal("99.00")));
        so = env.app.salesOrderService.confirm(users.staff, so.id());
        assertEquals("CONFIRMED", so.status());
        assertEquals(new BigDecimal("20.000"), so.lines().get(0).qtyReserved());
        StockDtos.StockLevelResponse level = levelOf();
        assertEquals(new BigDecimal("100.000"), level.qtyOnHand());
        assertEquals(new BigDecimal("20.000"), level.qtyReserved());
        assertEquals(new BigDecimal("80.000"), level.qtyAvailable());
    }

    @Test
    void confirmFailsWhenInsufficientAndRollsBackEverything() {
        BigDecimal reservedBefore = levelOf().qtyReserved();
        // 2 lines: first reserving 70 (ok), second needing 100 (would exceed)
        CreateRequest greedy = new CreateRequest("S-CUS", "S-WH", null, null, List.of(
                new LineRequest("S-SKU", new BigDecimal("70"), new BigDecimal("99.00"),
                        null, null),
                new LineRequest("S-SKU", new BigDecimal("100"), new BigDecimal("99.00"),
                        null, null)));
        Response so = env.app.salesOrderService.create(users.staff, greedy);
        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> env.app.salesOrderService.confirm(users.staff, so.id()));
        assertNotNull(e.available());
        // atomicity: line 1's reservation was rolled back
        assertEquals(reservedBefore, levelOf().qtyReserved(),
                "no partial reservations may survive a failed confirm");
        assertEquals("DRAFT",
                env.app.salesOrderService.get(users.staff, so.id()).status());
    }

    @Test
    void editOnlyInDraft() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(1,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.salesOrderService.update(users.staff, so.id(),
                        new UpdateRequest("S-CUS", "S-WH", null, null,
                                List.of(new LineRequest("S-SKU", BigDecimal.ONE,
                                        BigDecimal.ONE, null, null)))));
    }

    @Test
    void cancelReleasesReservations() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(15,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        BigDecimal reservedDuringConfirm = levelOf().qtyReserved();
        so = env.app.salesOrderService.cancel(users.staff, so.id());
        assertEquals("CANCELLED", so.status());
        assertEquals(reservedDuringConfirm.subtract(new BigDecimal("15.000")),
                levelOf().qtyReserved());
        assertEquals(new BigDecimal("0.000"), levelOf().qtyReserved());
    }

    @Test
    void partialShipmentThenFinalShipment() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(30,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        long line = so.lines().get(0).id();

        var shipment1 = env.app.fulfillmentService.ship(users.staff, so.id(),
                new ShipRequest(List.of(new ShipLineRequest(line,
                        new BigDecimal("12"))), "Aramex", "TRK-1", null));
        assertNotNull(shipment1.shipmentNo());
        assertEquals("PARTIALLY_FULFILLED",
                env.app.salesOrderService.get(users.staff, so.id()).status());

        var shipment2 = env.app.fulfillmentService.ship(users.staff, so.id(),
                new ShipRequest(List.of(new ShipLineRequest(line,
                        new BigDecimal("18"))), "Aramex", "TRK-2", null));
        assertEquals("FULFILLED",
                env.app.salesOrderService.get(users.staff, so.id()).status());
        assertEquals(2, env.app.fulfillmentService.shipmentsForSo(users.staff,
                so.id()).size());

        // on hand reduced by 30, reserved back to zero
        StockDtos.StockLevelResponse level = levelOf();
        assertEquals(new BigDecimal("70.000"), level.qtyOnHand());
        assertEquals(new BigDecimal("0.000"), level.qtyReserved());
    }

    @Test
    void cannotShipMoreThanReserved() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(5,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        long line = so.lines().get(0).id();
        assertThrows(ValidationException.class, () ->
                env.app.fulfillmentService.ship(users.staff, so.id(),
                        new ShipRequest(List.of(new ShipLineRequest(line,
                                new BigDecimal("6"))), null, null, null)));
        assertThrows(ValidationException.class, () ->
                env.app.fulfillmentService.ship(users.staff, so.id(),
                        new ShipRequest(List.of(new ShipLineRequest(line,
                                BigDecimal.ZERO)), null, null, null)));
    }

    @Test
    void cannotShipDraftOrder() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(5,
                new BigDecimal("99.00")));
        long line = so.lines().get(0).id();
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.fulfillmentService.ship(users.staff, so.id(),
                        new ShipRequest(List.of(new ShipLineRequest(line,
                                BigDecimal.ONE)), null, null, null)));
    }

    @Test
    void cancelRejectedAfterShipping() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(5,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        long line = so.lines().get(0).id();
        env.app.fulfillmentService.ship(users.staff, so.id(),
                new ShipRequest(List.of(new ShipLineRequest(line, BigDecimal.ONE)),
                        null, null, null));
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.salesOrderService.cancel(users.staff, so.id()));
    }

    @Test
    void returnsRestockAndRespectShippedQty() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(10,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        long line = so.lines().get(0).id();
        env.app.fulfillmentService.ship(users.staff, so.id(),
                new ShipRequest(List.of(new ShipLineRequest(line,
                        new BigDecimal("10"))), null, null, null));
        BigDecimal onHandAfterShip = levelOf().qtyOnHand();

        // return 3 with restock
        var ret = env.app.salesReturnService.receiveReturn(users.staff, so.id(),
                new ReturnRequest(List.of(new ReturnLineRequest(line,
                        new BigDecimal("3"), true)), "DEFECTIVE", null));
        assertNotNull(ret.returnNo());
        assertEquals(onHandAfterShip.add(new BigDecimal("3.000")), levelOf().qtyOnHand());
        assertEquals(new BigDecimal("267.30"), ret.lines().get(0).creditAmount(),
                "3 * 99.00 = 297 -> minus 10% discount = 267.30");

        // cumulative returns cannot exceed shipped (10)
        assertThrows(ValidationException.class, () ->
                env.app.salesReturnService.receiveReturn(users.staff, so.id(),
                        new ReturnRequest(List.of(new ReturnLineRequest(line,
                                new BigDecimal("8"), true)), "OTHER", null)));

        // no-restock return does not add stock
        BigDecimal onHand = levelOf().qtyOnHand();
        env.app.salesReturnService.receiveReturn(users.staff, so.id(),
                new ReturnRequest(List.of(new ReturnLineRequest(line,
                        new BigDecimal("2"), false)), "DEFECTIVE", null));
        assertEquals(onHand, levelOf().qtyOnHand());
    }

    @Test
    void returnsRejectedForUnshippedOrders() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(2,
                new BigDecimal("99.00")));
        long line = so.lines().get(0).id();
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.salesReturnService.receiveReturn(users.staff, so.id(),
                        new ReturnRequest(List.of(new ReturnLineRequest(line,
                                BigDecimal.ONE, true)), "DEFECTIVE", null)));
    }

    @Test
    void viewerCannotCreateOrders() {
        assertThrows(com.ims.common.exception.ForbiddenException.class, () ->
                env.app.salesOrderService.create(users.viewer,
                        orderFor(1, BigDecimal.ONE)));
    }

    @Test
    void closeOnlyFromFulfilled() {
        Response so = env.app.salesOrderService.create(users.staff, orderFor(4,
                new BigDecimal("99.00")));
        env.app.salesOrderService.confirm(users.staff, so.id());
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.salesOrderService.close(users.staff, so.id()));
        long line = so.lines().get(0).id();
        env.app.fulfillmentService.ship(users.staff, so.id(),
                new ShipRequest(List.of(new ShipLineRequest(line,
                        new BigDecimal("4"))), null, null, null));
        assertEquals("CLOSED",
                env.app.salesOrderService.close(users.staff, so.id()).status());
    }

    private StockDtos.StockLevelResponse levelOf() {
        return env.app.stockQueryService.stockLevels(users.viewer, "S-SKU", null, null,
                com.ims.common.util.PageRequest.of(1, 50, null, null)).content().get(0);
    }
}
