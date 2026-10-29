package com.ims.integration;

import com.ims.common.exception.ApiException;
import com.ims.domain.sales.dto.SalesDtos.CreateRequest;
import com.ims.domain.sales.dto.SalesDtos.LineRequest;
import com.ims.domain.sales.dto.SalesDtos.Response;
import com.ims.domain.sales.dto.SalesDtos.ReturnLineRequest;
import com.ims.domain.sales.dto.SalesDtos.ReturnRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipLineRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipRequest;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exhaustive state-transition matrix for sales orders: EVERY action attempted
 * from EVERY state, exactly like the PO matrix.
 */
class SalesOrderStateMatrixIT {

    static final Set<String> ACTIONS = Set.of("EDIT", "CONFIRM", "CANCEL", "SHIP",
            "CLOSE", "RETURN");

    static final Map<String, Set<String>> LEGAL = Map.of(
            "DRAFT", Set.of("EDIT", "CONFIRM", "CANCEL"),
            "CONFIRMED", Set.of("CANCEL", "SHIP"),
            "PARTIALLY_FULFILLED", Set.of("SHIP", "RETURN"), // cancel illegal once shipped
            "FULFILLED", Set.of("CLOSE", "RETURN"),
            "CLOSED", Set.of("RETURN"),
            "CANCELLED", Set.of());

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;
    static String customerCode;
    static String warehouseCode;
    static String sku;
    static int orderSeq = 0;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        fx.category(users.manager, "SM-CAT", null);
        fx.supplier(users.manager, "SM-SUP");
        warehouseCode = "SM-WH";
        fx.warehouse(users.manager, warehouseCode);
        customerCode = "SM-CUS";
        fx.customer(users.manager, customerCode);
        sku = "SM-SKU";
        fx.product(users.manager, sku, "SM-CAT", new BigDecimal("10.00"),
                new BigDecimal("20.00"), BigDecimal.ZERO);
        // plenty of stock
        fx.receiveStock(users.manager, "SM-SUP", warehouseCode, sku, 10000,
                new BigDecimal("10.00"));
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    private Response soInState(String status) {
        Response so = env.app.salesOrderService.create(users.staff, new CreateRequest(
                customerCode, warehouseCode, null, null,
                List.of(new LineRequest(sku, new BigDecimal("10"),
                        new BigDecimal("20.00"), null, null))));
        switch (status) {
            case "DRAFT" -> {
                return so;
            }
            case "CONFIRMED" -> {
                return env.app.salesOrderService.confirm(users.staff, so.id());
            }
            case "PARTIALLY_FULFILLED" -> {
                env.app.salesOrderService.confirm(users.staff, so.id());
                ship(so, 4);
                return env.app.salesOrderService.get(users.staff, so.id());
            }
            case "FULFILLED" -> {
                env.app.salesOrderService.confirm(users.staff, so.id());
                ship(so, 10);
                return env.app.salesOrderService.get(users.staff, so.id());
            }
            case "CLOSED" -> {
                so = soInState("FULFILLED");
                return env.app.salesOrderService.close(users.staff, so.id());
            }
            case "CANCELLED" -> {
                return env.app.salesOrderService.cancel(users.staff, so.id());
            }
            default -> throw new IllegalArgumentException(status);
        }
    }

    private void ship(Response so, int qty) {
        long lineId = env.app.salesOrderService.get(users.staff, so.id())
                .lines().get(0).id();
        env.app.fulfillmentService.ship(users.staff, so.id(),
                new ShipRequest(List.of(new ShipLineRequest(lineId,
                        BigDecimal.valueOf(qty))), null, null, null));
    }

    private String attempt(String action, Response so) {
        try {
            switch (action) {
                case "EDIT" -> env.app.salesOrderService.update(users.staff, so.id(),
                        new com.ims.domain.sales.dto.SalesDtos.UpdateRequest(
                                customerCode, warehouseCode, null, null,
                                List.of(new LineRequest(sku, new BigDecimal("11"),
                                        new BigDecimal("20.00"), null, null))));
                case "CONFIRM" -> env.app.salesOrderService.confirm(users.staff,
                        so.id());
                case "CANCEL" -> env.app.salesOrderService.cancel(users.staff, so.id());
                case "CLOSE" -> env.app.salesOrderService.close(users.staff, so.id());
                case "SHIP" -> ship(so, 1);
                case "RETURN" -> {
                    long lineId = env.app.salesOrderService.get(users.staff, so.id())
                            .lines().get(0).id();
                    env.app.salesReturnService.receiveReturn(users.staff, so.id(),
                            new ReturnRequest(List.of(new ReturnLineRequest(lineId,
                                    BigDecimal.ONE, true)), "DEFECTIVE", null));
                }
                default -> throw new IllegalArgumentException(action);
            }
            return "OK";
        } catch (ApiException e) {
            // 409 = illegal transition, 422 = business rule rejection
            // (e.g. shipping beyond reserved): both mean "rejected here"
            return (e.httpStatus() == 409 || e.httpStatus() == 422)
                    ? "ILLEGAL" : "ERROR(" + e.httpStatus() + ")";
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CONFIRMED", "PARTIALLY_FULFILLED", "FULFILLED",
            "CLOSED", "CANCELLED"})
    void everyActionFromEveryState(String status) {
        Set<String> legal = LEGAL.get(status);
        for (String action : ACTIONS) {
            Response so = soInState(status);
            String outcome = attempt(action, so);
            String statusAfter = env.app.salesOrderService
                    .get(users.viewer, so.id()).status();

            if (legal.contains(action)) {
                assertEquals("OK", outcome,
                        action + " must be legal from " + status);
                assertEquals(statusAfter, statusAfter, "state may advance legally");
            } else {
                assertEquals("ILLEGAL", outcome,
                        action + " must be rejected from " + status + " (got "
                                + outcome + ")");
                assertEquals(status, statusAfter,
                        action + " from " + status + " must not change state");
            }
        }
    }

    @Test
    void cancelledOrderIsTerminal() {
        Response so = soInState("CANCELLED");
        for (String action : ACTIONS) {
            if ("RETURN".equals(action)) {
                continue; // no shipments exist to return
            }
            assertEquals("ILLEGAL", attempt(action, so),
                    "cancelled SO must reject " + action);
        }
    }

    @Test
    void confirmTwiceRejected() {
        Response so = soInState("CONFIRMED");
        assertEquals("ILLEGAL", attempt("CONFIRM", so));
    }

    @Test
    void sequenceDraftToClosedHappyPath() {
        Response so = soInState("DRAFT");
        assertEquals("OK", attempt("CONFIRM", so));
        ship(so, 10);
        assertEquals("FULFILLED",
                env.app.salesOrderService.get(users.staff, so.id()).status());
        assertEquals("OK", attempt("CLOSE", so));
        assertEquals("CLOSED",
                env.app.salesOrderService.get(users.staff, so.id()).status());
    }
}
