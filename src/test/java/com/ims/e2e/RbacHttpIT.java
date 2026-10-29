package com.ims.e2e;

import com.ims.testing.ApiTestServer;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RBAC enforcement at the HTTP boundary: representative endpoints hit with
 * tokens of every role; 403 must appear exactly where the matrix denies.
 */
class RbacHttpIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static ApiTestServer api;

    static String adminToken;
    static String managerToken;
    static String staffToken;
    static String viewerToken;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        api = env.api();

        var adminUser = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "rbac-admin", "ra@it.example", "Initial!123", "RBAC A", "ADMIN"));
        var managerUser = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "rbac-manager", "rm@it.example", "Initial!123", "RBAC M",
                        "MANAGER"));
        var staffUser = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "rbac-staff", "rs@it.example", "Initial!123", "RBAC S",
                        "STAFF"));
        var viewerUser = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "rbac-viewer", "rv@it.example", "Initial!123", "RBAC V",
                        "VIEWER"));
        adminToken = api.login(adminUser.username(), "Initial!123");
        managerToken = api.login(managerUser.username(), "Initial!123");
        staffToken = api.login(staffUser.username(), "Initial!123");
        viewerToken = api.login(viewerUser.username(), "Initial!123");
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private void assertStatus(String method, String path, String token,
                              Object body, int expected) {
        int actual;
        switch (method) {
            case "GET" -> actual = api.get(path, token).status();
            case "POST" -> actual = api.post(path, token, body).status();
            case "PUT" -> actual = api.put(path, token, body).status();
            case "DELETE" -> actual = api.delete(path, token).status();
            default -> throw new IllegalArgumentException(method);
        }
        assertEquals(expected, actual,
                method + " " + path + " as "
                        + (token == null ? "anonymous"
                        : token.substring(0, Math.min(6, token.length()))));
    }

    @Test
    void masterDataWriteMatrix() {
        var warehouse = Map.of("code", "R-WH", "name", "RBAC WH");
        var product = Map.of("sku", "R-SKU", "name", "RBAC Product",
                "standardCost", 1, "listPrice", 2);

        assertStatus("POST", "/api/warehouses", adminToken, warehouse, 201);
        assertStatus("POST", "/api/warehouses", managerToken, warehouse, 422);
        assertStatus("POST", "/api/warehouses", staffToken, warehouse, 403);
        assertStatus("POST", "/api/warehouses", viewerToken, warehouse, 403);
        assertStatus("POST", "/api/warehouses", null, warehouse, 401);

        assertStatus("GET", "/api/warehouses", viewerToken, null, 200);
        assertStatus("GET", "/api/warehouses", staffToken, null, 200);
        assertStatus("GET", "/api/warehouses", null, null, 401);
    }

    @Test
    void userAdminMatrix() {
        var newUser = Map.of("username", "rbac-new", "email", "new@it.example",
                "password", "Initial!123", "fullName", "New", "role", "STAFF");
        assertStatus("POST", "/api/users", adminToken, newUser, 201);
        assertStatus("POST", "/api/users", managerToken, newUser, 403);
        assertStatus("GET", "/api/users", managerToken, null, 200);
        assertStatus("GET", "/api/users", staffToken, null, 403);
        assertStatus("GET", "/api/users", viewerToken, null, 403);
    }

    @Test
    void auditAccessMatrix() {
        assertStatus("GET", "/api/audit/logs", adminToken, null, 200);
        assertStatus("GET", "/api/audit/logs", managerToken, null, 403);
        assertStatus("GET", "/api/audit/logs", staffToken, null, 403);
        assertStatus("GET", "/api/audit/logs", viewerToken, null, 403);
    }

    @Test
    void stockAdjustmentMatrix() {
        // staff lacks STOCK_ADJUST; manager has it
        var adjustment = Map.of("productSku", "R-SKU", "warehouseCode", "R-WH",
                "quantityDelta", 5, "reason", "FOUND");
        assertStatus("POST", "/api/stock/adjustments", staffToken, adjustment, 403);
        assertStatus("POST", "/api/stock/adjustments", viewerToken, adjustment, 403);
        assertStatus("POST", "/api/stock/adjustments", managerToken, adjustment, 422);
        // 422 (unknown SKU) not 403: manager passed RBAC, failed validation
    }

    @Test
    void purchaseApprovalMatrix() {
        // staff/viewer lack PURCHASE_ORDER_APPROVE: 403 before existence check
        assertStatus("POST", "/api/purchase-orders/999999/approve", staffToken,
                Map.of(), 403);
        assertStatus("POST", "/api/purchase-orders/999999/approve", viewerToken,
                Map.of(), 403);
        // manager HAS the permission: gets past RBAC, then 404 (unknown PO)
        assertStatus("POST", "/api/purchase-orders/999999/approve", managerToken,
                Map.of(), 404);
    }

    @Test
    void allRolesCanReadReports() {
        assertStatus("GET", "/api/reports/stock-summary", adminToken, null, 200);
        assertStatus("GET", "/api/reports/stock-summary", managerToken, null, 200);
        assertStatus("GET", "/api/reports/stock-summary", staffToken, null, 200);
        assertStatus("GET", "/api/reports/stock-summary", viewerToken, null, 200);
        // dashboard and reorder proposals are reports too
        assertStatus("GET", "/api/dashboard", adminToken, null, 200);
        assertStatus("GET", "/api/dashboard", managerToken, null, 200);
        assertStatus("GET", "/api/dashboard", staffToken, null, 200);
        assertStatus("GET", "/api/dashboard", viewerToken, null, 200);
        assertStatus("GET", "/api/dashboard", null, null, 401);
        assertStatus("GET", "/api/reports/reorder-proposals", staffToken, null, 200);
        assertStatus("GET", "/api/reports/reorder-proposals", viewerToken, null, 200);
    }

    @Test
    void stocktakeMatrix() {
        // a warehouse of our own so this test does not depend on test order
        assertStatus("POST", "/api/warehouses", adminToken,
                Map.of("code", "R-STK", "name", "RBAC STK"), 201);
        var create = Map.of("warehouseCode", "R-STK", "name", "RBAC count");
        // create/open/count/close need STOCK_WRITE: viewer denied
        assertStatus("POST", "/api/stocktakes", staffToken, create, 201);
        assertStatus("POST", "/api/stocktakes", viewerToken, create, 403);
        assertStatus("POST", "/api/stocktakes", null, create, 401);
        // reading is open to every authenticated role
        assertStatus("GET", "/api/stocktakes", viewerToken, null, 200);
        assertStatus("GET", "/api/stocktakes/999999", viewerToken, null, 404);
        // posting needs STOCK_ADJUST: staff denied before existence check
        assertStatus("POST", "/api/stocktakes/999999/post", staffToken, Map.of(),
                403);
        assertStatus("POST", "/api/stocktakes/999999/post", viewerToken, Map.of(),
                403);
        // manager has STOCK_ADJUST: passes RBAC, fails on unknown stocktake
        assertStatus("POST", "/api/stocktakes/999999/post", managerToken, Map.of(),
                404);
    }

    @Test
    void webhookMatrix() {
        var webhook = Map.of("name", "rbac-hook", "url",
                "http://127.0.0.1:9/hook", "secret", "rbac-secret-0123456789",
                "events", java.util.List.of("TEST"));
        assertStatus("POST", "/api/webhooks", adminToken, webhook, 201);
        assertStatus("POST", "/api/webhooks", managerToken, webhook, 422);
        // 422 (duplicate name) not 403: manager passed RBAC
        assertStatus("POST", "/api/webhooks", staffToken, webhook, 403);
        assertStatus("POST", "/api/webhooks", viewerToken, webhook, 403);
        assertStatus("POST", "/api/webhooks", null, webhook, 401);
        assertStatus("GET", "/api/webhooks", adminToken, null, 200);
        assertStatus("GET", "/api/webhooks", managerToken, null, 200);
        assertStatus("GET", "/api/webhooks", staffToken, null, 403);
        assertStatus("GET", "/api/webhooks", viewerToken, null, 403);
        assertStatus("POST", "/api/webhooks/999999/test", managerToken, Map.of(),
                404);
        assertStatus("POST", "/api/webhooks/999999/test", staffToken, Map.of(),
                403);
    }

    @Test
    void productImportMatrix() {
        assertStatus("POST", "/api/products/import", staffToken,
                "sku,name\r\nX1,Import\r\n", 200);
        assertStatus("POST", "/api/products/import", viewerToken,
                "sku,name\r\nX2,Import\r\n", 403);
        assertStatus("POST", "/api/products/import", null,
                "sku,name\r\nX3,Import\r\n", 401);
    }
}
