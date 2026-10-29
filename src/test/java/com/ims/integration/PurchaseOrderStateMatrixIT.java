package com.ims.integration;

import com.ims.common.exception.ApiException;
import com.ims.domain.purchase.PurchaseOrder;
import com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.LineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveLineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.Response;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive state-transition matrix for purchase orders: EVERY action is
 * attempted from EVERY state; the legal set must match the specification
 * exactly. This is the "run every combination" guarantee for POs.
 */
class PurchaseOrderStateMatrixIT {

    static final Set<String> ACTIONS = Set.of("EDIT", "SUBMIT", "APPROVE", "CANCEL",
            "CLOSE", "RECEIVE");

    /** actions legal from each status */
    static final Map<String, Set<String>> LEGAL = Map.of(
            "DRAFT", Set.of("EDIT", "SUBMIT", "CANCEL"),
            "SUBMITTED", Set.of("CANCEL", "APPROVE"),
            "APPROVED", Set.of("CANCEL", "RECEIVE"),
            "PARTIALLY_RECEIVED", Set.of("RECEIVE", "CANCEL_RECEIPTS_EXIST"), // cancel illegal
            "RECEIVED", Set.of("CLOSE"),
            "CLOSED", Set.of(),
            "CANCELLED", Set.of());

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;
    static String supplierCode;
    static String warehouseCode;
    static String sku;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        fx.category(users.manager, "PM-CAT", null);
        supplierCode = "PM-SUP";
        fx.supplier(users.manager, supplierCode);
        warehouseCode = "PM-WH";
        fx.warehouse(users.manager, warehouseCode);
        sku = "PM-SKU";
        fx.product(users.manager, sku, "PM-CAT", new BigDecimal("10.00"),
                new BigDecimal("20.00"), BigDecimal.ZERO);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    /** Drive a fresh PO into the requested status via its legal path. */
    private Response poInState(String status) {
        Response po = env.app.purchaseOrderService.create(users.manager,
                new CreateRequest(supplierCode, warehouseCode, null, null, null, null,
                        List.of(new LineRequest(sku, new BigDecimal("10"),
                                new BigDecimal("10.00"), null))));
        switch (status) {
            case "DRAFT" -> {
                return po;
            }
            case "SUBMITTED" -> {
                return env.app.purchaseOrderService.submit(users.manager, po.id());
            }
            case "APPROVED" -> {
                env.app.purchaseOrderService.submit(users.manager, po.id());
                return env.app.purchaseOrderService.approve(users.manager, po.id());
            }
            case "PARTIALLY_RECEIVED" -> {
                env.app.purchaseOrderService.submit(users.manager, po.id());
                env.app.purchaseOrderService.approve(users.manager, po.id());
                long lineId = env.app.purchaseOrderService.get(users.manager, po.id())
                        .lines().get(0).id();
                env.app.receivingService.receive(users.manager, po.id(),
                        new ReceiveRequest(List.of(new ReceiveLineRequest(lineId,
                                new BigDecimal("4"), BigDecimal.ZERO)), null));
                return env.app.purchaseOrderService.get(users.manager, po.id());
            }
            case "RECEIVED" -> {
                env.app.purchaseOrderService.submit(users.manager, po.id());
                env.app.purchaseOrderService.approve(users.manager, po.id());
                long lineId = env.app.purchaseOrderService.get(users.manager, po.id())
                        .lines().get(0).id();
                env.app.receivingService.receive(users.manager, po.id(),
                        new ReceiveRequest(List.of(new ReceiveLineRequest(lineId,
                                new BigDecimal("10"), BigDecimal.ZERO)), null));
                return env.app.purchaseOrderService.get(users.manager, po.id());
            }
            case "CLOSED" -> {
                po = poInState("RECEIVED");
                return env.app.purchaseOrderService.close(users.manager, po.id());
            }
            case "CANCELLED" -> {
                env.app.purchaseOrderService.submit(users.manager, po.id());
                return env.app.purchaseOrderService.cancel(users.manager, po.id());
            }
            default -> throw new IllegalArgumentException(status);
        }
    }

