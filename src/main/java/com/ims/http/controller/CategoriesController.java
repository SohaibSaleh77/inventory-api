package com.ims.http.controller;

import com.ims.domain.category.CategoryService;
import com.ims.domain.category.dto.CategoryDtos.CreateRequest;
import com.ims.domain.category.dto.CategoryDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/categories endpoints. */
public final class CategoriesController {

    private final CategoryService categoryService;

    public CategoriesController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    public void register(Router router) {
        router.add("GET", "/api/categories", this::list);
        router.add("POST", "/api/categories", this::create);
        router.add("GET", "/api/categories/tree", this::tree);
        router.add("GET", "/api/categories/{id}", this::get);
        router.add("PUT", "/api/categories/{id}", this::update);
        router.add("DELETE", "/api/categories/{id}", this::delete);
    }

    private void list(WebContext ctx) throws Exception {
        ctx.ok(categoryService.list(ctx.auth(),
                Boolean.parseBoolean(ctx.query("includeInactive", "false"))));
    }

    private void tree(WebContext ctx) throws Exception {
        ctx.ok(categoryService.tree(ctx.auth(),
                Boolean.parseBoolean(ctx.query("includeInactive", "false"))));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(categoryService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(categoryService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(categoryService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void delete(WebContext ctx) throws Exception {
        categoryService.delete(ctx.auth(), ctx.pathParamAsLong("id"));
        ctx.noContent();
    }
}
