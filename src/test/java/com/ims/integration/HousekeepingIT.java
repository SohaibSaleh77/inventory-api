package com.ims.integration;

import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The housekeeping scheduler's job body: expired API tokens and expired
 * idempotency records are purged; live records survive.
 */
class HousekeepingIT {

    static IntegrationTestEnv env;
    static TestUsers users;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void runOncePurgesExpiredTokensAndIdempotencyRecords() throws Exception {
        // a live token (must survive) and an expired one (must be purged)
        var live = env.app.tokenService.issue(users.adminId);
        var expired = env.app.tokenService.issue(users.adminId);
        expireToken(expired.token());

        // an expired idempotency record (created directly with a past expiry)
        insertExpiredIdempotency(users.adminId);

        assertTrue(env.app.tokenService.resolve(live.token()).isPresent());
        assertTrue(env.app.tokenService.resolve(expired.token()).isEmpty(),
                "already-expired token must not resolve");

        env.app.housekeeping.runOnce();

        assertEquals(0, countTokensByHash(sha256Hex(expired.token())),
                "expired token rows are purged by housekeeping");
        assertEquals(1, countTokensByHash(sha256Hex(live.token())),
                "live token rows must survive housekeeping");
        assertEquals(0, countExpiredIdempotencyRows(),
                "expired idempotency rows are purged by housekeeping");

        // the live token still authenticates
        assertTrue(env.app.authService.authenticate(live.token()).isPresent());
    }

    // ---- helpers ----

    private static void expireToken(String rawToken) throws Exception {
        try (Connection c = env.connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE api_tokens SET expires_at = ? WHERE token_hash = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(3600)));
            ps.setString(2, sha256Hex(rawToken));
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static int countTokensByHash(String hash) throws Exception {
        try (Connection c = env.connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM api_tokens WHERE token_hash = ?")) {
            ps.setString(1, hash);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void insertExpiredIdempotency(long userId) throws Exception {
        try (Connection c = env.connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO idempotency_keys
                         (idem_key, user_id, method, path, request_hash,
                          status_code, content_type, response_body, expires_at)
                     VALUES (?, ?, 'POST', '/api/test', ?, 201, 'application/json',
                             '{"id":1}', ?)
                     """)) {
            ps.setString(1, "expired-key-001");
            ps.setLong(2, userId);
            ps.setString(3, "0".repeat(64));
            ps.setTimestamp(4, Timestamp.from(Instant.now().minusSeconds(3600)));
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static int countExpiredIdempotencyRows() throws Exception {
        try (Connection c = env.connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = "
                             + "'expired-key-001'");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
