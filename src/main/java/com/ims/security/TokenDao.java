package com.ims.security;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

/** JDBC DAO for the api_tokens table. */
public final class TokenDao {

    public record TokenRecord(long id, long userId, String tokenHash, Instant issuedAt,
                              Instant expiresAt, Instant revokedAt) {
    }

    public TokenRecord insert(Connection c, long userId, String tokenHash,
                              Instant expiresAt) throws SQLException {
        return insert(c, userId, tokenHash, expiresAt, null, null);
    }

    /** Insert with the login's network context (user agent + client IP). */
    public TokenRecord insert(Connection c, long userId, String tokenHash,
                              Instant expiresAt, String userAgent, String ipAddress)
            throws SQLException {
        String sql = """
                INSERT INTO api_tokens (user_id, token_hash, expires_at, user_agent, ip_address)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, tokenHash);
            SqlUtils.setNullableTimestamp(ps, 3, expiresAt);
            SqlUtils.setNullableString(ps, 4, truncate(userAgent, 255));
            SqlUtils.setNullableString(ps, 5, truncate(ipAddress, 45));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return new TokenRecord(id, userId, tokenHash, Instant.now(),
                            expiresAt, null);
                }
            }
            throw new SQLException("Inserting api_token did not return an id");
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public Optional<TokenRecord> findByHash(Connection c, String tokenHash)
            throws SQLException {
        String sql = """
                SELECT id, user_id, token_hash, issued_at, expires_at, revoked_at
                  FROM api_tokens
                 WHERE token_hash = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean revokeById(Connection c, long tokenId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE api_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")) {
            SqlUtils.setNullableTimestamp(ps, 1, Instant.now());
            ps.setLong(2, tokenId);
            return ps.executeUpdate() == 1;
        }
    }

    public int revokeAllForUser(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE api_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
            SqlUtils.setNullableTimestamp(ps, 1, Instant.now());
            ps.setLong(2, userId);
            return ps.executeUpdate();
        }
    }

    /** Revoke every session of a user except the presented token id. */
    public int revokeAllForUserExcept(Connection c, long userId, long exceptTokenId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE api_tokens SET revoked_at = ? "
                        + "WHERE user_id = ? AND revoked_at IS NULL AND id <> ?")) {
            SqlUtils.setNullableTimestamp(ps, 1, Instant.now());
            ps.setLong(2, userId);
            ps.setLong(3, exceptTokenId);
            return ps.executeUpdate();
        }
    }

    public int purgeExpired(Connection c, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM api_tokens WHERE expires_at < ?")) {
            SqlUtils.setNullableTimestamp(ps, 1, now);
            return ps.executeUpdate();
        }
    }

    private TokenRecord map(ResultSet rs) throws SQLException {
        return new TokenRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("token_hash"),
                SqlUtils.getInstant(rs, "issued_at"),
                SqlUtils.getInstant(rs, "expires_at"),
                SqlUtils.getInstant(rs, "revoked_at"));
    }
}
