package com.ims.integration;

import com.ims.common.exception.ForbiddenException;
import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.InsufficientStockException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.sales.SalesOrderService;
import com.ims.domain.sales.dto.SalesDtos;
import com.ims.domain.stocktake.Stocktake;
import com.ims.domain.stocktake.dto.StocktakeDtos.CreateRequest;
import com.ims.domain.stocktake.dto.StocktakeDtos.CountRequest;
import com.ims.domain.stocktake.dto.StocktakeDtos.DetailResponse;
import com.ims.domain.stocktake.dto.StocktakeDtos.PostResult;
import com.ims.domain.stocktake.dto.StocktakeDtos.Response;
import com.ims.domain.stocktake.dto.StocktakeDtos.LineResponse;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stocktake lifecycle against a real database: count sheet freezing, blind
 * mode, recounts, variance posting through the adjustment machinery, and
 * the full state-transition matrix.
 */
class StocktakeServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fixtures;

    static {
        env = IntegrationTestEnv.get();
        IntegrationTestEnv.touch();
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    /**
     * Count sheets cover EVERY active product, so each test starts from a
     * clean slate to keep assertions exact.
     */
    private void freshIsolatedState() {
        env.truncateAll();
        users = TestUsers.create(env);
        fixtures = new Fixtures(env.app);
    }

    private long warehouseWithStock(String skuA, int qtyA, String skuB, int qtyB) {
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        fixtures.product(users.admin, skuA, category.code(),
                new BigDecimal("10.00"), new BigDecimal("20.00"), BigDecimal.ZERO);
        fixtures.product(users.admin, skuB, category.code(),
                new BigDecimal("5.00"), new BigDecimal("9.00"), BigDecimal.ZERO);
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                skuA, qtyA, new BigDecimal("10.00"));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                skuB, qtyB, new BigDecimal("5.00"));
        return warehouse.id();
    }

    @Test
    void fullCycleCountsCorrectsAndStamps() {
        freshIsolatedState();
        String skuA = fixtures.unique("STKA");
        String skuB = fixtures.unique("STKB");
        String skuC = fixtures.unique("STKC");
        long warehouseId = warehouseWithStock(skuA, 100, skuB, 40);
        // a third product with no stock in this warehouse: expected 0
        fixtures.product(users.admin, skuC,
                null, new BigDecimal("1.00"), new BigDecimal("2.00"), BigDecimal.ZERO);
        String warehouseCode = env.app.warehouseService
                .get(users.admin, warehouseId).code();

        Response created = env.app.stocktakeService.create(users.admin,
                new CreateRequest(warehouseCode, "Full cycle", false, "ops"));
        assertEquals(Stocktake.STATUS_DRAFT, created.status());
        // every active product appears on the sheet (3 here)
        assertEquals(3, created.lineCount());

        env.app.stocktakeService.open(users.admin, created.id());

        // skuA: counted 97 (variance -3), skuB: counted 40 (no variance),
        // skuC: counted 5 (variance +5, found stock), nothing else counted
        LineResponse a = env.app.stocktakeService.submitCount(users.admin,
                created.id(), new CountRequest(skuA, new BigDecimal("97")));
        assertEquals(0, a.countedQty().compareTo(new BigDecimal("97")));
        assertEquals(0, a.variance().compareTo(new BigDecimal("-3")));
        assertEquals(1, a.recounts());

        // recount overwrites
        env.app.stocktakeService.submitCount(users.admin, created.id(),
                new CountRequest(skuA, new BigDecimal("96")));
        LineResponse aAgain = lineOf(created.id(), skuA);
        assertEquals(0, aAgain.countedQty().compareTo(new BigDecimal("96")));
        assertEquals(2, aAgain.recounts());

        env.app.stocktakeService.submitCount(users.admin, created.id(),
                new CountRequest(skuB, new BigDecimal("40")));
        env.app.stocktakeService.submitCount(users.admin, created.id(),
                new CountRequest(skuC, new BigDecimal("5")));

        Response closed = env.app.stocktakeService.close(users.admin, created.id());
        assertEquals(Stocktake.STATUS_REVIEW, closed.status());
        assertEquals(3, closed.countedLines());
        assertEquals(2, closed.varianceLines());

        PostResult result = env.app.stocktakeService.post(users.admin, created.id());
        assertEquals(3, result.countedLines());
        assertEquals(2, result.correctedLines());
        assertEquals(1, result.zeroVarianceLines());
        assertEquals(0, result.uncountedLines());

        // stock now matches the counts
        assertEquals(new BigDecimal("96.000"),
                env.app.stockQueryService.totalOnHand(users.admin,
                        env.app.productService.getBySku(users.admin, skuA).id()));
        assertEquals(new BigDecimal("5.000"),
                env.app.stockQueryService.totalOnHand(users.admin,
                        env.app.productService.getBySku(users.admin, skuC).id()));

        // ledger carries the corrections with STOCKTAKE references
        var movements = env.app.stockQueryService.movements(users.admin,
                env.app.productService.getBySku(users.admin, skuA).id(), null,
                null, null, null, null, null,
                com.ims.common.util.PageRequest.of(1, 50, null, null));
        assertTrue(movements.content().stream().anyMatch(m ->
                "ADJUSTMENT_OUT".equals(m.movementType())
                        && "STOCKTAKE".equals(m.refType())
                        && created.stocktakeNo().equals(m.refNo())));
        // the found-stock line (skuC, +5) carries the inbound correction
        var movementsC = env.app.stockQueryService.movements(users.admin,
                env.app.productService.getBySku(users.admin, skuC).id(), null,
                null, null, null, null, null,
                com.ims.common.util.PageRequest.of(1, 50, null, null));
        assertTrue(movementsC.content().stream().anyMatch(m ->
                "ADJUSTMENT_IN".equals(m.movementType())
                        && "STOCKTAKE".equals(m.refType())
                        && created.stocktakeNo().equals(m.refNo())));

        // adjustment documents exist for both corrected lines, referencing
        // the stocktake in their note
        var skuAResponse = env.app.productService.getBySku(users.admin, skuA);
        var adjustments = env.app.stockQueryService.adjustments(users.admin,
                skuAResponse.id(), warehouseId, "COUNT_CORRECTION",
                com.ims.common.util.PageRequest.of(1, 10, null, null));
        assertEquals(1, adjustments.totalElements());
        assertTrue(adjustments.content().get(0).note()
                .contains(created.stocktakeNo()));

        // final state: POSTED, immutable
        Response posted = env.app.stocktakeService.list(users.admin, null,
                "POSTED", com.ims.common.util.PageRequest.of(1, 10, null, null))
                .content().stream()
                .filter(r -> r.id() == created.id()).findFirst().orElseThrow();
        assertEquals(Stocktake.STATUS_POSTED, posted.status());
        assertNotNull(posted.postedAt());
        assertThrows(IllegalStateTransitionException.class,
                () -> env.app.stocktakeService.post(users.admin, created.id()));
    }

    @Test
    void blindModeHidesExpectedUntilClosed() {
        freshIsolatedState();
        String skuA = fixtures.unique("BLIND");
        long warehouseId = warehouseWithStock(skuA, 50,
                fixtures.unique("BLIB"), 10);
        String warehouseCode = env.app.warehouseService
                .get(users.admin, warehouseId).code();

        Response created = env.app.stocktakeService.create(users.admin,
                new CreateRequest(warehouseCode, "Blind count", true, null));
        env.app.stocktakeService.open(users.admin, created.id());

        DetailResponse openDetail = env.app.stocktakeService.get(users.admin,
                created.id());
        assertEquals(0, openDetail.varianceLines());
        for (LineResponse line : openDetail.lines()) {
            assertNull(line.expectedQty(), "blind sheet must hide expected qty");
            assertNull(line.variance(), "blind sheet must hide variance");
        }

        // counting still works and returns the counted value
        LineResponse counted = env.app.stocktakeService.submitCount(users.staff,
                created.id(), new CountRequest(skuA, new BigDecimal("50")));
        assertEquals(0, counted.countedQty().compareTo(new BigDecimal("50")));
        assertNull(counted.expectedQty());

        env.app.stocktakeService.close(users.admin, created.id());
        DetailResponse review = env.app.stocktakeService.get(users.admin,
                created.id());
        // after close the reviewer sees everything
        LineResponse revealed = review.lines().stream()
                .filter(line -> skuA.equals(line.sku())).findFirst().orElseThrow();
        assertEquals(0, revealed.expectedQty().compareTo(new BigDecimal("50")));
        assertEquals(0, revealed.variance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void postingFailsWhenCountUndercutsReservations() {
        freshIsolatedState();
        String sku = fixtures.unique("RSV");
        long warehouseId = warehouseWithStock(sku, 30,
                fixtures.unique("RSVX"), 5);
        String warehouseCode = env.app.warehouseService
                .get(users.admin, warehouseId).code();
        long productId = env.app.productService.getBySku(users.admin, sku).id();

        // reserve 20 units via a confirmed sales order
        var customer = fixtures.customer(users.admin, fixtures.unique("CUS"));
        env.app.salesOrderService.create(users.admin,
                new SalesDtos.CreateRequest(customer.code(), warehouseCode, null,
                        null,
                        List.of(new SalesDtos.LineRequest(sku,
                                new BigDecimal("20"), new BigDecimal("15.00"),
                                BigDecimal.ZERO, null))));
        var orders = env.app.salesOrderService.search(users.admin, null, null, null,
                null, null, com.ims.common.util.PageRequest.of(1, 10, null, null));
        long soId = orders.content().get(0).id();
        env.app.salesOrderService.confirm(users.admin, soId);

        Response created = env.app.stocktakeService.create(users.admin,
                new CreateRequest(warehouseCode, "Undercut", false, null));
        env.app.stocktakeService.open(users.admin, created.id());
        // counting 5 would take on-hand below the 20 reserved
        env.app.stocktakeService.submitCount(users.admin, created.id(),
                new CountRequest(sku, new BigDecimal("5")));
        env.app.stocktakeService.close(users.admin, created.id());

        // the invariant machinery must reject the posting outright
        assertThrows(InsufficientStockException.class,
                () -> env.app.stocktakeService.post(users.admin, created.id()));
        // stocktake is still REVIEW (transaction rolled back atomically)
        assertEquals(Stocktake.STATUS_REVIEW, env.app.stocktakeService
                .get(users.admin, created.id()).status());
        // and nothing was adjusted
        assertEquals(new BigDecimal("30.000"),
                env.app.stockQueryService.totalOnHand(users.admin, productId));
    }

    @Test
    void everyActionFromEveryStateAssertedExactly() {
        freshIsolatedState();
        String sku = fixtures.unique("MTX");
        long warehouseId = warehouseWithStock(sku, 10,
                fixtures.unique("MTY"), 10);
        String warehouseCode = env.app.warehouseService
                .get(users.admin, warehouseId).code();

        // every (state, action) pair gets a FRESH stocktake in that state,
        // so one legal action cannot mask another
        for (String state : List.of("DRAFT", "OPEN", "REVIEW", "POSTED",
                "CANCELLED")) {
            for (String action : List.of("open", "count", "close", "post",
                    "cancel")) {
                long id = stocktakeInState(warehouseCode, sku, state);
                boolean legal = switch (action) {
                    case "open" -> "DRAFT".equals(state);
                    case "count" -> "OPEN".equals(state);
                    case "close" -> "OPEN".equals(state);
                    case "post" -> "REVIEW".equals(state);
                    case "cancel" -> List.of("DRAFT", "OPEN", "REVIEW")
                            .contains(state);
                    default -> false;
                };
                assertMatrix(state + "/" + action, legal, () -> {
                    switch (action) {
                        case "open" -> env.app.stocktakeService
                                .open(users.admin, id);
                        case "count" -> env.app.stocktakeService
                                .submitCount(users.admin, id,
                                        new CountRequest(sku, new BigDecimal("10")));
                        case "close" -> env.app.stocktakeService
                                .close(users.admin, id);
                        case "post" -> env.app.stocktakeService
                                .post(users.admin, id);
                        case "cancel" -> env.app.stocktakeService
                                .cancel(users.admin, id);
                        default -> throw new IllegalStateException(action);
                    }
                });
            }
        }
    }

    /** Create a stocktake and walk it into the requested state. */
    private long stocktakeInState(String warehouseCode, String sku,
                                  String state) {
        Response created = env.app.stocktakeService.create(users.admin,
                new CreateRequest(warehouseCode, "matrix " + state + " "
                        + System.nanoTime(), false, null));
        long id = created.id();
        switch (state) {
            case "OPEN" -> env.app.stocktakeService.open(users.admin, id);
            case "REVIEW" -> {
                env.app.stocktakeService.open(users.admin, id);
                env.app.stocktakeService.submitCount(users.admin, id,
                        new CountRequest(sku, new BigDecimal("10")));
                env.app.stocktakeService.close(users.admin, id);
            }
            case "POSTED" -> {
                env.app.stocktakeService.open(users.admin, id);
                env.app.stocktakeService.submitCount(users.admin, id,
                        new CountRequest(sku, new BigDecimal("10")));
                env.app.stocktakeService.close(users.admin, id);
                env.app.stocktakeService.post(users.admin, id);
            }
            case "CANCELLED" -> env.app.stocktakeService.cancel(users.admin, id);
            default -> { /* DRAFT */ }
        }
        return id;
    }
    @Test
    void permissionsFollowStockRoles() {
        freshIsolatedState();
        String sku = fixtures.unique("PRM");
        long warehouseId = warehouseWithStock(sku, 10,
                fixtures.unique("PRN"), 10);
        String warehouseCode = env.app.warehouseService
                .get(users.admin, warehouseId).code();

        // viewer cannot create or count; staff cannot post (STOCK_ADJUST)
        assertThrows(ForbiddenException.class, () -> env.app.stocktakeService
                .create(users.viewer, new CreateRequest(warehouseCode, "x", false, null)));
        Response created = env.app.stocktakeService.create(users.staff,
                new CreateRequest(warehouseCode, "staff count", false, null));
        env.app.stocktakeService.open(users.staff, created.id());
        env.app.stocktakeService.submitCount(users.staff, created.id(),
                new CountRequest(sku, new BigDecimal("10")));
        env.app.stocktakeService.close(users.staff, created.id());
        assertThrows(ForbiddenException.class,
                () -> env.app.stocktakeService.post(users.staff, created.id()));
        // but reading is open to every role
        assertNotNull(env.app.stocktakeService.get(users.viewer, created.id()));
    }

    @Test
    void validationRejectsBadInput() {
        freshIsolatedState();
        assertThrows(ValidationException.class, () -> env.app.stocktakeService
                .create(users.admin, new CreateRequest("NOPE", "x", false, null)));
        String sku = fixtures.unique("VAL");
        long warehouseId = warehouseWithStock(sku, 10,
                fixtures.unique("VAX"), 10);
        String warehouseCode = env.app.warehouseService
                .get(users.admin, warehouseId).code();
        Response created = env.app.stocktakeService.create(users.admin,
                new CreateRequest(warehouseCode, "validations", false, null));
        env.app.stocktakeService.open(users.admin, created.id());
        // negative count
        assertThrows(ValidationException.class,
                () -> env.app.stocktakeService.submitCount(users.admin, created.id(),
                        new CountRequest(sku, new BigDecimal("-1"))));
        // unknown sku
        assertThrows(ValidationException.class,
                () -> env.app.stocktakeService.submitCount(users.admin, created.id(),
                        new CountRequest("NO-SUCH-SKU", BigDecimal.TEN)));
    }

    private LineResponse lineOf(long stocktakeId, String sku) {
        return env.app.stocktakeService.get(users.admin, stocktakeId).lines()
                .stream().filter(line -> sku.equals(line.sku()))
                .findFirst().orElseThrow();
    }

    private void assertMatrix(String action, boolean legal, Runnable call) {
        if (legal) {
            call.run();
        } else {
            assertThrows(IllegalStateTransitionException.class, call::run,
                    action + " must be rejected");
        }
    }
}
