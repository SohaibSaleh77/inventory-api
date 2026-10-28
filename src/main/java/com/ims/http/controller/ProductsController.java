package com.ims.http.controller;

import com.ims.common.util.PageRequest;
import com.ims.domain.product.ProductService;
import com.ims.domain.product.dto.ProductDtos.CreateRequest;
import com.ims.domain.product.dto.ProductDtos.UpdateRequest;
import com.ims.domain.stock.StockService;
import com.ims.http.Router;
import com.ims.http.WebContext;

/** /api/products endpoints. */
public final class ProductsController {

    private final ProductService productService;
    private final StockService stockService;

    public ProductsController(ProductService productService, StockService stockService) {
        this.productService = productService;
        this.stockService = stockService;
    }

    public void register(Router router) {
        router.add("GET", "/api/products", this::search);
        router.add("POST", "/api/products", this::create);
        router.add("POST", "/api/products/import", this::importCsv);
        router.add("GET", "/api/products/barcode/{barcode}", this::getByBarcode);
        router.add("GET", "/api/products/sku/{sku}", this::getBySku);
        router.add("GET", "/api/products/{id}", this::get);
        router.add("GET", "/api/products/{id}/stock", this::stock);
        router.add("PUT", "/api/products/{id}", this::update);
        router.add("DELETE", "/api/products/{id}", this::delete);
    }

    private void search(WebContext ctx) throws Exception {
        PageRequest page = PageRequest.of(ctx.queryAsInt("page"),
                ctx.queryAsInt("size"), ctx.query("sortBy"), ctx.query("sortDir"));
        ctx.ok(productService.search(ctx.auth(), ctx.query("search"),
                ctx.query("categoryCode"), ctx.queryAsBool("active"),
                ctx.query("sortBy"), page));
    }

    private void create(WebContext ctx) throws Exception {
        ctx.created(productService.create(ctx.auth(), ctx.body(CreateRequest.class)));
    }

    private void get(WebContext ctx) throws Exception {
        ctx.etagged(productService.get(ctx.auth(), ctx.pathParamAsLong("id")));
    }

    private void getBySku(WebContext ctx) throws Exception {
        ctx.etagged(productService.getBySku(ctx.auth(), ctx.pathParam("sku")));
    }

    private void getByBarcode(WebContext ctx) throws Exception {
        ctx.etagged(productService.getByBarcode(ctx.auth(),
                ctx.pathParam("barcode")));
    }

    private void importCsv(WebContext ctx) throws Exception {
        ctx.ok(productService.importProducts(ctx.auth(), ctx.body()));
    }

    private void stock(WebContext ctx) throws Exception {
        ctx.ok(stockService.getStockAcrossWarehouses(ctx.auth(),
                ctx.pathParamAsLong("id")));
    }

    private void update(WebContext ctx) throws Exception {
        ctx.ok(productService.update(ctx.auth(), ctx.pathParamAsLong("id"),
                ctx.body(UpdateRequest.class)));
    }

    private void delete(WebContext ctx) throws Exception {
        productService.delete(ctx.auth(), ctx.pathParamAsLong("id"));
        ctx.noContent();
    }
}
