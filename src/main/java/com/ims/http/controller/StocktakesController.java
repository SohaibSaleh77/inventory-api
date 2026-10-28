package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.stocktake.StocktakeService;
import com.ims.domain.stocktake.dto.StocktakeDtos.CountRequest;
import com.ims.domain.stocktake.dto.StocktakeDtos.CreateRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/stocktakes endpoints: the cycle-count lifecycle. */
public final class StocktakesController {

    private final StocktakeService stocktakeService;

    public StocktakesController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    public void register(Router router) {
        router.add("GET", "/api/stocktakes", this::list);
        router.add("POST", "/api/stocktakes", this::create);
        router.add("GET", "/api/stocktakes/{id}", this::get);
        router.add("POST", "/api/stocktakes/{id}/open", this::open);
        router.add("POST", "/api/stocktakes/{id}/counts", this::count);
        router.add("POST", "/api/stocktakes/{id}/close", this::close);
        router.add("POST", "/api/stocktakes/{id}/post", this::post);
        router.add("POST", "/api/stocktakes/{id}/cancel", this::cancel);
        router.add("GET", "/api/stocktakes/{id}/export", this::export);
    }

    private void list(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, null);
        ctx.ok(stocktakeService.list(ctx.auth(), ctx.queryAsLong("warehouseId"),
                ctx.query("status"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(stocktakeService.create(ctx.auth(),
                ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(stocktakeService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void open(WebContext ctx) throws Exception {
        ctx.ok(stocktakeService.open(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void count(WebContext ctx) throws Exception {
        ctx.ok(stocktakeService.submitCount(ctx.auth(),
                ctx.pathParamAsLong("id"), ctx.body(CountRequest.class)));
    }

    private void close(WebContext ctx) throws Exception {
        ctx.ok(stocktakeService.close(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void post(WebContext ctx) throws Exception {
        ctx.ok(stocktakeService.post(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void cancel(WebContext ctx) throws Exception {
        ctx.ok(stocktakeService.cancel(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void export(WebContext ctx) throws Exception {
        long id = ctx.pathParamAsLong("id");
        var sheet = stocktakeService.exportCsv(ctx.auth(), id);
        var headers = java.util.List.of("sku", "productName", "expectedQty",
                "countedQty", "variance", "recounts", "countedAt");
        ctx.exchange().getResponseHeaders()
                .set("X-Truncated", String.valueOf(sheet.truncated()));
        ctx.sendCsv("stocktake-" + id + ".csv", headers, sheet.rows());
    }
}
