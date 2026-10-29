package com.ims.simulation;

import com.ims.common.exception.ApiException;
import com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.LineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveLineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.Response;
import com.ims.domain.sales.dto.SalesDtos;
import com.ims.domain.stock.dto.StockDtos;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.InvariantVerifier;
import com.ims.testing.TestUsers;
import com.ims.domain.product.dto.ProductDtos;
import com.ims.domain.supplier.dto.SupplierDtos;
import com.ims.domain.warehouse.dto.WarehouseDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A full simulated trading day for a small electronics distributor, using
 * only real business operations through the real services. Includes
 * realistic chaos: rejected receipts, failed confirms due to stock, partial
 * shipments, returns, damage adjustments, and concurrent traffic. Afterwards
 * the global invariants must hold exactly.
 */
class BusinessDaySimulationIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;
    static SecureRandom random = new SecureRandom();

    static final String WAREHOUSE_MAIN = "SIM-WH-MAIN";
    static final String WAREHOUSE_NORTH = "SIM-WH-NORTH";

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void fullTradingDay() throws Exception {
        // ---------- 07:30 - master data ----------
        fx.category(users.manager, "SIM-LAPTOPS", null);
        fx.category(users.manager, "SIM-AUDIO", null);
        fx.category(users.manager, "SIM-ACC", null);

        SupplierDtos.Response techno = fx.supplier(users.manager, "SIM-TECHNO");
        SupplierDtos.Response soundwave = fx.supplier(users.manager, "SIM-SOUNDWAVE");
        fx.supplier(users.manager, "SIM-ACCWARE");

        fx.warehouse(users.manager, WAREHOUSE_MAIN);
        fx.warehouse(users.manager, WAREHOUSE_NORTH);

        var customerRiyadh = fx.customer(users.manager, "SIM-CUS-RUH");
        var customerJeddah = fx.customer(users.manager, "SIM-CUS-JED");
        var customerKhobar = fx.customer(users.manager, "SIM-CUS-KHB");

        record P(String sku, String category, BigDecimal cost, BigDecimal price,
                 BigDecimal reorderPoint) {
        }
        List<P> catalog = List.of(
                new P("SIM-LAP-14", "SIM-LAPTOPS", bd("950.00"), bd("1399.00"), bd("10")),
                new P("SIM-LAP-16", "SIM-LAPTOPS", bd("1200.00"), bd("1799.00"), bd("8")),
                new P("SIM-HEAD-BT", "SIM-AUDIO", bd("85.00"), bd("179.00"), bd("40")),
                new P("SIM-SPKR-MINI", "SIM-AUDIO", bd("60.00"), bd("129.00"), bd("50")),
                new P("SIM-MOUSE-ERG", "SIM-ACC", bd("18.50"), bd("39.99"), bd("100")),
                new P("SIM-KEY-MECH", "SIM-ACC", bd("45.00"), bd("99.00"), bd("60")),
                new P("SIM-DOCK-USB", "SIM-ACC", bd("110.00"), bd("199.00"), bd("25")),
                new P("SIM-CBL-HDMI", "SIM-ACC", bd("6.20"), bd("14.99"), bd("300")));
        for (P p : catalog) {
            fx.product(users.manager, p.sku(), p.category(), p.cost(), p.price,
                    p.reorderPoint());
        }

        // supplier catalog entries
        env.app.supplierService.upsertItem(users.manager, techno.id(),
                new com.ims.domain.supplier.dto.SupplierDtos.UpsertItemRequest(
                        "SIM-LAP-14", "TEC-L14", bd("912.50"), true, 21));
        env.app.supplierService.upsertItem(users.manager, techno.id(),
                new com.ims.domain.supplier.dto.SupplierDtos.UpsertItemRequest(
                        "SIM-LAP-16", "TEC-L16", bd("1150.00"), true, 21));

        // ---------- 08:15 - morning replenishment ----------
        // PO 1: laptops from Techno (rejected units expected)
        Response po1 = purchaseOrder(techno, WAREHOUSE_MAIN, List.of(
                line("SIM-LAP-14", 20, "912.50"),
                line("SIM-LAP-16", 10, "1150.00")));
        receiveFullyOrPartially(po1, Map.of(0, 18, 1, 10), 2); // 2 laptops damaged

        // PO 2: audio gear from Soundwave
        Response po2 = purchaseOrder(soundwave, WAREHOUSE_MAIN, List.of(
                line("SIM-HEAD-BT", 80, "85.00"),
                line("SIM-SPKR-MINI", 60, "60.00")));
        receiveFullyOrPartially(po2, Map.of(0, 80, 1, 60), 0);

        // PO 3: accessories (partial receipt only - delivery truck broke down)
        Response po3 = purchaseOrder(techno, WAREHOUSE_MAIN, List.of(
                line("SIM-MOUSE-ERG", 400, "18.50"),
                line("SIM-KEY-MECH", 200, "45.00"),
                line("SIM-DOCK-USB", 100, "110.00"),
                line("SIM-CBL-HDMI", 1000, "6.20")));
        receiveFullyOrPartially(po3, Map.of(0, 250, 1, 200, 2, 60, 3, 1000), 0);
        assertEquals("PARTIALLY_RECEIVED",
                env.app.purchaseOrderService.get(users.manager, po3.id()).status());

        // ---------- 10:00 - stock transfer to the northern branch ----------
        transfer("SIM-HEAD-BT", WAREHOUSE_MAIN, WAREHOUSE_NORTH, 20);
        transfer("SIM-MOUSE-ERG", WAREHOUSE_MAIN, WAREHOUSE_NORTH, 100);
        transfer("SIM-CBL-HDMI", WAREHOUSE_MAIN, WAREHOUSE_NORTH, 300);

        // ---------- 10:30 - morning sales rush ----------
        var order1 = salesOrder(customerRiyadh.code(), WAREHOUSE_MAIN, List.of(
                salesLine("SIM-LAP-14", 5, "1379.00", "2", "15"),
                salesLine("SIM-HEAD-BT", 10, "175.00", "0", "15"),
                salesLine("SIM-MOUSE-ERG", 50, "38.99", "5", "15")));
        confirm(order1);
        shipFully(order1);

        var order2 = salesOrder(customerJeddah.code(), WAREHOUSE_MAIN, List.of(
                salesLine("SIM-LAP-16", 4, "1799.00", "0", "15"),
                salesLine("SIM-DOCK-USB", 25, "195.00", "4", "15")));
        confirm(order2);
        shipPartially(order2, Map.of(0, 2, 1, 10)); // partial: laptops backordered

        // an order that CANNOT be confirmed: not enough docks left
        var tooGreedy = salesOrder(customerKhobar.code(), WAREHOUSE_MAIN, List.of(
                salesLine("SIM-DOCK-USB", 500, "199.00", "0", "15")));
        AtomicBoolean greedyFailed = new AtomicBoolean();
        try {
            confirm(tooGreedy);
        } catch (ApiException expected) {
            greedyFailed.set(true);
            assertEquals("DRAFT", env.app.salesOrderService
                    .get(users.staff, tooGreedy.id()).status());
        }
        assertTrue(greedyFailed.get(), "oversized order must be rejected");
        env.app.salesOrderService.cancel(users.staff, tooGreedy.id());

        // ---------- 13:00 - returns and damages ----------
        // customer returns 3 mice from order1
        long mouseLine = env.app.salesOrderService.get(users.staff, order1.id())
                .lines().get(2).id();
        env.app.salesReturnService.receiveReturn(users.staff, order1.id(),
                new SalesDtos.ReturnRequest(List.of(
                        new SalesDtos.ReturnLineRequest(mouseLine, bd("3"), true)),
                        "CUSTOMER_REMORSE", "opened boxes"));

        // warehouse damage: 5 mice crushed by forklift
        adjust("SIM-MOUSE-ERG", WAREHOUSE_MAIN, -5, "DAMAGE", "forklift accident");
        // count correction: found 2 extra hdmi cables
        adjust("SIM-CBL-HDMI", WAREHOUSE_MAIN, 2, "FOUND", "cycle count");
        // quality hold: 4 keyboards taken out
        adjust("SIM-KEY-MECH", WAREHOUSE_MAIN, -4, "QUALITY_HOLD", "batch QC");

        // ---------- 14:30 - concurrent afternoon orders ----------
        List<Thread> rush = new ArrayList<>();
        record Sale(String customer, String sku, int qty) {
        }
        List<Sale> afternoon = List.of(
                new Sale(customerRiyadh.code(), "SIM-CBL-HDMI", 120),
                new Sale(customerJeddah.code(), "SIM-SPKR-MINI", 25),
                new Sale(customerKhobar.code(), "SIM-HEAD-BT", 30),
                new Sale(customerRiyadh.code(), "SIM-MOUSE-ERG", 60),
                new Sale(customerJeddah.code(), "SIM-KEY-MECH", 40));
        for (Sale sale : afternoon) {
            Thread t = Thread.ofVirtual().start(() -> {
                var so = salesOrder(sale.customer(), WAREHOUSE_MAIN, List.of(
                        salesLine(sale.sku(), sale.qty(), "list", "0", "15")));
                try {
                    confirm(so);
                    shipFully(so);
                } catch (ApiException race) {
                    // acceptable: concurrent stock exhaustion rejects cleanly
                    env.app.salesOrderService.cancel(users.staff, so.id());
                }
            });
            rush.add(t);
        }
        for (Thread t : rush) {
            t.join(30_000);
        }

        // northern branch sells from its own stock
        var northOrder = salesOrder(customerKhobar.code(), WAREHOUSE_NORTH, List.of(
                salesLine("SIM-MOUSE-ERG", 40, "39.99", "0", "15")));
        confirm(northOrder);
        shipFully(northOrder);
        var northReturn = salesOrder(customerKhobar.code(), WAREHOUSE_NORTH, List.of(
                salesLine("SIM-HEAD-BT", 5, "179.00", "0", "15")));
        confirm(northReturn);
        shipFully(northReturn);

        // ---------- 16:00 - reorder what fell below the reorder point ----------
        var lowStock = env.app.reportService.lowStock(users.manager);
        assertTrue(lowStock.rows().size() > 0, "something must be low by now");
        for (var row : lowStock.rows()) {
            // draft-only: the next shift will review, submit and approve
            env.app.purchaseOrderService.create(users.staff,
                    new CreateRequest(techno.code(), WAREHOUSE_MAIN, null, null, null,
                            null, List.of(line(row.sku(),
                            row.shortfall().add(row.reorderQty())
                                    .max(BigDecimal.ONE).intValue(), "50.00"))));
        }

        // ---------- 17:00 - close the books: invariants + reports ----------
        List<InvariantVerifier.Violation> violations = new InvariantVerifier(env)
                .verifyAll();
        assertEquals(List.of(), violations,
                () -> "invariants broken after the trading day: " + violations);

        var valuation = env.app.reportService.stockValuation(users.manager);
        assertTrue(valuation.grandTotal().signum() > 0,
                "remaining stock must have value");
        var margin = env.app.reportService.grossMargin(users.manager, null, null);
        assertTrue(margin.totalRevenue().signum() > 0);
        assertTrue(margin.totalCogs().signum() > 0);
        assertTrue(margin.totalMargin().signum() > 0, "this distributor is profitable");

        // every movement reconciles per product/warehouse (already covered by
        // InvariantVerifier) and the audit trail captured the day
        long auditRows = env.rowCount("audit_logs");
        assertTrue(auditRows >= 60, "expected a rich audit trail, got " + auditRows);

        long movementRows = env.rowCount("stock_movements");
        assertTrue(movementRows >= 25, "expected a busy ledger, got " + movementRows);
    }

    // ---- helpers ----

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static LineRequest line(String sku, int qty, String cost) {
        return new LineRequest(sku, BigDecimal.valueOf(qty), bd(cost), BigDecimal.ZERO);
    }

    private Response purchaseOrder(SupplierDtos.Response supplier, String warehouse,
                                   List<LineRequest> lines) {
        Response po = env.app.purchaseOrderService.create(users.staff,
                new CreateRequest(supplier.code(), warehouse, null, null, null,
                        null, lines));
        env.app.purchaseOrderService.submit(users.staff, po.id());
        env.app.purchaseOrderService.approve(users.manager, po.id());
        return env.app.purchaseOrderService.get(users.staff, po.id());
    }

    /** Receive given quantities (qty - received = rejected on the first line). */
    private void receiveFullyOrPartially(Response po, Map<Integer, Integer> qtyByLine,
                                         int rejectedOnFirstLine) {
        List<ReceiveLineRequest> lines = new ArrayList<>();
        for (int i = 0; i < po.lines().size(); i++) {
            Integer qty = qtyByLine.get(i);
            if (qty == null) {
                continue;
            }
            long lineId = po.lines().get(i).id();
            BigDecimal rejected = i == 0 ? BigDecimal.valueOf(rejectedOnFirstLine)
                    : BigDecimal.ZERO;
            lines.add(new ReceiveLineRequest(lineId, BigDecimal.valueOf(qty),
                    rejected));
        }
        env.app.receivingService.receive(users.manager, po.id(),
                new ReceiveRequest(lines, "simulated delivery"));
    }

    private void transfer(String sku, String from, String to, int qty) {
        var t = env.app.transferService.create(users.staff,
                new com.ims.domain.transfer.dto.TransferDtos.CreateRequest(
                        sku, from, to, BigDecimal.valueOf(qty), null));
        env.app.transferService.dispatch(users.staff, t.id());
        env.app.transferService.receive(users.staff, t.id());
    }

    private SalesDtos.Response salesOrder(String customer, String warehouse,
                                          List<SalesDtos.LineRequest> lines) {
        return env.app.salesOrderService.create(users.staff,
                new SalesDtos.CreateRequest(customer, warehouse, null, null, lines));
    }

    private static SalesDtos.LineRequest salesLine(String sku, int qty, String price,
                                                   String discount, String tax) {
        BigDecimal unitPrice;
        if ("list".equals(price)) {
            unitPrice = null; // resolved below
        } else {
            unitPrice = bd(price);
        }
        BigDecimal finalPrice = unitPrice == null ? bd("39.99") : unitPrice;
        return new SalesDtos.LineRequest(sku, BigDecimal.valueOf(qty), finalPrice,
                discount.isBlank() ? null : bd(discount), bd(tax));
    }

    private void confirm(SalesDtos.Response so) {
        env.app.salesOrderService.confirm(users.staff, so.id());
    }

    private void shipFully(SalesDtos.Response so) {
        var detail = env.app.salesOrderService.get(users.staff, so.id());
        List<SalesDtos.ShipLineRequest> lines = detail.lines().stream()
                .map(l -> new SalesDtos.ShipLineRequest(l.id(), l.qtyReserved()))
                .toList();
        env.app.fulfillmentService.ship(users.staff, so.id(),
                new SalesDtos.ShipRequest(lines, null, null, null));
    }

    private void shipPartially(SalesDtos.Response so, Map<Integer, Integer> qtyByLine) {
        var detail = env.app.salesOrderService.get(users.staff, so.id());
        List<SalesDtos.ShipLineRequest> lines = new ArrayList<>();
        for (int i = 0; i < detail.lines().size(); i++) {
            Integer qty = qtyByLine.get(i);
            if (qty != null) {
                lines.add(new SalesDtos.ShipLineRequest(detail.lines().get(i).id(),
                        BigDecimal.valueOf(qty)));
            }
        }
        env.app.fulfillmentService.ship(users.staff, so.id(),
                new SalesDtos.ShipRequest(lines, null, null, null));
    }

    private void adjust(String sku, String warehouse, int delta, String reason,
                        String note) {
        env.app.stockQueryService.adjust(users.manager,
                new StockDtos.AdjustRequest(sku, warehouse, BigDecimal.valueOf(delta),
                        reason, note));
    }
}
