package com.ims.integration;

import com.ims.common.exception.ForbiddenException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.user.dto.UserDtos.CreateRequest;
import com.ims.domain.user.dto.UserDtos.ResetPasswordRequest;
import com.ims.domain.user.dto.UserDtos.Response;
import com.ims.domain.user.dto.UserDtos.UpdateRequest;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** User administration rules: RBAC guards, uniqueness, last-admin protection. */
class UserServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static int seq = 0;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
    }

    @BeforeEach
    void cleanUsers() {
        // keep the fixture users; remove test users from earlier methods
        env.transactions.inTransaction(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM users WHERE username LIKE 'u-it-%'")) {
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private String username() {
        return "u-it-" + (++seq);
    }

    @Test
    void createPreservesCaseAndValidates() {
        Response created = env.app.userService.create(users.admin,
                new CreateRequest("CaseSensitive", "case@it.example",
                        "Initial!123", "Case User", "STAFF"));
        assertEquals("CaseSensitive", created.username());
        assertNotNull(created.id());

        // duplicate username (case-insensitive via collation) rejected
        assertThrows(ValidationException.class, () ->
                env.app.userService.create(users.admin, new CreateRequest(
                        "casesensitive", "other@it.example", "Initial!123",
                        "Dup", "STAFF")));
        assertThrows(ValidationException.class, () ->
                env.app.userService.create(users.admin, new CreateRequest(
                        "okname", "case@it.example", "Initial!123", "Dup", "STAFF")),
                "duplicate email rejected");
        // weak password rejected
        assertThrows(ValidationException.class, () ->
                env.app.userService.create(users.admin, new CreateRequest(
                        "okname2", "x@it.example", "short", "Weak", "STAFF")));
        // bad role rejected
        assertThrows(ValidationException.class, () ->
                env.app.userService.create(users.admin, new CreateRequest(
                        "okname3", "y@it.example", "Initial!123", "Bad Role",
                        "SUPERUSER")));
    }

    @Test
    void staffCannotAdministerUsers() {
        assertThrows(ForbiddenException.class, () ->
                env.app.userService.create(users.staff, new CreateRequest(
                        username(), "s@it.example", "Initial!123", "S", "STAFF")));
        assertThrows(ForbiddenException.class, () ->
                env.app.userService.search(users.viewer, null, null, null,
                        com.ims.common.util.PageRequest.of(1, 10, null, null)));
    }

    @Test
    void managerCanReadButNotWriteUsers() {
        var page = env.app.userService.search(users.manager, null, null, null,
                com.ims.common.util.PageRequest.of(1, 10, null, null));
        assertTrue(page.totalElements() >= 4);
        assertThrows(ForbiddenException.class, () ->
                env.app.userService.create(users.manager, new CreateRequest(
                        username(), "m@it.example", "Initial!123", "M", "STAFF")));
    }

    @Test
    void lastActiveAdminProtected() {
        Response created = env.app.userService.create(users.admin,
                new CreateRequest(username(), "adm@it.example", "Initial!123",
                        "Second Admin", "ADMIN"));
        // with two active admins, demoting/deactivating one is fine
        env.app.userService.update(users.admin, created.id(),
                new UpdateRequest("adm@it.example", "Second Admin", "MANAGER"));
        // promote back, then remove all admins except the fixture one
        env.app.userService.update(users.admin, created.id(),
                new UpdateRequest("adm@it.example", "Second Admin", "ADMIN"));
        env.app.userService.deactivate(users.admin, created.id());
        // the fixture admin is the only active ADMIN now: demotion blocked
        TestUsers fixture = users;
        assertThrows(ValidationException.class, () ->
                env.app.userService.update(fixture.admin, fixture.adminId,
                        new UpdateRequest("admin@test.example", "SysAdmin",
                                "VIEWER")));
        assertThrows(ValidationException.class, () ->
                env.app.userService.deactivate(fixture.admin, fixture.adminId));
        // cannot deactivate yourself either
        assertThrows(ValidationException.class, () ->
                env.app.userService.deactivate(fixture.admin, fixture.adminId));
    }

    @Test
    void passwordResetClearsLockout() {
        Response created = env.app.userService.create(users.admin,
                new CreateRequest(username(), "locked@it.example",
                        "Initial!123", "Locked", "STAFF"));
        // burn the lockout with bad logins
        for (int i = 0; i < 5; i++) {
            assertThrows(Exception.class, () ->
                    env.app.authService.login(new com.ims.domain.auth.dto
                            .AuthDtos.LoginRequest(created.username(), "wrong"),
                            "ip"));
        }
        // admin resets the password -> lock cleared, new password works
        env.app.userService.resetPassword(users.admin, created.id(),
                new ResetPasswordRequest("Brand!New99"));
        var login = env.app.authService.login(new com.ims.domain.auth.dto
                .AuthDtos.LoginRequest(created.username(), "Brand!New99"), "ip");
        assertEquals(created.username(), login.username());
    }

    @Test
    void unknownUserYields404() {
        assertThrows(NotFoundException.class, () ->
                env.app.userService.get(users.admin, 999999L));
        assertThrows(NotFoundException.class, () ->
                env.app.userService.resetPassword(users.admin, 999999L,
                        new ResetPasswordRequest("Whatever!123")));
    }
}
