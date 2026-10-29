package com.ims.integration;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.purchase.PurchaseOrder;
import com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.LineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveLineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.UpdateRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.Response;
import com.ims.domain.stock.dto.StockDtos;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
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

/** Purchase order lifecycle + goods receiving. */
class PurchaseOrderServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;

    static SupplierDtos.Response supplier;
    static WarehouseDtos.Response warehouse;
    static ProductDtos.Response laptop;
    static ProductDtos.Response mouse;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        fx.category(users.manager, "IT-CAT", null);
        supplier = fx.supplier(users.manager, "IT-SUP");
        warehouse = fx.warehouse(users.manager, "IT-WH");
        laptop = fx.product(users.manager, "IT-LAP", "IT-CAT",
                new BigDecimal("900.00"), new BigDecimal("1200.00"), BigDecimal.TEN);
        mouse = fx.product(users.manager, "IT-MOU", "IT-CAT",
                new BigDecimal("18.00"), new BigDecimal("39.99"),
                new BigDecimal("100"));
    }

    @BeforeEach
    void resetDocuments() {
        env.resetDocuments();
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private CreateRequest createRequest() {
        return new CreateRequest("IT-SUP", "IT-WH", null, null, null, null,
                List.of(
                new LineRequest("IT-LAP", new BigDecimal("30"), new BigDecimal("912.50"),
                        new BigDecimal("15")),
                new LineRequest("IT-MOU", new BigDecimal("200"), new BigDecimal("18.50"),
                        BigDecimal.ZERO)));
    }

    @Test
    void createComputesTotalsServerSide() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        assertNotNull(po.poNo());
        assertTrue(po.poNo().startsWith("PO-"));
        // 30*912.50=27375 +15% tax = 31481.25; 200*18.50=3700
        assertEquals(new BigDecimal("31481.25"), po.lines().get(0).lineTotal());
        assertEquals(new BigDecimal("3700.00"), po.lines().get(1).lineTotal());
        assertEquals(new BigDecimal("35181.25"), po.total());
        assertEquals("DRAFT", po.status());
    }

    @Test
    void unknownProductLineRejected() {
        CreateRequest bad = new CreateRequest("IT-SUP", "IT-WH", null, null, null,
                null, List.of(new LineRequest("NOPE-404", BigDecimal.TEN, BigDecimal.TEN,
                        null)));
        assertThrows(ValidationException.class,
                () -> env.app.purchaseOrderService.create(users.manager, bad));
    }

    @Test
    void unknownSupplierAndWarehouseRejected() {
        CreateRequest badSupplier = new CreateRequest("NO-SUP", "IT-WH", null, null,
                null, null, List.of(new LineRequest("IT-LAP", BigDecimal.ONE,
                        BigDecimal.ONE, null)));
        assertThrows(ValidationException.class,
                () -> env.app.purchaseOrderService.create(users.manager, badSupplier));
        CreateRequest badWarehouse = new CreateRequest("IT-SUP", "NO-WH", null, null,
                null, null, List.of(new LineRequest("IT-LAP", BigDecimal.ONE,
                        BigDecimal.ONE, null)));
        assertThrows(ValidationException.class,
                () -> env.app.purchaseOrderService.create(users.manager, badWarehouse));
    }

    @Test
    void emptyLinesRejected() {
        CreateRequest empty = new CreateRequest("IT-SUP", "IT-WH", null, null, null,
                null, List.of());
        assertThrows(ValidationException.class,
                () -> env.app.purchaseOrderService.create(users.manager, empty));
    }

    @Test
    void negativeOrZeroQuantityRejected() {
        CreateRequest bad = new CreateRequest("IT-SUP", "IT-WH", null, null, null,
                null, List.of(new LineRequest("IT-LAP", BigDecimal.ZERO, BigDecimal.ONE,
                        null)));
        assertThrows(ValidationException.class,
                () -> env.app.purchaseOrderService.create(users.manager, bad));
    }

    @Test
    void lifecycleHappyPath() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        assertEquals("DRAFT", po.status());
        po = env.app.purchaseOrderService.submit(users.manager, po.id());
        assertEquals("SUBMITTED", po.status());
        po = env.app.purchaseOrderService.approve(users.manager, po.id());
        assertEquals("APPROVED", po.status());
        po = receiveAll(po);
        assertEquals("RECEIVED", po.status());
        po = env.app.purchaseOrderService.close(users.manager, po.id());
        assertEquals("CLOSED", po.status());
    }

    @Test
    void draftCannotBeApprovedDirectly() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        assertThrows(IllegalStateTransitionException.class,
                () -> env.app.purchaseOrderService.approve(users.manager, po.id()));
    }

    @Test
    void editOnlyAllowedInDraft() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        env.app.purchaseOrderService.submit(users.manager, po.id());
        assertThrows(IllegalStateTransitionException.class,
                () -> env.app.purchaseOrderService.update(users.manager, po.id(),
                        new UpdateRequest("IT-SUP", "IT-WH", null, null, null,
                                List.of(new LineRequest("IT-LAP", BigDecimal.ONE,
                                        BigDecimal.ONE, null)))));
    }

    @Test
    void approveRequiresApprovePermission() {
        Response po = env.app.purchaseOrderService.create(users.staff, createRequest());
        env.app.purchaseOrderService.submit(users.staff, po.id());
        assertThrows(com.ims.common.exception.ForbiddenException.class,
                () -> env.app.purchaseOrderService.approve(users.staff, po.id()));
        // manager can
        env.app.purchaseOrderService.approve(users.manager, po.id());
    }

    @Test
    void cancelAllowedBeforeReceiptsOnly() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        env.app.purchaseOrderService.submit(users.manager, po.id());
        env.app.purchaseOrderService.approve(users.manager, po.id());
        assertEquals("CANCELLED",
                env.app.purchaseOrderService.cancel(users.manager, po.id()).status());

        Response withReceipts = env.app.purchaseOrderService.create(users.manager,
                createRequest());
        env.app.purchaseOrderService.submit(users.manager, withReceipts.id());
        env.app.purchaseOrderService.approve(users.manager, withReceipts.id());
        receiveAll(withReceipts);
        assertThrows(IllegalStateTransitionException.class,
                () -> env.app.purchaseOrderService.cancel(users.manager,
                        withReceipts.id()));
    }

    @Test
    void partialReceiptsAdvanceStatusAndStock() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        env.app.purchaseOrderService.submit(users.manager, po.id());
        env.app.purchaseOrderService.approve(users.manager, po.id());

        long line1 = po.lines().get(0).id();
        var receipt = env.app.receivingService.receive(users.manager, po.id(),
                new ReceiveRequest(List.of(
                        new ReceiveLineRequest(line1, new BigDecimal("10"),
                                BigDecimal.ONE)), "partial"));
        assertNotNull(receipt.receiptNo());
        assertEquals("PARTIALLY_RECEIVED",
                env.app.purchaseOrderService.get(users.manager, po.id()).status());

        // stock increased by 10 (rejections do not restock)
        StockDtos.StockLevelResponse level = levelOf("IT-LAP");
        assertEquals(new BigDecimal("10.000"), level.qtyOnHand());
    }

    @Test
    void overReceiveRejected() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        env.app.purchaseOrderService.submit(users.manager, po.id());
        env.app.purchaseOrderService.approve(users.manager, po.id());
        long line2 = po.lines().get(1).id();
        assertThrows(ValidationException.class, () ->
                env.app.receivingService.receive(users.manager, po.id(),
                        new ReceiveRequest(List.of(new ReceiveLineRequest(line2,
                                new BigDecimal("201"), BigDecimal.ZERO)), null)));
    }

    @Test
    void receiveAgainstDraftOrSubmittedRejected() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        long line1 = po.lines().get(0).id();
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.receivingService.receive(users.manager, po.id(),
                        new ReceiveRequest(List.of(new ReceiveLineRequest(line1,
                                BigDecimal.ONE, BigDecimal.ONE)), null)));
        env.app.purchaseOrderService.submit(users.manager, po.id());
        assertThrows(IllegalStateTransitionException.class, () ->
                env.app.receivingService.receive(users.manager, po.id(),
                        new ReceiveRequest(List.of(new ReceiveLineRequest(line1,
                                BigDecimal.ONE, BigDecimal.ONE)), null)));
    }

    @Test
    void receivingUnknownLineRejected() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        env.app.purchaseOrderService.submit(users.manager, po.id());
        env.app.purchaseOrderService.approve(users.manager, po.id());
        assertThrows(ValidationException.class, () ->
                env.app.receivingService.receive(users.manager, po.id(),
                        new ReceiveRequest(List.of(new ReceiveLineRequest(999999,
                                BigDecimal.ONE, BigDecimal.ZERO)), null)));
    }

    @Test
    void movingAverageCostUpdatesAcrossReceipts() {
        Response po = env.app.purchaseOrderService.create(users.manager, createRequest());
        env.app.purchaseOrderService.submit(users.manager, po.id());
        env.app.purchaseOrderService.approve(users.manager, po.id());
        receiveAll(po);
        // 30 laptops at 912.50 -> avg cost exactly 912.50
        assertEquals(new BigDecimal("912.5000"), levelOf("IT-LAP").avgUnitCost());
    }

    private Response receiveAll(Response po) {
        long line1 = po.lines().get(0).id();
        long line2 = po.lines().get(1).id();
        env.app.receivingService.receive(users.manager, po.id(), new ReceiveRequest(
                List.of(new ReceiveLineRequest(line1, new BigDecimal("30"), BigDecimal.ZERO),
                        new ReceiveLineRequest(line2, new BigDecimal("200"),
                                BigDecimal.ZERO)), null));
        return env.app.purchaseOrderService.get(users.manager, po.id());
    }

    private StockDtos.StockLevelResponse levelOf(String sku) {
        var page = env.app.stockQueryService.stockLevels(users.viewer, sku, null,
                null, com.ims.common.util.PageRequest.of(1, 50, null, null));
        return page.content().get(0);
    }
}
