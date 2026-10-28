package com.ims.http.controller;

import com.ims.http.Metrics;
import com.ims.http.Router;
import com.ims.http.WebContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GET /api/metrics (public, config-gated) - Prometheus text exposition.
 * Intended for the internal scrape network; restrict at the ingress when
 * the API is exposed publicly.
 */
public final class MetricsController {

    private final Metrics metrics;
    private final boolean enabled;

    public MetricsController(Metrics metrics, boolean enabled) {
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public void register(Router router) {
        router.add("GET", "/api/metrics", this::scrape);
    }

    private void scrape(WebContext ctx) throws IOException {
        if (!enabled) {
            ctx.send(404, null);
            return;
        }
        String body = metrics.render();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = ctx.exchange().getResponseHeaders();
        headers.set("Content-Type",
                "text/plain; version=0.0.4; charset=utf-8");
        ctx.exchange().sendResponseHeaders(200, bytes.length);
        try (var out = ctx.exchange().getResponseBody()) {
            out.write(bytes);
        }
    }
}
