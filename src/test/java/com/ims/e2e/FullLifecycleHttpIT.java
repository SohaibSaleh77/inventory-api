package com.ims.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.ims.testing.ApiTestServer;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The complete business lifecycle executed against a REAL running HTTP
 * server with REAL requests - exactly what an API client experiences.
 */
class FullLifecycleHttpIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static ApiTestServer api;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        api = env.api();
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void healthIsPublic() {
        var response = api.get("/api/health", null);
        assertEquals(200, response.status());
        assertEquals("UP", response.json().get("status").asText());
    }

    @Test
    void masterDataCrudOverHttp() {
        // login needs real usernames - create a dedicated http-admin via the
        // service (which hashes passwords), then log in over real HTTP.
        var adminUser = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "httpadmin", "httpadmin@it.example", "Initial!123",
                        "HTTP Admin", "ADMIN"));
        String token = api.login("httpadmin", "Initial!123");

        // warehouses
        var wh = api.post("/api/warehouses", token, Map.of(
                "code", "E-WH", "name", "E2E Warehouse", "city", "Riyadh"));
        assertEquals(201, wh.status());
        long warehouseId = wh.json().get("id").asLong();

        // category + supplier + customer + product
        assertEquals(201, api.post("/api/categories", token, Map.of(
                "code", "E-CAT", "name", "E2E Category")).status());
        assertEquals(201, api.post("/api/suppliers", token, Map.of(
                "code", "E-SUP", "name", "E2E Supplier", "email", "e@sup.example",
                "country", "Saudi Arabia")).status());
        assertEquals(201, api.post("/api/customers", token, Map.of(
                "code", "E-CUS", "name", "E2E Customer", "customerType", "WHOLESALE",
                "creditLimit", 250000)).status());
        var product = api.post("/api/products", token, Map.of(
                "sku", "E-SKU", "name", "E2E Product", "categoryCode", "E-CAT",
                "standardCost", 100.0, "listPrice", 180.0, "reorderPoint", 20));
        assertEquals(201, product.status(), "product create: " + product.body());
        long productId = product.json().get("id").asLong();

        // duplicate SKU rejected
        var duplicate = api.post("/api/products", token, Map.of(
                "sku", "E-SKU", "name", "Duplicate", "standardCost", 1, "listPrice", 2));
        assertEquals(422, duplicate.status());
        assertTrue(duplicate.json().get("fieldErrors").has("sku"));

        // search + pagination headers
        var search = api.get("/api/products?search=E2E&page=1&size=10", token);
        assertEquals(200, search.status());
        assertEquals(1, search.json().get("totalElements").asInt());

        // ---- procurement over HTTP ----
        var po = api.post("/api/purchase-orders", token, Map.of(
                "supplierCode", "E-SUP", "warehouseCode", "E-WH",
                "lines", List.of(Map.of("productSku", "E-SKU", "qty", 50,
                        "unitCost", 102.50, "taxPct", 15))));
        assertEquals(201, po.status());
        long poId = po.json().get("id").asLong();
        assertEquals(5893.75, po.json().get("total").asDouble(), 0.001);

        assertEquals(200, api.post("/api/purchase-orders/" + poId + "/submit",
                token, Map.of()).status());
        assertEquals(200, api.post("/api/purchase-orders/" + poId + "/approve",
                token, Map.of()).status());

        long lineId = api.get("/api/purchase-orders/" + poId, token)
                .json().get("lines").get(0).get("id").asLong();
        var receipt = api.post("/api/purchase-orders/" + poId + "/receipts", token,
                Map.of("lines", List.of(Map.of("poLineId", lineId, "qtyAccepted", 50,
                        "qtyRejected", 0)), "note", "full delivery"));
        assertEquals(201, receipt.status());
        assertEquals("RECEIVED", api.get("/api/purchase-orders/" + poId, token)
                .json().get("status").asText());

        // stock reflects the receipt
        var level = api.get("/api/stock/levels?sku=E-SKU", token);
        assertEquals(50.0, level.json().get("content").get(0).get("qtyOnHand")
                .asDouble(), 0.001);

        // ---- sales over HTTP ----
        var so = api.post("/api/sales-orders", token, Map.of(
                "customerCode", "E-CUS", "warehouseCode", "E-WH",
                "lines", List.of(Map.of("productSku", "E-SKU", "qty", 20,
                        "unitPrice", 179.00, "discountPct", 10, "taxPct", 15))));
        assertEquals(201, so.status());
        long soId = so.json().get("id").asLong();
        // 20*179=3580 -10% = 3222 +15% = 3705.30
        assertEquals(3705.30, so.json().get("total").asDouble(), 0.001);

        assertEquals(200, api.post("/api/sales-orders/" + soId + "/confirm",
                token, Map.of()).status());
        assertEquals(20.0, api.get("/api/stock/levels?sku=E-SKU", token)
                .json().get("content").get(0).get("qtyReserved").asDouble(), 0.001);

        long soLine = api.get("/api/sales-orders/" + soId, token)
                .json().get("lines").get(0).get("id").asLong();
        var shipment = api.post("/api/sales-orders/" + soId + "/shipments", token,
                Map.of("lines", List.of(Map.of("soLineId", soLine, "qty", 20)),
                        "carrier", "Aramex", "trackingNo", "E2E-001"));
        assertEquals(201, shipment.status());
        assertEquals("FULFILLED", api.get("/api/sales-orders/" + soId, token)
                .json().get("status").asText());
        assertEquals(30.0, api.get("/api/stock/levels?sku=E-SKU", token)
                .json().get("content").get(0).get("qtyOnHand").asDouble(), 0.001);

        // ---- return over HTTP ----
        var ret = api.post("/api/sales-orders/" + soId + "/returns", token,
                Map.of("lines", List.of(Map.of("soLineId", soLine, "qty", 3,
                        "restock", true)), "reason", "DEFECTIVE"));
        assertEquals(201, ret.status());
        assertEquals(33.0, api.get("/api/stock/levels?sku=E-SKU", token)
                .json().get("content").get(0).get("qtyOnHand").asDouble(), 0.001);

        // ---- reports over HTTP ----
        var valuation = api.get("/api/reports/stock-valuation", token);
        assertEquals(200, valuation.status());
        assertTrue(valuation.json().get("grandTotal").asDouble() > 0);
        var margin = api.get("/api/reports/gross-margin", token);
        assertEquals(200, margin.status());
        assertTrue(margin.json().get("totalRevenue").asDouble() > 0);

        // ---- movement ledger over HTTP ----
        var movements = api.get("/api/stock/movements?productId=" + productId, token);
        assertEquals(200, movements.status());
        assertTrue(movements.json().get("totalElements").asInt() >= 3,
                "receipt + shipment + return movements expected");

        // ---- audit over HTTP ----
        var audit = api.get("/api/audit/logs?size=200", token);
        assertEquals(200, audit.status());
        assertTrue(audit.json().get("totalElements").asInt() >= 10,
                "every mutation must be audited");
    }

    @Test
    void errorShapesAreUniform() {
        var adminUser = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "errchecker" + System.nanoTime(), "err@it.example",
                        "Initial!123", "Err Checker", "ADMIN"));
        String token = api.login(adminUser.username(), "Initial!123");

        var notFound = api.get("/api/nonexistent", token);
        assertEquals(404, notFound.status());
        assertEquals("NOT_FOUND", notFound.json().get("title").asText());

        var noToken = api.get("/api/products", null);
        assertEquals(401, noToken.status());
        assertEquals("UNAUTHENTICATED", noToken.json().get("title").asText());

        var badToken = api.get("/api/products", "garbage-token");
        assertEquals(401, badToken.status());

        var malformed = api.post("/api/warehouses", token, "{not json");
        assertEquals(400, malformed.status());

        var unknown = api.get("/api/products/99999999", token);
        assertEquals(404, unknown.status());
    }

    @Test
    void logoutInvalidatesTokenOverHttp() {
        var user = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "logoutuser" + System.nanoTime(), "lo@it.example",
                        "Initial!123", "Logout User", "STAFF"));
        String token = api.login(user.username(), "Initial!123");
        assertEquals(200, api.get("/api/auth/me", token).status());

        assertEquals(204, api.post("/api/auth/logout", token, Map.of()).status());
        assertEquals(401, api.get("/api/auth/me", token).status(),
                "logged-out token must be dead");
    }
}
