package com.ims.security;

import com.ims.common.util.Dates;
import com.ims.db.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Opaque bearer tokens: 256 bits of entropy from SecureRandom, presented in
 * clear only once at issue time; the database stores the SHA-256 hash. This
 * avoids JWT revocation problems entirely (a signed-out token is instantly
 * dead) and keeps the scheme trivially auditable.
 */
public final class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransactionManager tm;
    private final TokenDao tokenDao;
    private final Duration ttl;

    public TokenService(TransactionManager tm, TokenDao tokenDao, Duration ttl) {
        this.tm = tm;
        this.tokenDao = tokenDao;
        this.ttl = ttl;
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    /** Issue a new token for the user; returns the clear token exactly once. */
    public IssuedToken issue(long userId) {
        return issue(userId, null, null);
    }

    /** Issue with the login network context (stored for session forensics). */
    public IssuedToken issue(long userId, String userAgent, String ipAddress) {
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String token = HexFormat.of().formatHex(entropy);
        String hash = sha256(token);
        Instant expiresAt = Dates.now().plus(ttl);
        tm.inTransaction(conn -> tokenDao.insert(conn, userId, hash, expiresAt,
                userAgent, ipAddress));
        log.debug("Issued token for user {}", userId);
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Resolve the clear token into its DB record. Empty when unknown, expired
     * or revoked.
     */
    public Optional<TokenDao.TokenRecord> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256(token);
        return tm.withConnection(conn -> {
            Optional<TokenDao.TokenRecord> record = tokenDao.findByHash(conn, hash);
            if (record.isEmpty()) {
                return Optional.<TokenDao.TokenRecord>empty();
            }
            TokenDao.TokenRecord r = record.get();
            boolean expired = r.expiresAt().isBefore(Dates.now());
            boolean revoked = r.revokedAt() != null;
            if (expired || revoked) {
                return Optional.<TokenDao.TokenRecord>empty();
            }
            return Optional.of(r);
        });
    }

    /** Revoke one token (logout). Returns true when a live token was revoked. */
    public boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = sha256(token);
        return tm.inTransaction(conn -> {
            Optional<TokenDao.TokenRecord> record = tokenDao.findByHash(conn, hash);
            if (record.isEmpty() || record.get().revokedAt() != null) {
                return false;
            }
            return tokenDao.revokeById(conn, record.get().id());
        });
    }

    /** Revoke every live token of a user (used when disabling an account). */
    public int revokeAllForUser(long userId) {
        return tm.inTransaction(conn -> tokenDao.revokeAllForUser(conn, userId));
    }

    /** Housekeeping: delete expired token rows. Returns rows purged. */
    public int purgeExpired() {
        return tm.inTransaction(conn -> tokenDao.purgeExpired(conn, Dates.now()));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
