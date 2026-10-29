package com.ims.integration;

import com.ims.common.exception.UnauthenticatedException;
import com.ims.domain.auth.AuthService;
import com.ims.domain.auth.dto.AuthDtos.LoginRequest;
import com.ims.security.AuthContext;
import com.ims.security.Role;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login/lockout/logout flows against the real database. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static AuthService authService;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        authService = env.app.authService;
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private String username() {
        return "it-admin-" + System.nanoTime();
    }

    @Test
    @Order(1)
    void loginSucceedsWithCorrectPassword() {
        com.ims.domain.user.dto.UserDtos.Response created =
                env.app.userService.create(users.admin,
                        new com.ims.domain.user.dto.UserDtos.CreateRequest(
                                "alice", "alice@it.example", "Initial!123",
                                "Alice IT", "MANAGER"));
        var response = authService.login(new LoginRequest("alice", "Initial!123"), "127.0.0.1");
        assertNotNull(response.token());
        assertEquals("alice", response.username());
        assertEquals(Role.MANAGER, response.role());
        assertEquals(created.id(), response.userId());
    }

    @Test
    @Order(2)
    void loginFailsWithUniformMessage() {
        UnauthenticatedException e = assertThrows(UnauthenticatedException.class,
                () -> authService.login(new LoginRequest("alice", "wrong-pass"), "ip"));
        assertEquals("Invalid username or password", e.getMessage());
        assertThrows(UnauthenticatedException.class,
                () -> authService.login(new LoginRequest("ghost", "whatever"), "ip"));
    }

    @Test
    @Order(3)
    void blankInputsRejected() {
        assertThrows(UnauthenticatedException.class,
                () -> authService.login(new LoginRequest("", "x"), "ip"));
        assertThrows(UnauthenticatedException.class,
                () -> authService.login(new LoginRequest("alice", ""), "ip"));
        assertThrows(UnauthenticatedException.class,
                () -> authService.login(null, "ip"));
    }

    @Test
    @Order(4)
    void lockoutAfterMaxFailedAttempts() {
        env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "bob", "bob@it.example", "Initial!123", "Bob IT", "STAFF"));
        for (int i = 0; i < 5; i++) {
            assertThrows(UnauthenticatedException.class,
                    () -> authService.login(new LoginRequest("bob", "nope"), "ip"));
        }
        // now the account is locked: even the CORRECT password fails
        UnauthenticatedException locked = assertThrows(UnauthenticatedException.class,
                () -> authService.login(new LoginRequest("bob", "Initial!123"), "ip"));
        assertTrue(locked.getMessage().contains("locked"),
                "locked account must report lockout: " + locked.getMessage());
    }

    @Test
    @Order(5)
    void disabledAccountCannotLogin() {
        var created = env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "carol", "carol@it.example", "Initial!123", "Carol IT", "STAFF"));
        env.app.userService.deactivate(users.admin, created.id());
        UnauthenticatedException e = assertThrows(UnauthenticatedException.class,
                () -> authService.login(new LoginRequest("carol", "Initial!123"), "ip"));
        assertEquals("Invalid username or password", e.getMessage());
    }

    @Test
    @Order(6)
    void tokenValidatesAndLogoutRevokes() {
        env.app.userService.create(users.admin,
                new com.ims.domain.user.dto.UserDtos.CreateRequest(
                        "dave", "dave@it.example", "Initial!123", "Dave IT", "STAFF"));
        var login = authService.login(new LoginRequest("dave", "Initial!123"), "ip");

        Optional<AuthContext> resolved = authService.authenticate(login.token());
        assertTrue(resolved.isPresent());
        assertEquals("dave", resolved.get().username());

        authService.logout(resolved.get(), login.token());
        assertEquals(Optional.empty(), authService.authenticate(login.token()),
                "revoked token must not authenticate");
    }

    @Test
    @Order(7)
    void randomTokensDoNotAuthenticate() {
        assertEquals(Optional.empty(), authService.authenticate("deadbeef"));
        assertEquals(Optional.empty(), authService.authenticate(""));
        assertEquals(Optional.empty(), authService.authenticate(null));
    }
}
