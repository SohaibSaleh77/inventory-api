package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.transfer.TransferService;
import com.ims.domain.transfer.dto.TransferDtos.CreateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/transfers endpoints: two-phase warehouse movements. */
public final class TransfersController {

    private final TransferService transferService;

    public TransfersController(TransferService transferService) {
        this.transferService = transferService;
    }

    public void register(Router router) {
        router.add("GET", "/api/transfers", this::search);
        router.add("POST", "/api/transfers", this::create);
        router.add("GET", "/api/transfers/{id}", this::get);
        router.add("POST", "/api/transfers/{id}/dispatch", this::dispatch);
        router.add("POST", "/api/transfers/{id}/receive", this::receive);
        router.add("POST", "/api/transfers/{id}/cancel", this::cancel);
    }

    private void search(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(transferService.search(ctx.auth(), ctx.query("status"),
                ctx.queryAsLong("productId"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(transferService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(transferService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void dispatch(WebContext ctx) throws Exception {
        ctx.ok(transferService.dispatch(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void receive(WebContext ctx) throws Exception {
        ctx.ok(transferService.receive(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void cancel(WebContext ctx) throws Exception {
        ctx.ok(transferService.cancel(ctx.auth(), ctx.pathParamAsLong("id")));
    }
}
