package com.ims.domain.auth;

import com.ims.common.exception.UnauthenticatedException;
import com.ims.common.util.Dates;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.auth.dto.AuthDtos.LoginRequest;
import com.ims.domain.auth.dto.AuthDtos.LoginResponse;
import com.ims.domain.auth.dto.AuthDtos.MeResponse;
import com.ims.domain.user.User;
import com.ims.domain.user.UserDao;
import com.ims.security.AuthContext;
import com.ims.security.PasswordHasher;
import com.ims.security.Permission;
import com.ims.security.RolePermissions;
import com.ims.security.TokenDao;
import com.ims.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Login / logout / current-principal. Implements brute-force protection:
 * after N failed attempts the account is locked for a configurable period.
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final TransactionManager tm;
    private final UserDao userDao;
    private final TokenDao tokenDao;
    private final TokenService tokenService;
    private final PasswordHasher passwordHasher;
    private final AuditLogger audit;
    private final int maxFailedAttempts;
    private final Duration lockout;

    public AuthService(TransactionManager tm, UserDao userDao, TokenDao tokenDao,
                       TokenService tokenService, PasswordHasher passwordHasher,
                       AuditLogger audit, int maxFailedAttempts, Duration lockout) {
        this.tm = tm;
        this.userDao = userDao;
        this.tokenDao = tokenDao;
        this.tokenService = tokenService;
        this.passwordHasher = passwordHasher;
        this.audit = audit;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockout = lockout;
    }

    public record LoginOutcome(LoginResponse response, int failedAttemptsLeft) {
    }

    /**
     * Attempt login. On success returns a fresh bearer token. Failure detail
     * is deliberately identical for unknown user and wrong password.
     *
     * <p>Failed-attempt counting and the LOGIN_FAILED audit entry are written
     * in their own committed transaction so that lockout state survives the
     * (rolled back) failed login.
     */
    public LoginResponse login(LoginRequest request, String ip) {
        if (request == null) {
            throw new UnauthenticatedException("Invalid username or password");
        }
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (username.isEmpty() || password.isEmpty()) {
            throw new UnauthenticatedException("Invalid username or password");
        }
        // Prefer the request-scoped client IP (proxy-aware) over the direct
        // socket address handed in by the controller.
        String resolvedIp = com.ims.http.RequestContext.clientIp();
        if (resolvedIp == null || resolvedIp.isBlank()) {
            resolvedIp = ip;
        }
        final String clientIp = resolvedIp;
        final String userAgent = com.ims.http.RequestContext.userAgent();

        Optional<User> found = tm.withConnection(conn ->
                userDao.findByUsername(conn, username));
        if (found.isEmpty()) {
            recordFailedAttempt(null, username, "unknown username");
            throw new UnauthenticatedException("Invalid username or password");
        }
        User account = found.get();
        Instant now = Instant.now();

        if (!account.isActive()) {
            recordFailedAttempt(account, username, "account disabled");
            throw new UnauthenticatedException("Invalid username or password");
        }
        if (account.isLocked(now)) {
            recordFailedAttempt(account, username,
                    "account locked until " + account.getLockedUntil());
            throw new UnauthenticatedException("Account temporarily locked - try again later");
        }
        if (!passwordHasher.verify(password, account.getPasswordHash())) {
            int attempts = recordFailedAttempt(account, username,
                    "bad password");
            if (attempts >= maxFailedAttempts) {
                log.warn("Account {} locked after {} failed attempts",
                        username, attempts);
            }
            throw new UnauthenticatedException("Invalid username or password");
        }

        return tm.inTransaction(conn -> {
            userDao.registerSuccessfulLogin(conn, account.getId());
            TokenService.IssuedToken issued = tokenService.issue(account.getId(),
                    userAgent, clientIp);
            audit.logAction(conn, new AuthContext(account.getId(),
                            account.getUsername(), account.getRole(), 0),
                    AuditLogs.LOGIN_SUCCEEDED, "USER", account.getId(),
                    account.getUsername(), null, null);
            log.info("User {} logged in from {}", account.getUsername(),
                    clientIp == null ? "unknown" : clientIp);
            return new LoginResponse(issued.token(), issued.expiresAt(),
                    account.getId(), account.getUsername(), account.getFullName(),
                    account.getRole());
        });
    }

    /**
     * Persist a failed attempt + audit entry in a SEPARATE committed
     * transaction. Returns the new attempt count (or -1 when the account is
     * unknown and there is nothing to count).
     */
    private int recordFailedAttempt(User account, String username, String reason) {
        if (account == null) {
            tm.inTransaction(conn -> {
                audit.logAction(conn, null, AuditLogs.LOGIN_FAILED, "USER", null,
                        username, null, reason);
                return null;
            });
            return -1;
        }
        return tm.inTransaction(conn -> {
            int attempts = userDao.registerFailedLogin(conn, account,
                    maxFailedAttempts, lockout);
            audit.logAction(conn, null, AuditLogs.LOGIN_FAILED, "USER",
                    account.getId(), username, null,
                    reason + " (attempt " + attempts + ")");
            return attempts;
        });
    }

    /** Invalidate the presented token (logout). Idempotent. */
    /**
     * Self-service password change. Verifies the current password, rehashes
     * the new one, and revokes every OTHER session of the user - the
     * presented token stays valid so the calling client keeps working, but
     * stolen sessions die immediately.
     */
    public void changePassword(AuthContext actor,
                               com.ims.domain.user.dto.UserDtos.ChangeOwnPasswordRequest request) {
        if (request == null) {
            throw new com.ims.common.exception.ValidationException(
                    "currentPassword", "is required");
        }
        String currentPassword = com.ims.common.util.Validators.requireText(
                request.currentPassword(), "currentPassword", 200);
        String newPassword = com.ims.common.util.Validators.requireText(
                request.newPassword(), "newPassword", 200);
        String violation = passwordHasher.policyViolation(newPassword);
        if (violation != null) {
            throw new com.ims.common.exception.ValidationException("newPassword",
                    violation);
        }

        tm.inTransaction(conn -> {
            var user = userDao.findById(conn, actor.userId())
                    .orElseThrow(() -> new com.ims.common.exception.NotFoundException(
                            "User", actor.userId()));
            if (!passwordHasher.verify(currentPassword, user.getPasswordHash())) {
                throw new com.ims.common.exception.ValidationException(
                        "currentPassword", "is incorrect");
            }
            if (!userDao.updatePassword(conn, actor.userId(),
                    passwordHasher.hash(newPassword), Dates.now())) {
                throw new com.ims.common.exception.NotFoundException(
                        "User", actor.userId());
            }
            int revoked = tokenDao.revokeAllForUserExcept(conn, actor.userId(),
                    actor.tokenId());
            audit.logAction(conn, actor, AuditLogs.PASSWORD_CHANGED, "USER",
                    actor.userId(), actor.username(), null,
                    java.util.Map.of("otherSessionsRevoked", revoked));
            log.info("Password for {} changed by the account owner "
                            + "({} other session(s) revoked)",
                    actor.username(), revoked);
            return null;
        });
    }

    public void logout(AuthContext actor, String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            tm.inTransaction(conn -> {
                tokenService.revoke(rawToken);
                audit.logAction(conn, actor, AuditLogs.LOGOUT, "USER",
                        actor.userId(), actor.username(), null, null);
                return null;
            });
        }
    }

    /** Validate a bearer token and resolve the authenticated principal. */
    public Optional<AuthContext> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return tm.withConnection(conn -> {
            Optional<TokenDao.TokenRecord> record = tokenDao.findByHash(
                    conn, sha256(rawToken));
            if (record.isEmpty() || record.get().revokedAt() != null
                    || record.get().expiresAt().isBefore(Instant.now())) {
                return Optional.<AuthContext>empty();
            }
            Optional<User> user = userDao.findById(conn, record.get().userId());
            if (user.isEmpty() || !user.get().isActive()) {
                return Optional.<AuthContext>empty();
            }
            return Optional.of(new AuthContext(user.get().getId(),
                    user.get().getUsername(), user.get().getRole(), record.get().id()));
        });
    }

    public MeResponse me(AuthContext actor) {
        return tm.withConnection(conn -> {
            User user = userDao.findById(conn, actor.userId())
                    .orElseThrow(() -> new UnauthenticatedException("Session invalid"));
            Set<String> permissions = RolePermissions.permissionsOf(user.getRole())
                    .stream().map(Enum::name).collect(Collectors.toSet());
            return new MeResponse(user.getId(), user.getUsername(), user.getEmail(),
                    user.getFullName(), user.getRole(), permissions,
                    user.getLastLoginAt());
        });
    }

    private String sha256(String value) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