    private String attempt(String action, Response po) {
        try {
            switch (action) {
                case "EDIT" -> env.app.purchaseOrderService.update(users.manager,
                        po.id(), new com.ims.domain.purchase.dto.PurchaseDtos.UpdateRequest(
                                supplierCode, warehouseCode, null, null, null,
                                List.of(new LineRequest(sku, new BigDecimal("11"),
                                        new BigDecimal("10.00"), null))));
                case "SUBMIT" -> env.app.purchaseOrderService.submit(users.manager,
                        po.id());
                case "APPROVE" -> env.app.purchaseOrderService.approve(users.manager,
                        po.id());
                case "CANCEL" -> env.app.purchaseOrderService.cancel(users.manager,
                        po.id());
                case "CLOSE" -> env.app.purchaseOrderService.close(users.manager,
                        po.id());
                case "RECEIVE" -> {
                    long lineId = env.app.purchaseOrderService.get(users.manager,
                            po.id()).lines().get(0).id();
                    env.app.receivingService.receive(users.manager, po.id(),
                            new ReceiveRequest(List.of(new ReceiveLineRequest(lineId,
                                    BigDecimal.ONE, BigDecimal.ZERO)), null));
                }
                default -> throw new IllegalArgumentException(action);
            }
            return "OK";
        } catch (ApiException e) {
            // 409 = illegal transition, 422 = business rule rejection
            // (e.g. receiving beyond outstanding): both mean "rejected here"
            return (e.httpStatus() == 409 || e.httpStatus() == 422)
                    ? "ILLEGAL" : "ERROR(" + e.httpStatus() + ")";
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "SUBMITTED", "APPROVED", "PARTIALLY_RECEIVED",
            "RECEIVED", "CLOSED", "CANCELLED"})
    void everyActionFromEveryState(String status) {
        Set<String> legal = LEGAL.get(status);
        for (String action : ACTIONS) {
            Response po = poInState(status);
            String outcome = attempt(action, po);
            String statusAfter = env.app.purchaseOrderService
                    .get(users.viewer, po.id()).status();

            if (legal.contains(action)) {
                assertEquals("OK", outcome,
                        action + " must be legal from " + status);
                assertTrue(!statusAfter.equals(status) || "EDIT".equals(action)
                                || "RECEIVE".equals(action),
                        action + " from " + status + " should move state (was "
                                + statusAfter + ")");
            } else {
                assertEquals("ILLEGAL", outcome,
                        action + " must be rejected from " + status + " (got " + outcome
                                + ")");
                assertEquals(status, statusAfter,
                        action + " from " + status + " must not change state");
            }
        }
    }

    @Test
    void cancelledPoIsTerminal() {
        Response po = poInState("CANCELLED");
        for (String action : ACTIONS) {
            assertEquals("ILLEGAL", attempt(action, po),
                    "cancelled PO must reject " + action);
        }
    }

    @Test
    void closedPoIsTerminal() {
        Response po = poInState("CLOSED");
        for (String action : ACTIONS) {
            assertEquals("ILLEGAL", attempt(action, po),
                    "closed PO must reject " + action);
        }
    }

    @Nested
    class SequenceTests {

        @Test
        void approveThenCancelThenReceiveRejected() {
            Response po = poInState("APPROVED");
            env.app.purchaseOrderService.cancel(users.manager, po.id());
            assertEquals("ILLEGAL", attempt("RECEIVE", po));
        }

        @Test
        void submitEditRejectedButDraftEditFine() {
            assertEquals("OK", attempt("EDIT", poInState("DRAFT")));
            assertEquals("ILLEGAL", attempt("EDIT", poInState("SUBMITTED")));
        }

        @Test
        void receiveAfterFullReceiveRejected() {
            Response po = poInState("RECEIVED");
            assertEquals("ILLEGAL", attempt("RECEIVE", po));
        }
    }
}
