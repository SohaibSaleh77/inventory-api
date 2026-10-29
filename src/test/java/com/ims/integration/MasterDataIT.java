package com.ims.integration;

import com.ims.common.exception.ForbiddenException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.customer.dto.CustomerDtos;
import com.ims.domain.supplier.dto.SupplierDtos;
import com.ims.domain.warehouse.dto.WarehouseDtos;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Master-data rules for suppliers, customers and warehouses. */
class MasterDataIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    // ---- suppliers ----

    @Test
    void supplierCrudAndCatalog() {
        var supplier = fx.supplier(users.manager, "MD-SUP");
        assertEquals("MD-SUP", supplier.code());

        // product needed by the catalog + receiving steps
        fx.category(users.manager, "MD-SUP-CAT", null);
        fx.product(users.manager, "MD-SKU-1", "MD-SUP-CAT",
                new BigDecimal("10.00"), new BigDecimal("20.00"), null);

        // duplicate code
        assertThrows(ValidationException.class, () ->
                fx.supplier(users.manager, "MD-SUP"));

        // catalog upsert twice = update, not duplicate
        var item1 = env.app.supplierService.upsertItem(users.manager, supplier.id(),
                new SupplierDtos.UpsertItemRequest("MD-SKU-1", "SUP-A",
                        new BigDecimal("12.50"), true, 10));
        var item2 = env.app.supplierService.upsertItem(users.manager, supplier.id(),
                new SupplierDtos.UpsertItemRequest("MD-SKU-1", "SUP-A2",
                        new BigDecimal("13.75"), false, 12));
        var detail = env.app.supplierService.getDetail(users.manager, supplier.id());
        assertEquals(1, detail.items().size(), "upsert must not duplicate items");
        assertEquals(new BigDecimal("13.75"), detail.items().get(0).unitCost());

        // remove item
        env.app.supplierService.removeItem(users.manager, supplier.id(),
                detail.items().get(0).productId());
        assertEquals(0, env.app.supplierService
                .getDetail(users.manager, supplier.id()).items().size());

        // supplier with purchase history cannot be deleted
        fx.warehouse(users.manager, "MD-WH");
        fx.receiveStock(users.manager, "MD-SUP", "MD-WH", "MD-SKU-1", 5,
                new BigDecimal("12.50"));
        assertThrows(ResourceInUseException.class, () ->
                env.app.supplierService.delete(users.manager, supplier.id()));
    }

    @Test
    void supplierValidation() {
        assertThrows(ValidationException.class, () ->
                env.app.supplierService.create(users.manager,
                        new SupplierDtos.CreateRequest("MD-BAD1", "S", null,
                                "not-an-email", null, null, null, null, null,
                                null, null, null, null, null)));
        assertThrows(ValidationException.class, () ->
                env.app.supplierService.create(users.manager,
                        new SupplierDtos.CreateRequest("MD-BAD2", "S", null,
                                "ok@x.example", null, null, null, null, null,
                                null, null, 400, null, null)),
                "payment terms > 365 rejected");
        assertThrows(ValidationException.class, () ->
                env.app.supplierService.create(users.manager,
                        new SupplierDtos.CreateRequest("MD-BAD3", "S", null,
                                "ok@x.example", null, null, null, null, null,
                                null, null, null, 9, null)),
                "rating outside 1..5 rejected");
    }

    // ---- customers ----

    @Test
    void customerRules() {
        var customer = fx.customer(users.manager, "MD-CUS");
        assertEquals("RETAIL", customer.customerType());

        // duplicate code
        assertThrows(ValidationException.class, () ->
                fx.customer(users.manager, "MD-CUS"));

        // bad type + negative credit limit
        assertThrows(ValidationException.class, () ->
                env.app.customerService.create(users.manager,
                        new CustomerDtos.CreateRequest("MD-CUS-BAD", "X", "GOVERNMENT",
                                null, null, null, null, null, null, null, null,
                                null, null)));
        assertThrows(ValidationException.class, () ->
                env.app.customerService.create(users.manager,
                        new CustomerDtos.CreateRequest("MD-CUS-BAD2", "X", null,
                                null, null, null, null, null, null, null, null,
                                new BigDecimal("-1"), null)));

        // update flips type and credit limit
        var updated = env.app.customerService.update(users.manager, customer.id(),
                new CustomerDtos.UpdateRequest("Bigger Customer", "WHOLESALE",
                        null, null, null, null, null, null, null, null,
                        new BigDecimal("250000"), null, null));
        assertEquals("WHOLESALE", updated.customerType());
        assertEquals(0, new BigDecimal("250000").compareTo(updated.creditLimit()));

        assertThrows(NotFoundException.class, () ->
                env.app.customerService.get(users.admin, 999999L));
    }

    // ---- warehouses ----

    @Test
    void warehouseRules() {
        var wh = fx.warehouse(users.manager, "MD-WH2");
        // duplicate code
        assertThrows(ValidationException.class, () ->
                fx.warehouse(users.manager, "MD-WH2"));

        // a warehouse holding stock cannot be deleted (self-contained setup)
        var stocked = fx.warehouse(users.manager, fx.unique("WH-STOCKED"));
        var sup = fx.supplier(users.manager, fx.unique("SUP-FOR-WH"));
        var cat = fx.category(users.manager, fx.unique("CAT-FOR-WH"), null);
        var prd = fx.product(users.manager, fx.unique("SKU-FOR-WH"),
                cat.code(), BigDecimal.ONE, BigDecimal.TEN, null);
        fx.receiveStock(users.manager, sup.code(), stocked.code(), prd.sku(), 3,
                BigDecimal.ONE);
        assertThrows(ResourceInUseException.class, () ->
                env.app.warehouseService.delete(users.manager, stocked.id()));

        // unreferenced one can
        env.app.warehouseService.delete(users.manager, wh.id());
        assertThrows(NotFoundException.class, () ->
                env.app.warehouseService.get(users.manager, wh.id()));
    }

    @Test
    void viewerCannotWriteMasterData() {
        assertThrows(ForbiddenException.class, () ->
                fx.warehouse(users.viewer, fx.unique("WH-X")));
        assertThrows(ForbiddenException.class, () ->
                fx.supplier(users.viewer, fx.unique("SUP-X")));
        assertThrows(ForbiddenException.class, () ->
                fx.customer(users.viewer, fx.unique("CUS-X")));
    }

    @Test
    void productBarcodeUnique() {
        fx.category(users.manager, "MD-CAT", null);
        env.app.productService.create(users.manager,
                new com.ims.domain.product.dto.ProductDtos.CreateRequest(
                        fx.unique("SKU-A"), "1234567890", "Original Barcode",
                        null, "MD-CAT", null, null, null, null, null));
        // same barcode on another product -> rejected
        assertThrows(ValidationException.class, () ->
                env.app.productService.create(users.manager,
                        new com.ims.domain.product.dto.ProductDtos.CreateRequest(
                                fx.unique("SKU-B"), "1234567890", "Dup Barcode",
                                null, "MD-CAT", null, null, null, null, null)),
                "barcode must be unique");
        assertTrue(env.app.productService.search(users.viewer, null, null, null,
                null, com.ims.common.util.PageRequest.of(1, 100, null, null))
                .totalElements() > 0);
    }
}
