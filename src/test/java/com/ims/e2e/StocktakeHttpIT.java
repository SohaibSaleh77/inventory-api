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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stocktake lifecycle end-to-end over the real HTTP API, including the
 * CSV count-sheet export.
 */
class StocktakeHttpIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static ApiTestServer api;
    static Fixtures fixtures;
    static String adminToken;
    static String staffToken;
    static String viewerToken;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        api = env.api();
        fixtures = new Fixtures(env.app);
        adminToken = api.login(users.adminUsername, TestUsers.PASSWORD);
        staffToken = api.login(users.staffUsername, TestUsers.PASSWORD);
        viewerToken = api.login(users.viewerUsername, TestUsers.PASSWORD);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void lifecycleOverHttp() {
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        var product = fixtures.product(users.admin, fixtures.unique("SKU"),
                category.code(), new BigDecimal("10.00"), new BigDecimal("20.00"),
                BigDecimal.ZERO);
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                product.sku(), 100, new BigDecimal("10.00"));

        // create
        var create = api.post("/api/stocktakes", adminToken, Map.of(
                "warehouseCode", warehouse.code(),
                "name", "HTTP cycle",
                "blind", false,
                "note", "e2e"));
        assertEquals(201, create.status());
        assertEquals("DRAFT", create.field("status"));
        assertEquals(1, create.json().get("lineCount").asInt());
        long id = create.json().get("id").asLong();

        // open
        assertEquals(200, api.post("/api/stocktakes/" + id + "/open", adminToken,
                Map.of()).status());

        // count 96 (variance -4)
        var count = api.post("/api/stocktakes/" + id + "/counts", staffToken,
                Map.of("productSku", product.sku(), "countedQty", 96));
        assertEquals(200, count.status());
        assertEquals(96, count.json().get("countedQty").asInt());
        assertEquals(-4, count.json().get("variance").asInt());

        // close + post
        assertEquals(200, api.post("/api/stocktakes/" + id + "/close", adminToken,
                Map.of()).status());
        var post = api.post("/api/stocktakes/" + id + "/post", adminToken,
                Map.of());
        assertEquals(200, post.status());
        assertEquals(1, post.json().get("correctedLines").asInt());
        assertEquals(0, post.json().get("uncountedLines").asInt());

        // stock level reflects the correction
        var levels = api.get("/api/stock/levels?warehouseId=" + warehouse.id(),
                viewerToken);
        assertEquals(200, levels.status());
        var row = levels.json().get("content").get(0);
        assertEquals(96, row.get("qtyOnHand").asInt());

        // detail is immutable afterwards
        var detail = api.get("/api/stocktakes/" + id, viewerToken);
        assertEquals(200, detail.status());
        assertEquals("POSTED", detail.field("status"));
        assertNotNull(detail.json().get("postedAt"));

        // movements reference the stocktake
        var movements = api.get("/api/stock/movements?refType=STOCKTAKE&refId="
                + id, viewerToken);
        assertEquals(200, movements.status());
        assertEquals(1, movements.json().get("totalElements").asInt());

        // 405 with Allow on a read-only path
        var bad = api.send("DELETE", "/api/stocktakes/" + id, adminToken, null);
        assertEquals(405, bad.status());
        assertEquals("GET", bad.header("allow"));

        // CSV export of the count sheet
        var sheet = api.get("/api/stocktakes/" + id + "/export", viewerToken);
        assertEquals(200, sheet.status());
        assertTrue(sheet.header("content-type").startsWith("text/csv"));
        assertTrue(sheet.body().startsWith("sku,productName,expectedQty"));
        assertTrue(sheet.body().contains(product.sku()));
        assertTrue(sheet.body().contains("96"));

        // blind stocktake over HTTP hides expected quantities while OPEN
        var blindCreate = api.post("/api/stocktakes", adminToken, Map.of(
                "warehouseCode", warehouse.code(), "name", "Blind", "blind", true));
        assertEquals(201, blindCreate.status());
        long blindId = blindCreate.json().get("id").asLong();
        api.post("/api/stocktakes/" + blindId + "/open", adminToken, Map.of());
        var blindDetail = api.get("/api/stocktakes/" + blindId, staffToken);
        assertEquals(200, blindDetail.status());
        assertTrue(blindDetail.json().get("lines").get(0).get("expectedQty")
                .isNull(), "blind count sheet must hide the book quantity");
        assertTrue(blindDetail.json().get("lines").get(0).get("variance").isNull());
        // and cancel it from OPEN
        assertEquals(200, api.post("/api/stocktakes/" + blindId + "/cancel",
                adminToken, Map.of()).status());
        var cancelled = api.get("/api/stocktakes/" + blindId, adminToken);
        assertEquals("CANCELLED", cancelled.field("status"));
    }

    @Test
    void dashboardReflectsBusinessState() {
        // own data so this test does not depend on test order
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        var product = fixtures.product(users.admin, fixtures.unique("DSH"),
                category.code(), new BigDecimal("6.00"), new BigDecimal("12.00"),
                BigDecimal.ZERO);
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                product.sku(), 10, new BigDecimal("6.00"));

        var dashboard = api.get("/api/dashboard", viewerToken);
        assertEquals(200, dashboard.status());
        var json = dashboard.json();
        assertTrue(json.get("activeProducts").asLong() >= 1);
        assertTrue(json.get("activeWarehouses").asLong() >= 1);
        assertTrue(json.get("activeSuppliers").asLong() >= 1);
        assertTrue(json.get("totalCustomers").asLong() >= 0);
        assertTrue(json.get("stockValue").decimalValue().signum() > 0);
        assertTrue(json.get("recentMovements").size() >= 1);
        assertTrue(json.get("recentMovements").get(0).has("sku"));
        assertTrue(json.get("recentMovements").get(0).has("warehouseCode"));
        assertTrue(json.get("recentMovements").get(0).has("movementType"));
        assertTrue(json.has("lowStockLines"));
        assertTrue(json.has("inTransitTransfers"));
        assertTrue(json.has("pendingWebhookDeliveries"));
        // anonymous access is rejected
        assertEquals(401, api.get("/api/dashboard", null).status());
    }
}
