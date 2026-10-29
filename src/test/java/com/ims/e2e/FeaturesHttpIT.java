package com.ims.e2e;

import com.ims.testing.ApiTestServer;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting conveniences over HTTP: barcode lookup, CSV product
 * import, self-service password change, and the reorder-to-PO shortcut.
 */
class FeaturesHttpIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static ApiTestServer api;
    static Fixtures fixtures;
    static String adminToken;
    static String staffToken;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        api = env.api();
        fixtures = new Fixtures(env.app);
        adminToken = api.login(users.adminUsername, TestUsers.PASSWORD);
        staffToken = api.login(users.staffUsername, TestUsers.PASSWORD);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void barcodeLookupFindsProducts() {
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        String sku = fixtures.unique("BAR");
        var product = fixtures.product(users.admin, sku, category.code(),
                new BigDecimal("3.50"), new BigDecimal("7.00"), BigDecimal.ZERO);
        // assign a barcode (name is required by the partial-update contract)
        var update = api.put("/api/products/" + product.id(), adminToken,
                Map.of("name", product.name(),
                        "barcode", "50" + System.nanoTime() % 100000000L));
        assertEquals(200, update.status());
        String barcode = update.field("barcode");

        // scanner-style lookup
        var found = api.get("/api/products/barcode/" + barcode, staffToken);
        assertEquals(200, found.status());
        assertEquals(sku, found.field("sku"));
        assertTrue(found.header("etag") != null);

        // unknown barcode -> 404
        assertEquals(404, api.get("/api/products/barcode/0000000000",
                staffToken).status());
    }

    @Test
    void csvImportCreatesProductsAndReportsErrors() {
        String skuA = fixtures.unique("IMA");
        String skuB = fixtures.unique("IMB");
        String csv = "sku,name,barcode,categoryCode,unitOfMeasure,standardCost,listPrice,reorderPoint,reorderQty\r\n"
                + skuA + ",Imported A,9001,,EA,4.50,9.00,10,25\r\n"
                + skuB + ",\"Imported, B\",,,EA,1.00,2.00,,\r\n"
                + ",Missing SKU,,,EA,1,1,,\r\n"
                + skuA + ",Duplicate SKU,,,EA,1,1,,\r\n"
                + "BAD-NUM,Bad number,,,XX,notanumber,1,,\r\n";

        var response = api.post("/api/products/import", adminToken, csv);
        assertEquals(200, response.status());
        var json = response.json();
        assertEquals(2, json.get("created").asInt());
        assertEquals(3, json.get("skipped").asInt());
        assertEquals(3, json.get("errors").size());
        // per-row errors carry the line number and reason
        assertEquals(4, json.get("errors").get(0).get("line").asInt());
        assertTrue(json.get("errors").get(0).get("message").asText()
                .contains("sku"));

        // created products are real: lookup by sku
        var found = api.get("/api/products/sku/" + skuA, staffToken);
        assertEquals(200, found.status());
        assertEquals("9001", found.field("barcode"));
        assertEquals(10, found.json().get("reorderPoint").asInt());
        assertEquals(25, found.json().get("reorderQty").asInt());
        // quoted field with comma survived
        var foundB = api.get("/api/products/sku/" + skuB, staffToken);
        assertEquals(200, foundB.status());
        assertEquals("Imported, B", foundB.field("name"));

        // header validation
        assertEquals(422, api.post("/api/products/import", adminToken,
                "wrong,header\r\n1,2\r\n").status());
        // viewer cannot import
        assertEquals(403, api.post("/api/products/import",
                api.login(users.viewerUsername, TestUsers.PASSWORD),
                "sku,name\r\nX,Y\r\n").status());
    }

    @Test
    void changePasswordRotatesOtherSessions() {
        // a dedicated user with two sessions
        var user = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "pwuser", "pwuser@it.example", "First!1234",
                        "PW User", "STAFF"));
        String tokenA = api.login("pwuser", "First!1234");
        String tokenB = api.login("pwuser", "First!1234");

        // wrong current password
        var wrong = api.post("/api/auth/change-password", tokenA, Map.of(
                "currentPassword", "Wrong!9999", "newPassword", "Second!5678"));
        assertEquals(422, wrong.status());
        // weak new password
        var weak = api.post("/api/auth/change-password", tokenA, Map.of(
                "currentPassword", "First!1234", "newPassword", "short"));
        assertEquals(422, weak.status());

        // correct change
        var change = api.post("/api/auth/change-password", tokenB, Map.of(
                "currentPassword", "First!1234", "newPassword", "Second!5678"));
        assertEquals(204, change.status());

        // the calling session stays valid...
        assertEquals(200, api.get("/api/auth/me", tokenB).status());
        // ...the other session was revoked
        assertEquals(401, api.get("/api/auth/me", tokenA).status());
        // old password no longer logs in
        assertEquals(401, api.post("/api/auth/login", null,
                Map.of("username", "pwuser", "password", "First!1234")).status());
        // the new one does
        String tokenC = api.login("pwuser", "Second!5678");
        assertEquals(200, api.get("/api/auth/me", tokenC).status());

        // audit trail records the change
        var audit = api.get("/api/audit/logs?action=PASSWORD_CHANGED", adminToken);
        assertEquals(200, audit.status());
        assertTrue(audit.json().get("content").size() >= 1);
    }

    @Test
    void reorderShortcutCreatesDraftPurchaseOrders() {
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        // low stock: 2 on hand, reorder point 10, reorder qty 20, catalog 4.00
        var product = fixtures.product(users.admin, fixtures.unique("RDR"),
                category.code(), new BigDecimal("9.00"), new BigDecimal("18.00"),
                new BigDecimal("10"));
        env.app.productService.update(users.admin, product.id(),
                new com.ims.domain.product.dto.ProductDtos.UpdateRequest(
                        null, product.name(), null, null, null, null, null, null,
                        new BigDecimal("20"), null));
        env.app.supplierService.upsertItem(users.admin,
                env.app.supplierService.search(users.admin, supplier.code(), null,
                        com.ims.common.util.PageRequest.of(1, 1, null, null))
                        .content().get(0).id(),
                new com.ims.domain.supplier.dto.SupplierDtos.UpsertItemRequest(
                        product.sku(), product.sku() + "-S",
                        new BigDecimal("4.00"), true, 5));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                product.sku(), 2, new BigDecimal("9.00"));

        // the report
        var proposals = api.get("/api/reports/reorder-proposals", staffToken);
        assertEquals(200, proposals.status());
        boolean found = false;
        for (var row : proposals.json().get("rows")) {
            if (product.sku().equals(row.get("sku").asText())) {
                found = true;
                assertEquals(20, row.get("suggestedQty").asInt());
                assertEquals(supplier.code(), row.get("supplierCode").asText());
            }
        }
        assertTrue(found, "our product should appear in the proposals");

        // the generator
        var drafts = api.post("/api/purchase-orders/from-reorder", staffToken,
                Map.of("warehouseCode", warehouse.code()));
        assertEquals(200, drafts.status());
        assertEquals(1, drafts.json().get("purchaseOrders").size());
        var po = drafts.json().get("purchaseOrders").get(0);
        assertEquals("DRAFT", po.get("status").asText());
        assertEquals(supplier.code(), po.get("supplierCode").asText());
        assertEquals(20, po.get("lines").get(0).get("qtyOrdered").asInt());
        assertEquals(4.00, po.get("lines").get(0).get("unitCost").asDouble());

        // empty body works too (warehouse via query string is NOT supported;
        // the endpoint requires a JSON body with warehouseCode)
        assertEquals(422, api.post("/api/purchase-orders/from-reorder",
                staffToken, Map.of()).status());
    }
}
