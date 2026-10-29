package com.ims.e2e;

import com.ims.testing.ApiTestServer;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-hardening behaviours at the HTTP boundary:
 * request ids, security headers, 405 + Allow, 413 body cap, ETag/304,
 * meta endpoint, metrics endpoint.
 */
class HttpHardeningIT {

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
    void everyResponseCarriesSecurityHeaders() {
        ApiTestServer.ApiResponse response = api.get("/api/health", null);
        assertEquals("nosniff", response.header("x-content-type-options"));
        assertEquals("DENY", response.header("x-frame-options"));
        assertEquals("no-referrer", response.header("referrer-policy"));
        assertEquals("no-store", response.header("cache-control"));
    }

    @Test
    void errorResponsesCarryRequestIdAndSecurityHeaders() {
        ApiTestServer.ApiResponse response = api.get("/api/no-such-route", adminToken);
        assertEquals(404, response.status());
        assertNotNull(response.header("x-request-id"), "error responses need a request id");
        assertTrue(response.body().contains("requestId"),
                "problem body should embed the requestId");
        assertEquals("nosniff", response.header("x-content-type-options"));
    }

    @Test
    void requestIdsAreEchoedAndGenerated() {
        ApiTestServer.ApiResponse echoed = api.send("GET", "/api/health", null,
                null, Map.of("X-Request-Id", "trace-abc-123"));
        assertEquals("trace-abc-123", echoed.header("x-request-id"));

        ApiTestServer.ApiResponse generated = api.get("/api/health", null);
        assertNotNull(generated.header("x-request-id"));
        // 16 hex chars
        assertTrue(generated.header("x-request-id").matches("[0-9a-f]{16}"));
    }

    @Test
    void methodNotAllowedReturns405WithAllowHeader() {
        ApiTestServer.ApiResponse response = api.send("DELETE", "/api/health",
                null, null, Map.of("Authorization", "Bearer " + adminToken));
        assertEquals(405, response.status());
        String allow = response.header("allow");
        assertNotNull(allow);
        assertTrue(allow.contains("GET"));
    }

    @Test
    void oversizedBodyIsRejected413() {
        char[] filler = new char[2 * 1024 * 1024]; // 2 MB > 1 MB default cap
        java.util.Arrays.fill(filler, 'x');
        String bigPayload = "{\"sku\":\"" + new String(filler) + "\"}";
        ApiTestServer.ApiResponse response = api.send("POST", "/api/products",
                adminToken, bigPayload, Map.of());
        assertEquals(413, response.status());
        assertTrue(response.body().contains("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void etagFlowReturns304ForUnmodifiedResource() {
        // create a warehouse
        Map<String, Object> body = Map.of("code", "WH-ETAG", "name", "Etag Test");
        ApiTestServer.ApiResponse created = api.post("/api/warehouses", adminToken, body);
        assertEquals(201, created.status());
        String id = created.field("id");

        ApiTestServer.ApiResponse first = api.get("/api/warehouses/" + id, adminToken);
        assertEquals(200, first.status());
        String etag = first.header("etag");
        assertNotNull(etag, "single-resource GETs must be ETagged");

        ApiTestServer.ApiResponse notModified = api.send("GET",
                "/api/warehouses/" + id, adminToken, null,
                Map.of("If-None-Match", etag));
        assertEquals(304, notModified.status());

        // after an update the etag changes and the old one no longer matches
        api.put("/api/warehouses/" + id, adminToken,
                Map.of("name", "Etag Test Renamed"));
        ApiTestServer.ApiResponse afterUpdate = api.send("GET",
                "/api/warehouses/" + id, adminToken, null,
                Map.of("If-None-Match", etag));
        assertEquals(200, afterUpdate.status());
    }

    @Test
    void metaEndpointIsPublicAndDescribesBuild() {
        ApiTestServer.ApiResponse response = api.get("/api/meta", null);
        assertEquals(200, response.status());
        assertEquals("v1", response.field("apiVersion"));
        assertNotNull(response.field("version"));
        assertTrue(response.body().contains("\"features\""));
    }

    @Test
    void healthSplitEndpoints() {
        assertEquals(200, api.get("/api/health/live", null).status());
        ApiTestServer.ApiResponse ready = api.get("/api/health/ready", null);
        assertTrue(ready.status() == 200 || ready.status() == 503);
        assertTrue(ready.body().contains("database"));
    }

    @Test
    void metricsEndpointExposesPrometheusFormat() {
        ApiTestServer.ApiResponse response = api.get("/api/metrics", null);
        assertEquals(200, response.status());
        assertTrue(response.header("content-type").startsWith("text/plain"));
        assertTrue(response.body().contains("http_server_requests_total"));
        assertTrue(response.body().contains("db_pool_connections"));
        assertTrue(response.body().contains("ims_build_info"));
        assertTrue(response.body().contains("ims_rate_limited_total"));
    }

    @Test
    void corsIsDisabledByDefault() {
        ApiTestServer.ApiResponse response = api.send("GET", "/api/health", null,
                null, Map.of("Origin", "https://evil.example"));
        assertEquals(null, response.header("access-control-allow-origin"),
                "no CORS headers unless origins are explicitly configured");
    }
}
