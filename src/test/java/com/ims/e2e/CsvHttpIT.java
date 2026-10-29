package com.ims.e2e;

import com.ims.testing.ApiTestServer;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV export endpoints: content type, disposition, header row and
 * RFC 4180 quoting.
 */
class CsvHttpIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static ApiTestServer api;
    static String adminToken;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        api = env.api();
        adminToken = api.login(users.adminUsername, TestUsers.PASSWORD);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void levelsExportStreamsCsv() {
        Fixtures fixtures = new Fixtures(env.app);
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        fixtures.product(users.admin, fixtures.unique("CSV"), category.code(),
                new BigDecimal("10.00"), new BigDecimal("20.00"),
                new BigDecimal("5"));

        ApiTestServer.ApiResponse response = api.get("/api/stock/levels/export",
                adminToken);
        assertEquals(200, response.status());
        assertTrue(response.header("content-type").startsWith("text/csv"));
        assertEquals("attachment; filename=\"stock-levels.csv\"",
                response.header("content-disposition"));
        String body = response.body();
        assertTrue(body.startsWith("sku,productName,warehouseCode"),
                "header row expected first");
        assertTrue(body.contains("\r\n"), "CRLF line endings");
        assertNotNull(response.header("x-truncated"));
    }

    @Test
    void movementsExportStreamsCsv() {
        ApiTestServer.ApiResponse response = api.get("/api/stock/movements/export",
                adminToken);
        assertEquals(200, response.status());
        assertTrue(response.header("content-type").startsWith("text/csv"));
        assertTrue(response.body().startsWith("movementNo,sku,warehouseCode"));
    }

    @Test
    void csvExportRequiresAuthentication() {
        // stock data requires a token; VIEWER (read-only) is allowed
        ApiTestServer.ApiResponse anonymous = api.get("/api/stock/levels/export", null);
        assertEquals(401, anonymous.status());
        String viewerToken = api.login(users.viewerUsername, TestUsers.PASSWORD);
        ApiTestServer.ApiResponse viewer = api.get("/api/stock/levels/export",
                viewerToken);
        assertEquals(200, viewer.status());
    }

    @Test
    void csvFieldsAreRfc4180Quoted() throws Exception {
        // a product whose name contains a comma must be quoted in the export
        Fixtures fixtures = new Fixtures(env.app);
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        var product = fixtures.product(users.admin, fixtures.unique("CSV"),
                category.code(), new BigDecimal("10.00"), new BigDecimal("20.00"),
                new BigDecimal("5"));
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                product.sku(), 10, new BigDecimal("10.00"));
        // rename through the real API to inject a comma
        var update = new com.ims.domain.product.dto.ProductDtos.UpdateRequest(
                null, "Name, with comma", null, null, null, null, null, null, null,
                null);
        env.app.productService.update(users.admin, product.id(), update);

        ApiTestServer.ApiResponse response = api.get("/api/stock/levels/export",
                adminToken);
        assertTrue(response.body().contains("\"Name, with comma\""),
                "commas must be quoted per RFC 4180");
    }
}
