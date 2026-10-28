package com.ims.http.controller;

import com.ims.domain.auth.AuthService;
import com.ims.domain.auth.dto.AuthDtos.LoginRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** /api/auth endpoints: login, logout, current principal. */
public final class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public void register(Router router) {
        router.add("POST", "/api/auth/login", this::login);
        router.add("POST", "/api/auth/logout", this::logout);
        router.add("POST", "/api/auth/change-password", this::changePassword);
        router.add("GET", "/api/auth/me", this::me);
    }

    private void login(WebContext ctx) throws Exception {
        LoginRequest request = ctx.body(LoginRequest.class);
        String ip = ctx.exchange().getRemoteAddress().getAddress().getHostAddress();
        ctx.ok(authService.login(request, ip));
    }

    private void logout(WebContext ctx) throws Exception {
        String header = ctx.exchange().getRequestHeaders().getFirst("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        authService.logout(ctx.auth(), token);
        ctx.noContent();
    }

    private void me(WebContext ctx) throws Exception {
        ctx.ok(authService.me(ctx.auth()));
    }

    private void changePassword(WebContext ctx) throws Exception {
        authService.changePassword(ctx.auth(),
                ctx.body(com.ims.domain.user.dto.UserDtos.ChangeOwnPasswordRequest.class));
        ctx.noContent();
    }
}
