package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.supplier.SupplierService;
import com.ims.domain.supplier.dto.SupplierDtos.CreateRequest;
import com.ims.domain.supplier.dto.SupplierDtos.UpdateRequest;
import com.ims.domain.supplier.dto.SupplierDtos.UpsertItemRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/suppliers endpoints. */
public final class SuppliersController {

    private final SupplierService supplierService;

    public SuppliersController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    public void register(Router router) {
        router.add("GET", "/api/suppliers", this::search);
        router.add("POST", "/api/suppliers", this::create);
        router.add("GET", "/api/suppliers/{id}", this::get);
        router.add("GET", "/api/suppliers/{id}/detail", this::getDetail);
        router.add("PUT", "/api/suppliers/{id}", this::update);
        router.add("DELETE", "/api/suppliers/{id}", this::delete);
        router.add("PUT", "/api/suppliers/{id}/items", this::upsertItem);
        router.add("DELETE", "/api/suppliers/{id}/items/{productId}", this::removeItem);
    }

    private void search(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(supplierService.search(ctx.auth(), ctx.query("search"),
                ctx.queryAsBool("active"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(supplierService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(supplierService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void getDetail(WebContext ctx) throws Exception {
        ctx.ok(supplierService.getDetail(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(supplierService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void delete(WebContext ctx) throws Exception {
        supplierService.delete(ctx.auth(), ctx.pathParamAsLong("id"));
        ctx.noContent();
    }

    private void upsertItem(WebContext ctx) throws Exception {
        ctx.ok(supplierService.upsertItem(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpsertItemRequest.class)));
    }

    private void removeItem(WebContext ctx) throws Exception {
        supplierService.removeItem(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.pathParamAsLong("productId"));
        ctx.noContent();
    }
}
