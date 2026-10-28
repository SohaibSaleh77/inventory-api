package com.ims.domain.user;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Dates;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.user.dto.UserDtos.CreateRequest;
import com.ims.domain.user.dto.UserDtos.ResetPasswordRequest;
import com.ims.domain.user.dto.UserDtos.Response;
import com.ims.domain.user.dto.UserDtos.UpdateRequest;
import com.ims.security.AuthContext;
import com.ims.security.PasswordHasher;
import com.ims.security.Permission;
import com.ims.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;

/**
 * User administration: creation, updates, status changes, password resets.
 * All mutations are audited; all run in transactions.
 */
public final class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransactionManager tm;
    private final UserDao userDao;
    private final PasswordHasher passwordHasher;
    private final AuditLogger audit;

    public UserService(TransactionManager tm, UserDao userDao,
                       PasswordHasher passwordHasher, AuditLogger audit) {
        this.tm = tm;
        this.userDao = userDao;
        this.passwordHasher = passwordHasher;
        this.audit = audit;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.USER_WRITE);

        String username = Validators.requireHandle(request.username(), "username", 50);
        String email = Validators.requireEmail(request.email(), "email");
        String fullName = Validators.requireText(request.fullName(), "fullName", 150);
        Role role = parseRole(request.role());
        validatePasswordPolicy(request.password());

        String passwordHash = passwordHasher.hash(request.password());

        return tm.inTransaction(conn -> {
            if (userDao.usernameExists(conn, username)) {
                throw new ValidationException("username", "already in use");
            }
            if (userDao.emailExists(conn, email)) {
                throw new ValidationException("email", "already in use");
            }
            User user = new User(username, email, passwordHash, fullName, role);
            user.setPasswordChangedAt(Dates.now());
            User inserted = userDao.insert(conn, user);
            audit.logAction(conn, actor, AuditLogs.USER_CREATED, "USER", inserted.getId(),
                    inserted.getUsername(), null, Response.from(inserted));
            log.info("User {} created by {}", username, actor.username());
            return Response.from(inserted);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.USER_WRITE);
        String email = Validators.requireEmail(request.email(), "email");
        String fullName = Validators.requireText(request.fullName(), "fullName", 150);
        Role role = parseRole(request.role());

        return tm.inTransaction(conn -> {
            User user = userDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("User", id));
            Response before = Response.from(user);

            if ("ADMIN".equals(user.getRole().name()) && role != Role.ADMIN) {
                long activeAdmins = userDao.search(conn, null, Role.ADMIN,
                        User.STATUS_ACTIVE, PageRequest.of(1, 100, null, null)).size();
                if (activeAdmins <= 1) {
                    throw new ValidationException("role",
                            "cannot demote the last active ADMIN");
                }
            }
            if (!user.getEmail().equals(email) && userDao.emailExists(conn, email)) {
                throw new ValidationException("email", "already in use");
            }
            user.setEmail(email);
            user.setFullName(fullName);
            user.setRole(role);
            if (!userDao.update(conn, user)) {
                throw new OptimisticLockException("User", id);
            }
            User updated = userDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.USER_UPDATED, "USER", id,
                    updated.getUsername(), before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.USER_READ);
        return tm.withConnection(conn ->
                userDao.findById(conn, id)
                        .map(Response::from)
                        .orElseThrow(() -> new NotFoundException("User", id)));
    }

    public PageRequest.Page<Response> search(AuthContext actor, String search, Role role,
                                             String status, PageRequest page) {
        actor.require(Permission.USER_READ);
        return tm.withConnection(conn -> {
            List<User> users = userDao.search(conn, search, role, status, page);
            long total = userDao.countSearch(conn, search, role, status);
            return PageRequest.Page.of(users.stream().map(Response::from).toList(),
                    page, total);
        });
    }

    public Response activate(AuthContext actor, long id) {
        actor.require(Permission.USER_WRITE);
        return changeStatus(actor, id, User.STATUS_ACTIVE, AuditLogs.USER_ACTIVATED);
    }

    public Response deactivate(AuthContext actor, long id) {
        actor.require(Permission.USER_WRITE);
        if (actor.userId() == id) {
            throw new ValidationException("id", "cannot deactivate your own account");
        }
        return changeStatus(actor, id, User.STATUS_DISABLED, AuditLogs.USER_DEACTIVATED);
    }

    private Response changeStatus(AuthContext actor, long id, String status, String action) {
        return tm.inTransaction(conn -> {
            User user = userDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("User", id));
            Response before = Response.from(user);

            if (User.STATUS_DISABLED.equals(status)
                    && Role.ADMIN.equals(user.getRole())) {
                long activeAdmins = userDao.search(conn, null, Role.ADMIN,
                        User.STATUS_ACTIVE, PageRequest.of(1, 100, null, null)).size();
                if (activeAdmins <= 1) {
                    throw new ValidationException("status",
                            "cannot disable the last active ADMIN");
                }
            }
            if (!userDao.setStatus(conn, id, status)) {
                throw new NotFoundException("User", id);
            }
            User updated = userDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, action, "USER", id, updated.getUsername(),
                    before, Response.from(updated));
            return Response.from(updated);
        });
    }

    /** Admin-triggered password reset. */
    public void resetPassword(AuthContext actor, long id, ResetPasswordRequest request) {
        actor.require(Permission.USER_WRITE);
        validatePasswordPolicy(request.newPassword());

        tm.inTransaction(conn -> {
            User user = userDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("User", id));
            if (!userDao.updatePassword(conn, id,
                    passwordHasher.hash(request.newPassword()), Dates.now())) {
                throw new NotFoundException("User", id);
            }
            audit.logAction(conn, actor, AuditLogs.USER_PASSWORD_RESET, "USER", id,
                    user.getUsername(), null, null);
            log.info("Password for {} reset by {}", user.getUsername(), actor.username());
            return null;
        });
    }

    /** Generate a one-time bootstrap password (used by the demo/test tooling). */
    public String generateTempPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        sb.append("!7");
        return sb.toString();
    }

    private void validatePasswordPolicy(String password) {
        String violation = PasswordHasher.policyViolation(password);
        if (violation != null) {
            throw new ValidationException("password", violation);
        }
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(Validators.requireText(value, "role", 20).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("role",
                    "must be one of ADMIN, MANAGER, STAFF, VIEWER");
        }
    }
}
