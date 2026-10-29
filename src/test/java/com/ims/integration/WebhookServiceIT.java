package com.ims.integration;

import com.ims.common.exception.ForbiddenException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.webhook.WebhookClient;
import com.ims.domain.webhook.WebhookDelivery;
import com.ims.domain.webhook.WebhookDispatcher;
import com.ims.domain.webhook.WebhookEvents;
import com.ims.domain.webhook.WebhookService;
import com.ims.domain.webhook.dto.WebhookDtos.CreateRequest;
import com.ims.domain.webhook.dto.WebhookDtos.DeliveryResponse;
import com.ims.domain.webhook.dto.WebhookDtos.Response;
import com.ims.domain.webhook.dto.WebhookDtos.RetryResponse;
import com.ims.domain.webhook.dto.WebhookDtos.TestEventResponse;
import com.ims.domain.webhook.dto.WebhookDtos.UpdateRequest;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhooks against a real database and a real local HTTP receiver:
 * outbox enqueue inside business transactions, HMAC-signed delivery,
 * retry/backoff, operator retry, and STOCK_LOW crossing semantics.
 */
class WebhookServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fixtures;
    static HttpServer receiver;
    static final ConcurrentLinkedQueue<Received> received =
            new ConcurrentLinkedQueue<>();

    /** What the test receiver saw: method, path, headers, body. */
    record Received(String method, String path, Map<String, String> headers,
                    String body) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    @BeforeAll
    static void setUp() throws IOException {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fixtures = new Fixtures(env.app);

        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) ->
                    headers.put(k.toLowerCase(), v.get(0)));
            received.add(new Received(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), headers, body));
            if (exchange.getRequestURI().getPath().endsWith("/fail")) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(ok);
                }
            }
        });
        receiver.start();
        IntegrationTestEnv.touch();
    }

    @AfterAll
    static void tearDown() {
        receiver.stop(0);
        env.truncateAll();
    }

    /** Drain any leftover outbox rows from earlier tests, then reset capture. */
    private void drainAndReset() {
        env.app.webhookService.deliverDue(100);
        received.clear();
    }

    private long countReceived(String eventType) {
        return received.stream()
                .filter(call -> eventType.equals(call.header("X-IMS-Event")))
                .count();
    }

    private String okUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    private String failUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook/fail";
    }

    /** A webhook service with a 1-attempt budget for fast exhaustion testing. */
    private WebhookService quickRetryService() {
        return new WebhookService(env.transactions, env.app.webhookDao,
                env.app.auditLogger, new WebhookClient(Duration.ofSeconds(2)), 1);
    }

    private Response register(String url, String... events) {
        return env.app.webhookService.create(users.admin,
                new CreateRequest("hook-" + UUID.randomUUID(), url,
                        "itest-secret-0123456789", List.of(events)));
    }

    @Test
    void deliversSignedEventsToRealEndpoint() {
        drainAndReset();
        Response webhook = register(okUrl(), WebhookEvents.TEST);

        TestEventResponse test = env.app.webhookService
                .sendTestEvent(users.admin, webhook.id());
        assertNotNull(test.deliveryId());

        int completed = env.app.webhookService.deliverDue(10);
        assertEquals(1, completed);
        assertEquals(1, countReceived(WebhookEvents.TEST));

        Received call = received.stream()
                .filter(r -> WebhookEvents.TEST.equals(r.header("X-IMS-Event")))
                .findFirst().orElseThrow();
        assertEquals("POST", call.method());
        assertEquals("/hook", call.path());
        assertEquals(String.valueOf(test.deliveryId()),
                call.header("X-IMS-Delivery-Id"));
        // signature matches the shared secret over the exact body
        String expected = WebhookClient.signature("itest-secret-0123456789",
                call.body());
        assertEquals(expected, call.header("X-IMS-Signature"));
        assertTrue(call.body().contains("\"eventType\":\"TEST\""));
        assertTrue(call.body().contains("\"sentBy\":\"admin\""));
        assertTrue(call.body().contains("inventory-api webhook test event"));

        // delivery row is SUCCESS with the HTTP status recorded
        var page = env.app.webhookService.deliveries(users.admin, webhook.id(),
                null, com.ims.common.util.PageRequest.of(1, 10, null, null));
        assertEquals(1, page.totalElements());
        DeliveryResponse delivery = page.content().get(0);
        assertEquals(WebhookDelivery.SUCCESS, delivery.status());
        assertEquals(1, delivery.attempts());
        assertEquals(200, delivery.lastStatusCode());
        assertNotNull(delivery.deliveredAt());
        assertNull(delivery.lastError());
    }

    @Test
    void businessEventsLandInOutboxAndFlowToSubscribers() {
        drainAndReset();
        Response webhook = register(okUrl(), WebhookEvents.PURCHASE_ORDER_APPROVED);

        // procure-to-pay: PO create -> submit -> approve (approval enqueues)
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        var product = fixtures.product(users.admin, fixtures.unique("PRA"),
                category.code(), new BigDecimal("4.00"), new BigDecimal("8.00"),
                BigDecimal.ZERO);
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        var po = env.app.purchaseOrderService.create(users.admin,
                new com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest(
                        supplier.code(), warehouse.code(), null, null, null, null,
                        List.of(new com.ims.domain.purchase.dto.PurchaseDtos.LineRequest(
                                product.sku(), BigDecimal.TEN,
                                new BigDecimal("4.00"), BigDecimal.ZERO))));
        env.app.purchaseOrderService.submit(users.admin, po.id());
        env.app.purchaseOrderService.approve(users.admin, po.id());

        // outbox row exists BEFORE any delivery attempt (transactional outbox)
        var pending = env.app.webhookService.deliveries(users.admin, webhook.id(),
                "PENDING", com.ims.common.util.PageRequest.of(1, 10, null, null));
        assertEquals(1, pending.totalElements());
        assertTrue(pending.content().get(0).payload()
                .contains("\"poNo\":\"" + po.poNo() + "\""));

        env.app.webhookService.deliverDue(10);
        assertEquals(1, countReceived(WebhookEvents.PURCHASE_ORDER_APPROVED));
        Received event = received.stream()
                .filter(r -> WebhookEvents.PURCHASE_ORDER_APPROVED
                        .equals(r.header("X-IMS-Event")))
                .findFirst().orElseThrow();
        assertTrue(event.body().contains("\"poId\":" + po.id()));
        assertTrue(event.body().contains("\"poNo\":\"" + po.poNo() + "\""));
    }

    @Test
    void failingEndpointRetriesThenParksFailedAndOperatorCanRetry() {
        drainAndReset();
        WebhookService quick = quickRetryService();
        Response webhook = env.app.webhookService.create(users.admin,
                new CreateRequest("flaky-" + UUID.randomUUID(), failUrl(),
                        "itest-secret-0123456789", List.of(WebhookEvents.TEST)));
        env.app.webhookService.sendTestEvent(users.admin, webhook.id());

        // 1-attempt budget: the first failure is terminal -> FAILED
        assertEquals(1, quick.deliverDue(10));
        DeliveryResponse delivery = env.app.webhookService.deliveries(users.admin,
                webhook.id(), "FAILED",
                com.ims.common.util.PageRequest.of(1, 10, null, null))
                .content().get(0);
        assertEquals(1, delivery.attempts());
        assertEquals(500, delivery.lastStatusCode());
        assertNotNull(delivery.lastError());

        // operator retry resets the row to PENDING
        RetryResponse retried = env.app.webhookService.retryDelivery(users.admin,
                webhook.id(), delivery.id());
        assertEquals(WebhookDelivery.PENDING, retried.status());

        // the re-queued delivery fails terminally once more
        assertEquals(1, quick.deliverDue(10));
        assertEquals(1, env.app.webhookService.deliveries(users.admin,
                webhook.id(), "FAILED",
                com.ims.common.util.PageRequest.of(1, 20, null, null)).totalElements());

        // foreign delivery ids and non-FAILED states are rejected
        Response other = register(okUrl(), WebhookEvents.TEST);
        TestEventResponse otherEvent = env.app.webhookService
                .sendTestEvent(users.admin, other.id());
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .retryDelivery(users.admin, webhook.id(), otherEvent.deliveryId()));
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .retryDelivery(users.admin, other.id(), otherEvent.deliveryId()));
        assertThrows(com.ims.common.exception.NotFoundException.class,
                () -> env.app.webhookService.retryDelivery(users.admin,
                        webhook.id(), otherEvent.deliveryId() + 1_000_000));
    }

    @Test
    void failingEndpointReschedulesWithBackoff() {
        drainAndReset();
        // the production service holds a 6-attempt budget
        Response webhook = env.app.webhookService.create(users.admin,
                new CreateRequest("backoff-" + UUID.randomUUID(), failUrl(),
                        "itest-secret-0123456789", List.of(WebhookEvents.TEST)));
        env.app.webhookService.sendTestEvent(users.admin, webhook.id());

        assertEquals(0, env.app.webhookService.deliverDue(10));
        DeliveryResponse delivery = env.app.webhookService.deliveries(users.admin,
                webhook.id(), null,
                com.ims.common.util.PageRequest.of(1, 10, null, null))
                .content().get(0);
        assertEquals(WebhookDelivery.PENDING, delivery.status());
        assertEquals(1, delivery.attempts());
        assertEquals(500, delivery.lastStatusCode());
        // not due again for ~1 minute (exponential backoff)
        assertNotNull(delivery.nextAttemptAt());
        assertTrue(delivery.nextAttemptAt().isAfter(java.time.Instant.now()),
                "failed delivery must be rescheduled in the future");

        // forcing it due runs attempt 2, still inside the budget
        long deliveryId = delivery.id();
        env.transactions.withConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "UPDATE webhook_deliveries SET next_attempt_at = NOW() "
                            + "WHERE id = ?")) {
                ps.setLong(1, deliveryId);
                ps.executeUpdate();
                return null;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(0, env.app.webhookService.deliverDue(10));
        delivery = env.app.webhookService.deliveries(users.admin, webhook.id(),
                null, com.ims.common.util.PageRequest.of(1, 10, null, null))
                .content().get(0);
        assertEquals(WebhookDelivery.PENDING, delivery.status());
        assertEquals(2, delivery.attempts());
    }

    @Test
    void stockLowFiresOnDownwardCrossingOnly() {
        drainAndReset();
        Response webhook = register(okUrl(), WebhookEvents.STOCK_LOW);

        // product with reorder point 10; stock it to 30 (above threshold)
        var warehouse = fixtures.warehouse(users.admin, fixtures.unique("WH"));
        var category = fixtures.category(users.admin, fixtures.unique("CAT"), null);
        String sku = fixtures.unique("LOW");
        fixtures.product(users.admin, sku, category.code(),
                new BigDecimal("3.00"), new BigDecimal("6.00"),
                new BigDecimal("10"));
        var supplier = fixtures.supplier(users.admin, fixtures.unique("SUP"));
        fixtures.receiveStock(users.admin, supplier.code(), warehouse.code(),
                sku, 30, new BigDecimal("3.00"));

        // stocking up from 0 does not cross downward: nothing fires
        env.app.webhookService.deliverDue(10);
        assertEquals(0, countReceived(WebhookEvents.STOCK_LOW));

        // ship 25 via a sales order: available 30 -> 5 crosses below 10
        var customer = fixtures.customer(users.admin, fixtures.unique("CUS"));
        var so = env.app.salesOrderService.create(users.admin,
                new com.ims.domain.sales.dto.SalesDtos.CreateRequest(
                        customer.code(), warehouse.code(), null, null,
                        List.of(new com.ims.domain.sales.dto.SalesDtos.LineRequest(
                                sku, new BigDecimal("25"), new BigDecimal("6.00"),
                                BigDecimal.ZERO, null))));
        var soDetail = env.app.salesOrderService.get(users.admin, so.id());
        env.app.salesOrderService.confirm(users.admin, so.id());
        env.app.fulfillmentService.ship(users.admin, so.id(),
                new com.ims.domain.sales.dto.SalesDtos.ShipRequest(
                        List.of(new com.ims.domain.sales.dto.SalesDtos.ShipLineRequest(
                                soDetail.lines().get(0).id(), new BigDecimal("25"))),
                        null, null, null));

        // exactly one crossing event, even though the product stays below
        env.app.webhookService.deliverDue(10);
        assertEquals(1, countReceived(WebhookEvents.STOCK_LOW));
        Received event = received.stream()
                .filter(r -> WebhookEvents.STOCK_LOW.equals(r.header("X-IMS-Event")))
                .findFirst().orElseThrow();
        assertTrue(event.body().contains("\"sku\":\"" + sku + "\""));
        assertTrue(event.body().contains("\"reorderPoint\":10"));
        assertTrue(event.body().contains("\"qtyAvailable\":5"));
    }

    @Test
    void dispatcherDrainsDueOutbox() {
        drainAndReset();
        // the scheduler entry point is a plain synchronous pass
        WebhookDispatcher dispatcher = new WebhookDispatcher(env.app.webhookService);
        Response webhook = register(okUrl(), WebhookEvents.TEST);
        TestEventResponse test = env.app.webhookService
                .sendTestEvent(users.admin, webhook.id());
        dispatcher.runOnce();
        assertEquals(1, received.stream().filter(call -> String
                .valueOf(test.deliveryId()).equals(call.header("X-IMS-Delivery-Id")))
                .count());
        dispatcher.close();
    }

    @Test
    void crudValidatesAndAudits() {
        drainAndReset();
        // bad url scheme
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .create(users.admin, new CreateRequest("bad1", "ftp://x/y",
                        "itest-secret-0123456789", List.of(WebhookEvents.TEST))));
        // short secret
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .create(users.admin, new CreateRequest("bad2", okUrl(), "short",
                        List.of(WebhookEvents.TEST))));
        // unknown event
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .create(users.admin, new CreateRequest("bad3", okUrl(),
                        "itest-secret-0123456789", List.of("NOT_AN_EVENT"))));
        // empty event list
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .create(users.admin, new CreateRequest("bad4", okUrl(),
                        "itest-secret-0123456789", List.of())));
        // duplicate name
        String name = "dup-" + UUID.randomUUID();
        register(okUrl(), WebhookEvents.TEST);
        Response named = env.app.webhookService.create(users.admin,
                new CreateRequest(name, okUrl(), "itest-secret-0123456789",
                        List.of(WebhookEvents.TEST)));
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .create(users.admin, new CreateRequest(name, okUrl(),
                        "itest-secret-0123456789", List.of(WebhookEvents.TEST))));
        // RBAC: staff/viewer hold no webhook permissions
        assertThrows(ForbiddenException.class, () -> env.app.webhookService
                .create(users.staff, new CreateRequest("nope", okUrl(),
                        "itest-secret-0123456789", List.of(WebhookEvents.TEST))));
        assertThrows(ForbiddenException.class, () -> env.app.webhookService
                .list(users.viewer));

        // happy path + update + delete
        assertTrue(named.isActive());
        Response updated = env.app.webhookService.update(users.manager,
                named.id(), new UpdateRequest(named.name(), failUrl(),
                        "itest-secret-0123456789",
                        List.of(WebhookEvents.STOCK_LOW), false));
        assertFalse(updated.isActive());
        assertEquals(failUrl(), updated.url());
        assertEquals(List.of(WebhookEvents.STOCK_LOW), updated.events());

        // inactive webhooks receive nothing - not even test events
        assertThrows(ValidationException.class, () -> env.app.webhookService
                .sendTestEvent(users.manager, named.id()));
        var deliveries = env.app.webhookService.deliveries(users.manager,
                named.id(), null,
                com.ims.common.util.PageRequest.of(1, 10, null, null));
        assertEquals(0, deliveries.totalElements());

        env.app.webhookService.delete(users.admin, named.id());
        assertThrows(com.ims.common.exception.NotFoundException.class,
                () -> env.app.webhookService.get(users.admin, named.id()));
    }
}
