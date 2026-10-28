package com.ims.http.controller;

import com.ims.domain.report.ReportService;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/reports endpoints. */
public final class ReportsController {

    private final ReportService reportService;

    public ReportsController(ReportService reportService) {
        this.reportService = reportService;
    }

    public void register(Router router) {
        router.add("GET", "/api/reports/stock-summary", this::stockSummary);
        router.add("GET", "/api/reports/stock-valuation", this::stockValuation);
        router.add("GET", "/api/reports/low-stock", this::lowStock);
        router.add("GET", "/api/reports/reorder-proposals", this::reorderProposals);
        router.add("GET", "/api/reports/sales-by-customer", this::salesByCustomer);
        router.add("GET", "/api/reports/purchases-by-supplier", this::purchasesBySupplier);
        router.add("GET", "/api/reports/movement-summary", this::movementSummary);
        router.add("GET", "/api/reports/gross-margin", this::grossMargin);
        router.add("GET", "/api/dashboard", this::dashboard);
    }

    private void stockSummary(WebContext ctx) throws Exception {
        ctx.ok(reportService.stockSummary(ctx.auth()));
    }

    private void stockValuation(WebContext ctx) throws Exception {
        ctx.ok(reportService.stockValuation(ctx.auth()));
    }

    private void lowStock(WebContext ctx) throws Exception {
        ctx.ok(reportService.lowStock(ctx.auth()));
    }

    private void salesByCustomer(WebContext ctx) throws Exception {
        ctx.ok(reportService.salesByCustomer(ctx.auth(), ctx.query("dateFrom"),
                ctx.query("dateTo")));
    }

    private void purchasesBySupplier(WebContext ctx) throws Exception {
        ctx.ok(reportService.purchasesBySupplier(ctx.auth(), ctx.query("dateFrom"),
                ctx.query("dateTo")));
    }

    private void movementSummary(WebContext ctx) throws Exception {
        ctx.ok(reportService.movementSummary(ctx.auth(), ctx.query("dateFrom"),
                ctx.query("dateTo")));
    }

    private void grossMargin(WebContext ctx) throws Exception {
        ctx.ok(reportService.grossMargin(ctx.auth(), ctx.query("dateFrom"),
                ctx.query("dateTo")));
    }

    private void reorderProposals(WebContext ctx) throws Exception {
        ctx.ok(reportService.reorderProposals(ctx.auth()));
    }

    private void dashboard(WebContext ctx) throws Exception {
        ctx.ok(reportService.dashboard(ctx.auth()));
    }
}
