package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.customer.CustomerService;
import com.ims.domain.customer.dto.CustomerDtos.CreateRequest;
import com.ims.domain.customer.dto.CustomerDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/customers endpoints. */
public final class CustomersController {

    private final CustomerService customerService;

    public CustomersController(CustomerService customerService) {
        this.customerService = customerService;
    }

    public void register(Router router) {
        router.add("GET", "/api/customers", this::search);
        router.add("POST", "/api/customers", this::create);
        router.add("GET", "/api/customers/{id}", this::get);
        router.add("PUT", "/api/customers/{id}", this::update);
        router.add("DELETE", "/api/customers/{id}", this::delete);
    }

    private void search(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(customerService.search(ctx.auth(), ctx.query("search"),
                ctx.queryAsBool("active"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(customerService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(customerService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(customerService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void delete(WebContext ctx) throws Exception {
        customerService.delete(ctx.auth(), ctx.pathParamAsLong("id"));
        ctx.noContent();
    }
}
