package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.user.UserService;
import com.ims.domain.user.dto.UserDtos.CreateRequest;
import com.ims.domain.user.dto.UserDtos.ResetPasswordRequest;
import com.ims.domain.user.dto.UserDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;
import com.ims.security.Role;

/** /api/users endpoints. */
public final class UsersController {

    private final UserService userService;

    public UsersController(UserService userService) {
        this.userService = userService;
    }

    public void register(Router router) {
        router.add("GET", "/api/users", this::search);
        router.add("POST", "/api/users", this::create);
        router.add("GET", "/api/users/{id}", this::get);
        router.add("PUT", "/api/users/{id}", this::update);
        router.add("POST", "/api/users/{id}/reset-password", this::resetPassword);
        router.add("POST", "/api/users/{id}/activate", this::activate);
        router.add("POST", "/api/users/{id}/deactivate", this::deactivate);
    }

    private void search(WebContext ctx) throws Exception {
        Role role = ctx.query("role") == null ? null
                : Role.valueOf(ctx.query("role").toUpperCase());
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(userService.search(ctx.auth(), ctx.query("search"), role,
                ctx.query("status"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(userService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.ok(userService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(userService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void resetPassword(WebContext ctx) throws Exception {
        userService.resetPassword(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(ResetPasswordRequest.class));
        ctx.noContent();
    }

    private void activate(WebContext ctx) throws Exception {
        ctx.ok(userService.activate(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void deactivate(WebContext ctx) throws Exception {
        ctx.ok(userService.deactivate(ctx.auth(), ctx.pathParamAsLong("id")));
    }
}
