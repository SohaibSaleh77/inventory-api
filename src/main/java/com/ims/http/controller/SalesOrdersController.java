package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.sales.FulfillmentService;
import com.ims.domain.sales.SalesOrderService;
import com.ims.domain.sales.SalesReturnService;
import com.ims.domain.sales.dto.SalesDtos.CreateRequest;
import com.ims.domain.sales.dto.SalesDtos.ReturnRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipRequest;
import com.ims.domain.sales.dto.SalesDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/sales-orders endpoints. */
public final class SalesOrdersController {

    private final SalesOrderService soService;
    private final FulfillmentService fulfillmentService;
    private final SalesReturnService salesReturnService;

    public SalesOrdersController(SalesOrderService soService,
                                 FulfillmentService fulfillmentService,
                                 SalesReturnService salesReturnService) {
        this.soService = soService;
        this.fulfillmentService = fulfillmentService;
        this.salesReturnService = salesReturnService;
    }

    public void register(Router router) {
        router.add("GET", "/api/sales-orders", this::search);
        router.add("POST", "/api/sales-orders", this::create);
        router.add("GET", "/api/sales-orders/{id}", this::get);
        router.add("PUT", "/api/sales-orders/{id}", this::update);
        router.add("DELETE", "/api/sales-orders/{id}", this::cancel);
        router.add("POST", "/api/sales-orders/{id}/confirm", this::confirm);
        router.add("POST", "/api/sales-orders/{id}/close", this::close);
        router.add("POST", "/api/sales-orders/{id}/shipments", this::ship);
        router.add("GET", "/api/sales-orders/{id}/shipments", this::shipments);
        router.add("POST", "/api/sales-orders/{id}/returns", this::receiveReturn);
        router.add("GET", "/api/sales-orders/{id}/returns", this::returns);
    }

    private void search(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(soService.search(ctx.auth(), ctx.query("status"),
                ctx.queryAsLong("customerId"), ctx.queryAsLong("warehouseId"),
                ctx.query("dateFrom"), ctx.query("dateTo"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(soService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(soService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(soService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void cancel(WebContext ctx) throws Exception {
        ctx.ok(soService.cancel(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void confirm(WebContext ctx) throws Exception {
        ctx.ok(soService.confirm(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void close(WebContext ctx) throws Exception {
        ctx.ok(soService.close(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void ship(WebContext ctx) throws Exception {
        ctx.created(fulfillmentService.ship(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(ShipRequest.class)));
    }

    private void shipments(WebContext ctx) throws Exception {
        ctx.ok(fulfillmentService.shipmentsForSo(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void receiveReturn(WebContext ctx) throws Exception {
        ctx.created(salesReturnService.receiveReturn(ctx.auth(),
                ctx.pathParamAsLong("id"), ctx.body(ReturnRequest.class)));
    }

    private void returns(WebContext ctx) throws Exception {
        ctx.ok(salesReturnService.returnsForSo(ctx.auth(), ctx.pathParamAsLong("id")));
    }
}
