package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.webhook.WebhookService;
import com.ims.domain.webhook.dto.WebhookDtos.CreateRequest;
import com.ims.domain.webhook.dto.WebhookDtos.UpdateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/webhooks endpoints: registration CRUD plus delivery inspection. */
public final class WebhooksController {

    private final WebhookService webhookService;

    public WebhooksController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    public void register(Router router) {
        router.add("GET", "/api/webhooks", this::list);
        router.add("POST", "/api/webhooks", this::create);
        router.add("GET", "/api/webhooks/{id}", this::get);
        router.add("PUT", "/api/webhooks/{id}", this::update);
        router.add("DELETE", "/api/webhooks/{id}", this::delete);
        router.add("POST", "/api/webhooks/{id}/test", this::test);
        router.add("GET", "/api/webhooks/{id}/deliveries", this::deliveries);
        router.add("POST", "/api/webhooks/{id}/deliveries/{deliveryId}/retry",
                this::retry);
    }

    private void list(WebContext ctx) throws Exception {
        ctx.ok(webhookService.list(ctx.auth()));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(webhookService.create(ctx.auth(),
                ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(webhookService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(webhookService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void delete(WebContext ctx) throws Exception {
        webhookService.delete(ctx.auth(), ctx.pathParamAsLong("id"));
        ctx.noContent();
    }

    private void test(WebContext ctx) throws Exception {
        ctx.ok(webhookService.sendTestEvent(ctx.auth(),
                ctx.pathParamAsLong("id")));
    }

    private void deliveries(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, null);
        ctx.ok(webhookService.deliveries(ctx.auth(),
                ctx.pathParamAsLong("id"), ctx.query("status"), page));
    }

    private void retry(WebContext ctx) throws Exception {
        ctx.ok(webhookService.retryDelivery(ctx.auth(),
                ctx.pathParamAsLong("id"), ctx.pathParamAsLong("deliveryId")));
    }
}
