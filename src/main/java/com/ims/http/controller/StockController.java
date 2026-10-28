package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.stock.StockQueryService;
import com.ims.domain.stock.dto.StockDtos.AdjustRequest;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/stock endpoints: levels, movements ledger, adjustments, CSV exports. */
public final class StockController {

    private final StockQueryService stockQueryService;

    public StockController(StockQueryService stockQueryService) {
        this.stockQueryService = stockQueryService;
    }

    public void register(Router router) {
        router.add("GET", "/api/stock/levels", this::levels);
        router.add("GET", "/api/stock/levels/export", this::levelsExport);
        router.add("GET", "/api/stock/movements", this::movements);
        router.add("GET", "/api/stock/movements/export", this::movementsExport);
        router.add("GET", "/api/stock/adjustments", this::adjustments);
        router.add("POST", "/api/stock/adjustments", this::adjust);
    }

    private void levels(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(stockQueryService.stockLevels(ctx.auth(), ctx.query("sku"),
                ctx.queryAsLong("warehouseId"),
                ctx.queryAsBool("lowOnly"), page));
    }

    private void levelsExport(WebContext ctx) throws Exception {
        var csv = stockQueryService.stockLevelsCsv(ctx.auth(), ctx.query("sku"),
                ctx.queryAsLong("warehouseId"), ctx.queryAsBool("lowOnly"));
        ctx.exchange().getResponseHeaders().set("X-Truncated",
                Boolean.toString(csv.truncated()));
        ctx.sendCsv("stock-levels.csv",
                java.util.List.of("sku", "productName", "warehouseCode",
                        "warehouseName", "qtyOnHand", "qtyReserved",
                        "qtyAvailable", "avgUnitCost", "stockValue"),
                csv.rows());
    }

    private void movements(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(stockQueryService.movements(ctx.auth(), ctx.queryAsLong("productId"),
                ctx.queryAsLong("warehouseId"), ctx.query("type"),
                ctx.query("refType"), ctx.queryAsLong("refId"),
                ctx.query("from"), ctx.query("to"), page));
    }

    private void movementsExport(WebContext ctx) throws Exception {
        var csv = stockQueryService.movementsCsv(ctx.auth(),
                ctx.queryAsLong("productId"), ctx.queryAsLong("warehouseId"),
                ctx.query("type"), ctx.query("refType"), ctx.queryAsLong("refId"),
                ctx.query("from"), ctx.query("to"));
        ctx.exchange().getResponseHeaders().set("X-Truncated",
                Boolean.toString(csv.truncated()));
        ctx.sendCsv("stock-movements.csv",
                java.util.List.of("movementNo", "sku", "warehouseCode", "quantity",
                        "balanceAfter", "unitCost", "type", "refType", "refNo",
                        "reason", "performedAt"),
                csv.rows());
    }

    private void adjustments(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), null, ctx.query("sortDir"));
        ctx.ok(stockQueryService.adjustments(ctx.auth(), ctx.queryAsLong("productId"),
                ctx.queryAsLong("warehouseId"), ctx.query("reason"), page));
    }

    private void adjust(WebContext ctx) throws Exception {
        ctx.created(stockQueryService.adjust(ctx.auth(), ctx.body(AdjustRequest.class)));
    }
}
