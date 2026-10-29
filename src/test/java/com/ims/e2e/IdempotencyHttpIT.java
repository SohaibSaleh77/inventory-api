package com.ims.e2e;

import com.ims.testing.ApiTestServer;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idempotency-Key semantics at the HTTP boundary:
 * replay of identical retries, 409 on key reuse with a different payload.
 */
class IdempotencyHttpIT {

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
    void identicalRetryReplaysStoredResponseWithoutReExecuting() {
        Map<String, Object> body = Map.of("code", "WH-IDEM", "name", "Idempotent WH");
        Map<String, String> headers = Map.of("Idempotency-Key", "it-key-001");

        ApiTestServer.ApiResponse first = api.send("POST", "/api/warehouses",
                adminToken, body, headers);
        assertEquals(201, first.status());
        String firstId = first.field("id");
        assertNotNull(firstId);

        // client retry (e.g. connection dropped after server committed)
        ApiTestServer.ApiResponse retry = api.send("POST", "/api/warehouses",
                adminToken, body, headers);
        assertEquals(201, retry.status());
        assertEquals("true", retry.header("idempotency-replayed"));
        assertEquals(firstId, retry.field("id"), "replay must return the SAME resource");

        // only ONE warehouse exists - the retry did not create a duplicate
        ApiTestServer.ApiResponse list = api.get("/api/warehouses", adminToken);
        assertTrue(list.body().contains("WH-IDEM"));
        assertEquals(1, list.json().size(), "no duplicate rows may be created");
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected409() {
        Map<String, String> headers = Map.of("Idempotency-Key", "it-key-002");
        api.send("POST", "/api/warehouses", adminToken,
                Map.of("code", "WH-A", "name", "A"), headers);

        ApiTestServer.ApiResponse conflicting = api.send("POST", "/api/warehouses",
                adminToken, Map.of("code", "WH-B", "name", "B"), headers);
        assertEquals(409, conflicting.status());
        assertTrue(conflicting.body().contains("different")
                || conflicting.body().toLowerCase().contains("idempotency"));
        // the conflicting warehouse must NOT have been created
        ApiTestServer.ApiResponse list = api.get("/api/warehouses", adminToken);
        assertTrue(!list.body().contains("WH-B"));
    }

    @Test
    void differentKeysExecuteIndependently() {
        ApiTestServer.ApiResponse one = api.send("POST", "/api/warehouses",
                adminToken, Map.of("code", "WH-C", "name", "C"),
                Map.of("Idempotency-Key", "it-key-003"));
        ApiTestServer.ApiResponse two = api.send("POST", "/api/warehouses",
                adminToken, Map.of("code", "WH-D", "name", "D"),
                Map.of("Idempotency-Key", "it-key-004"));
        assertEquals(201, one.status());
        assertEquals(201, two.status());
        assertNotEquals(one.field("id"), two.field("id"));
        // no replay header on first execution
        assertEquals(null, one.header("idempotency-replayed"));
        assertEquals(null, two.header("idempotency-replayed"));
    }

    @Test
    void unauthenticatedRequestsIgnoreIdempotency() {
        // public endpoints (login) never enter the idempotency path
        ApiTestServer.ApiResponse response = api.send("POST", "/api/auth/login",
                null, Map.of("username", "nobody", "password", "wrong"),
                Map.of("Idempotency-Key", "it-key-005"));
        assertEquals(401, response.status());
    }
}
