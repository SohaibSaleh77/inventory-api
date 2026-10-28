package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.purchase.PurchaseOrderService;
import com.ims.domain.purchase.ReceivingService;
import com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/purchase-orders endpoints. */
public final class PurchaseOrdersController {

    private final PurchaseOrderService poService;
    private final ReceivingService receivingService;

    public PurchaseOrdersController(PurchaseOrderService poService,
                                    ReceivingService receivingService) {
        this.poService = poService;
        this.receivingService = receivingService;
    }

    public void register(Router router) {
        router.add("GET", "/api/purchase-orders", this::search);
        router.add("POST", "/api/purchase-orders", this::create);
        router.add("POST", "/api/purchase-orders/from-reorder", this::createFromReorder);
        router.add("GET", "/api/purchase-orders/{id}", this::get);
        router.add("PUT", "/api/purchase-orders/{id}", this::update);
        router.add("DELETE", "/api/purchase-orders/{id}", this::cancel);
        router.add("POST", "/api/purchase-orders/{id}/submit", this::submit);
        router.add("POST", "/api/purchase-orders/{id}/approve", this::approve);
        router.add("POST", "/api/purchase-orders/{id}/close", this::close);
        router.add("POST", "/api/purchase-orders/{id}/receipts", this::receive);
        router.add("GET", "/api/purchase-orders/{id}/receipts", this::receipts);
    }

    private void search(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(poService.search(ctx.auth(), ctx.query("status"),
                ctx.queryAsLong("supplierId"), ctx.queryAsLong("warehouseId"),
                ctx.query("dateFrom"), ctx.query("dateTo"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(poService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void createFromReorder(WebContext ctx) throws Exception {
        var body = ctx.optBody(FromReorderRequest.class);
        ctx.ok(poService.createFromReorder(ctx.auth(),
                body == null ? null : body.warehouseCode(),
                body == null ? null : body.supplierCode()));
    }

    /** {warehouseCode, supplierCode?} - supplier omitted means one PO per supplier. */
    public record FromReorderRequest(String warehouseCode, String supplierCode) {
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(poService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(poService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void cancel(WebContext ctx) throws Exception {
        ctx.ok(poService.cancel(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void submit(WebContext ctx) throws Exception {
        ctx.ok(poService.submit(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void approve(WebContext ctx) throws Exception {
        ctx.ok(poService.approve(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void close(WebContext ctx) throws Exception {
        ctx.ok(poService.close(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void receive(WebContext ctx) throws Exception {
        ctx.created(receivingService.receive(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(ReceiveRequest.class)));
    }

    private void receipts(WebContext ctx) throws Exception {
        ctx.ok(receivingService.receiptsForPo(ctx.auth(), ctx.pathParamAsLong("id")));
    }
}
