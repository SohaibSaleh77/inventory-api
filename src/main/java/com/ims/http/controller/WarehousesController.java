package com.ims.http.controller;

import com.ims.domain.warehouse.WarehouseService;
import com.ims.domain.warehouse.dto.WarehouseDtos.CreateRequest;
import com.ims.domain.warehouse.dto.WarehouseDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/warehouses endpoints. */
public final class WarehousesController {

    private final WarehouseService warehouseService;

    public WarehousesController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    public void register(Router router) {
        router.add("GET", "/api/warehouses", this::list);
        router.add("POST", "/api/warehouses", this::create);
        router.add("GET", "/api/warehouses/{id}", this::get);
        router.add("PUT", "/api/warehouses/{id}", this::update);
        router.add("DELETE", "/api/warehouses/{id}", this::delete);
    }

    private void list(WebContext ctx) throws Exception {
        ctx.ok(warehouseService.list(ctx.auth(),
                Boolean.parseBoolean(ctx.query("includeInactive", "false"))));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(warehouseService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(warehouseService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(warehouseService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void delete(WebContext ctx) throws Exception {
        warehouseService.delete(ctx.auth(), ctx.pathParamAsLong("id"));
        ctx.noContent();
    }
}
