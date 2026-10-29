package com.ims.testing;

import com.ims.domain.category.dto.CategoryDtos;
import com.ims.domain.customer.dto.CustomerDtos;
import com.ims.domain.product.dto.ProductDtos;
import com.ims.domain.supplier.dto.SupplierDtos;
import com.ims.domain.warehouse.dto.WarehouseDtos;
import com.ims.security.AuthContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Master-data fixtures created through the REAL services. */
public final class Fixtures {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100000);

    private final com.ims.core.ApplicationContext app;

    public Fixtures(com.ims.core.ApplicationContext app) {
        this.app = app;
    }

    public String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    public WarehouseDtos.Response warehouse(AuthContext actor, String code) {
        return app.warehouseService.create(actor, new WarehouseDtos.CreateRequest(
                code, "Warehouse " + code, "Address " + code, "Riyadh", "Riyadh",
                "12345", "Saudi Arabia"));
    }

    public CategoryDtos.Response category(AuthContext actor, String code,
                                          String parentCode) {
        return app.categoryService.create(actor, new CategoryDtos.CreateRequest(
                parentCode, code, "Category " + code, null));
    }

    public SupplierDtos.Response supplier(AuthContext actor, String code) {
        return app.supplierService.create(actor, new SupplierDtos.CreateRequest(
                code, "Supplier " + code, "Contact " + code, code.toLowerCase()
                + "@sup.example", "+966500000000", "Riyadh", null, "Riyadh",
                "Riyadh", "12345", "Saudi Arabia", 30, 4, null));
    }

    public CustomerDtos.Response customer(AuthContext actor, String code) {
        return app.customerService.create(actor, new CustomerDtos.CreateRequest(
                code, "Customer " + code, "RETAIL", code.toLowerCase() + "@cus.example",
                "+966500000001", "Jeddah", null, "Jeddah", "Makkah", "23456",
                "Saudi Arabia", new BigDecimal("100000"), null));
    }

    public ProductDtos.Response product(AuthContext actor, String sku, String categoryCode,
                                        BigDecimal cost, BigDecimal price,
                                        BigDecimal reorderPoint) {
        return app.productService.create(actor, new ProductDtos.CreateRequest(
                sku, null, "Product " + sku, "Description of " + sku, categoryCode,
                "EA", cost, price, reorderPoint, BigDecimal.ZERO));
    }

    /**
     * Receive stock into a warehouse through the real flow:
     * create PO -> submit -> approve -> receive. Returns the created PO id.
     */
    public long receiveStock(AuthContext actor, String supplierCode, String warehouseCode,
                             String productSku, int qty, BigDecimal unitCost) {
        var line = new com.ims.domain.purchase.dto.PurchaseDtos.LineRequest(
                productSku, BigDecimal.valueOf(qty), unitCost, BigDecimal.ZERO);
        var response = app.purchaseOrderService.create(actor,
                new com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest(
                        supplierCode, warehouseCode, null, null, null, null,
                        List.of(line)));
        app.purchaseOrderService.submit(actor, response.id());
        app.purchaseOrderService.approve(actor, response.id());
        var detail = app.purchaseOrderService.get(actor, response.id());
        long lineId = detail.lines().get(0).id();
        app.receivingService.receive(actor, response.id(),
                new com.ims.domain.purchase.dto.PurchaseDtos.ReceiveRequest(
                        List.of(new com.ims.domain.purchase.dto.PurchaseDtos
                                .ReceiveLineRequest(lineId, BigDecimal.valueOf(qty),
                                BigDecimal.ZERO)), null));
        return response.id();
    }
}
