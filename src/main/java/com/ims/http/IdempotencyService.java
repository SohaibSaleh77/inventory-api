package com.ims.http;

import com.ims.common.exception.ConflictException;
import com.ims.db.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Idempotency-Key support (Stripe-style) for unsafe HTTP methods.
 *
 * <p>Flow: when a client sends {@code Idempotency-Key: <key>} with a
 * POST/PUT/PATCH/DELETE, the first successful response is persisted keyed
 * by (user, key) together with a SHA-256 of the request body. Retries with
 * the same key and the same body replay the stored response instead of
 * re-executing the mutation; the same key with a DIFFERENT body is a client
 * bug and fails with 409.
 *
 * <p>Storage is transactional (MySQL), so the scheme is safe across
 * replicas. Rows expire after the configured TTL and are purged by the
 * housekeeping scheduler.
 */
public final class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Only success responses are stored (2xx incl. 201/204). */
    private static final int STORED_STATUS_MIN = 200;
    private static final int STORED_STATUS_MAX = 299;

    public record StoredResponse(int statusCode, String contentType, byte[] body) {
    }

    private final TransactionManager tm;
    private final Duration ttl;

    public IdempotencyService(TransactionManager tm, Duration ttl) {
        this.tm = tm;
        this.ttl = ttl;
    }

    /** SHA-256 of the request body (stable identity for conflict detection). */
    public String requestHash(String rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((rawBody == null ? "" : rawBody)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Look up a previously stored response for (user, key).
     *
     * @throws ConflictException when the key exists with a different
     *                           request hash (same key, different payload)
     */
    public Optional<StoredResponse> lookup(long userId, String key, String requestHash) {
        return tm.withConnection(conn -> {
            Optional<DbRow> row = select(conn, userId, key);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            DbRow found = row.get();
            if (!found.requestHash().equalsIgnoreCase(requestHash)) {
                throw new ConflictException(
                        "Idempotency-Key was already used with a different "
                                + "request body - use a new key for a new operation");
            }
            if (found.expiresAt().isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new StoredResponse(found.statusCode(),
                    found.contentType(), found.body()));
        });
    }

    /**
     * Persist a successful response. Returns false when the key was already
     * stored by a concurrent request (the caller should re-read and replay).
     */
    public boolean store(long userId, String key, String method, String path,
                         String requestHash, int statusCode, String contentType,
                         byte[] body) {
        if (statusCode < STORED_STATUS_MIN || statusCode > STORED_STATUS_MAX) {
            return true; // nothing to store for non-2xx
        }
        Instant expiresAt = Instant.now().plus(ttl);
        try {
            return tm.inTransaction(conn -> {
                insert(conn, userId, key, method, path, requestHash,
                        statusCode, contentType, body, expiresAt);
                return true;
            });
        } catch (RuntimeException e) {
            // Duplicate key on a concurrent identical request: first writer won.
            if (e instanceof com.ims.common.exception.DuplicateResourceException) {
                log.debug("Idempotency key already stored concurrently: {}", key);
                return false;
            }
            throw e;
        }
    }

    /** Housekeeping: delete expired rows. Returns the number purged. */
    public int purgeExpired() {
        return tm.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM idempotency_keys WHERE expires_at < ?")) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                return ps.executeUpdate();
            }
        });
    }

    private record DbRow(String requestHash, int statusCode, String contentType,
                         byte[] body, Instant expiresAt) {
    }

    private Optional<DbRow> select(Connection conn, long userId, String key)
            throws SQLException {
        String sql = """
                SELECT request_hash, status_code, content_type, response_body,
                       expires_at
                FROM idempotency_keys
                WHERE user_id = ? AND idem_key = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                byte[] body = rs.getBytes("response_body");
                return Optional.of(new DbRow(
                        rs.getString("request_hash"),
                        rs.getInt("status_code"),
                        rs.getString("content_type"),
                        body == null ? new byte[0] : body,
                        rs.getTimestamp("expires_at").toInstant()));
            }
        }
    }

    private void insert(Connection conn, long userId, String key, String method,
                        String path, String requestHash, int statusCode,
                        String contentType, byte[] body, Instant expiresAt)
            throws SQLException {
        String sql = """
                INSERT INTO idempotency_keys
                    (idem_key, user_id, method, path, request_hash,
                     status_code, content_type, response_body, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setLong(2, userId);
            ps.setString(3, method);
            ps.setString(4, path);
            ps.setString(5, requestHash);
            ps.setInt(6, statusCode);
            ps.setString(7, contentType);
            ps.setBytes(8, body == null || body.length == 0 ? null : body);
            ps.setTimestamp(9, Timestamp.from(expiresAt));
            ps.executeUpdate();
        }
    }
}
