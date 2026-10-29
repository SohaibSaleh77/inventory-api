package com.ims.integration;

import com.ims.domain.purchase.PurchaseOrderService;
import com.ims.domain.purchase.dto.PurchaseDtos;
import com.ims.domain.product.dto.ProductDtos;
import com.ims.domain.report.dto.ReportDtos;
import com.ims.domain.supplier.dto.SupplierDtos;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replenishment automation: the reorder proposal report and the
 * draft-PO-per-supplier generator, against a real database.
 */
class ReorderServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fixtures;

    static {
        env = IntegrationTestEnv.get();
        IntegrationTestEnv.touch();
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private void freshIsolatedState() {
        env.truncateAll();
        users = TestUsers.create(env);
        fixtures = new Fixtures(env.app);
    }

    /** Product with reorder point 10 / reorder qty 25 and a supplier catalog row. */
    private void stockProduct(String warehouseCode, String sku, String supplierCode,
                              int qty, BigDecimal catalogCost, boolean preferred) {
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        var product = fixtures.product(users.admin, sku, category.code(),
                new BigDecimal("9.00"), new BigDecimal("19.00"),
                new BigDecimal("10"));
        env.app.productService.update(users.admin, product.id(),
                new ProductDtos.UpdateRequest(null, product.name(), null, null,
                        null, null, null, null, new BigDecimal("25"), null));
        var supplier = env.app.supplierService.search(users.admin, supplierCode,
                null, com.ims.common.util.PageRequest.of(1, 1, null, null))
                .content().get(0);
        env.app.supplierService.upsertItem(users.admin, supplier.id(),
                new SupplierDtos.UpsertItemRequest(product.sku(),
                        product.sku() + "-S", catalogCost, preferred, 7));
        if (qty > 0) {
            fixtures.receiveStock(users.admin, supplierCode, warehouseCode,
                    product.sku(), qty, new BigDecimal("9.00"));
        }
    }

    @Test
    void proposalsAndDraftGeneration() {
        freshIsolatedState();
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        String whCode = warehouse.code();
        var supplierA = fixtures.supplier(users.admin, fixtures.unique("SA"));
        var supplierB = fixtures.supplier(users.admin, fixtures.unique("SB"));

        // LOW-1: 4 on hand (below rp 10), supplied by A at 5.00
        stockProduct(whCode, fixtures.unique("LOW1"), supplierA.code(), 4,
                new BigDecimal("5.00"), true);
        // LOW-2: never stocked, supplied by B at 3.00
        stockProduct(whCode, fixtures.unique("LOW2"), supplierB.code(), 0,
                new BigDecimal("3.00"), true);
        // OK-1: 50 on hand, comfortably above its reorder point
        stockProduct(whCode, fixtures.unique("OK1"), supplierA.code(), 50,
                new BigDecimal("5.00"), true);

        // ---- the report ----
        ReportDtos.ReorderReport report = env.app.reportService
                .reorderProposals(users.admin);
        assertEquals(2, report.rows().size());
        assertEquals(0, report.skippedNoSupplier());
        ReportDtos.ReorderRow row1 = report.rows().stream()
                .filter(r -> r.sku().contains("LOW1")).findFirst().orElseThrow();
        ReportDtos.ReorderRow row2 = report.rows().stream()
                .filter(r -> r.sku().contains("LOW2")).findFirst().orElseThrow();
        // suggested qty honours reorder_qty (25) over the deficit (6)
        assertEquals(0, row1.suggestedQty().compareTo(new BigDecimal("25")));
        assertEquals(0, row1.reorderQty().compareTo(new BigDecimal("25")));
        assertEquals(0, row1.totalAvailable().compareTo(new BigDecimal("4")));
        assertEquals(supplierA.code(), row1.supplierCode());
        assertEquals(new BigDecimal("5.00"), row1.unitCost());
        assertEquals(Integer.valueOf(7), row1.leadTimeDays());
        assertEquals(0, row2.suggestedQty().compareTo(new BigDecimal("25")));
        assertEquals(supplierB.code(), row2.supplierCode());

        // ---- the generator: one draft PO per supplier ----
        PurchaseOrderService.ReorderDrafts drafts = env.app.purchaseOrderService
                .createFromReorder(users.admin, whCode, null);
        assertEquals(2, drafts.purchaseOrders().size());
        assertEquals(2, drafts.totalLines());
        assertEquals(0, drafts.skippedLines());

        PurchaseDtos.Response poA = drafts.purchaseOrders().stream()
                .filter(po -> supplierA.code().equals(po.supplierCode()))
                .findFirst().orElseThrow();
        PurchaseDtos.Response poB = drafts.purchaseOrders().stream()
                .filter(po -> supplierB.code().equals(po.supplierCode()))
                .findFirst().orElseThrow();
        assertEquals("DRAFT", poA.status());
        assertNotNull(poA.notes());
        assertTrue(poA.notes().contains("reorder"));
        assertEquals(whCode, poA.warehouseCode());
        assertEquals(1, poA.lines().size());
        assertEquals(0, poA.lines().get(0).qtyOrdered()
                .compareTo(new BigDecimal("25")));
        assertEquals(new BigDecimal("5.00"), poA.lines().get(0).unitCost());
        assertEquals(1, poB.lines().size());

        // ---- single-supplier mode: only that supplier's proposals ----
        PurchaseOrderService.ReorderDrafts single = env.app.purchaseOrderService
                .createFromReorder(users.admin, whCode, supplierA.code());
        assertEquals(1, single.purchaseOrders().size());
        assertEquals(supplierA.code(),
                single.purchaseOrders().get(0).supplierCode());

        // proposals are pure reads: generating drafts does not consume them
        assertEquals(2, env.app.reportService
                .reorderProposals(users.admin).rows().size());
    }

    @Test
    void proposalsWithoutSupplierAreCountedNotOrdered() {
        freshIsolatedState();
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        String whCode = warehouse.code();
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        // low on stock but no supplier catalog entry anywhere
        var product = fixtures.product(users.admin, fixtures.unique("NOSUP"),
                category.code(), new BigDecimal("2.00"), new BigDecimal("4.00"),
                new BigDecimal("10"));
        env.app.productService.update(users.admin, product.id(),
                new ProductDtos.UpdateRequest(null, product.name(), null, null,
                        null, null, null, null, new BigDecimal("12"), null));

        ReportDtos.ReorderReport report = env.app.reportService
                .reorderProposals(users.admin);
        assertTrue(report.rows().stream().noneMatch(r -> r.sku().contains("NOSUP")),
                "rows without a supplier are counted, not proposed");
        assertEquals(1, report.skippedNoSupplier());

        PurchaseOrderService.ReorderDrafts drafts = env.app.purchaseOrderService
                .createFromReorder(users.admin, whCode, null);
        assertEquals(0, drafts.purchaseOrders().size());
        assertEquals(1, drafts.skippedLines());
    }
}
