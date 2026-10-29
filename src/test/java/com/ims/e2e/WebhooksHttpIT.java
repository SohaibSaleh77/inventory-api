package com.ims.e2e;

import com.ims.domain.webhook.WebhookClient;
import com.sun.net.httpserver.HttpServer;
import com.ims.testing.ApiTestServer;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Webhook management and delivery inspection over the real HTTP API. */
class WebhooksHttpIT {

    static IntegrationTestEnv env;
    static ApiTestServer api;
    static String adminToken;
    static String staffToken;
    static HttpServer receiver;
    static final ConcurrentLinkedQueue<Received> received =
            new ConcurrentLinkedQueue<>();

    record Received(Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    @BeforeAll
    static void setUp() throws IOException {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        TestUsers users = TestUsers.create(env);
        api = env.api();
        adminToken = api.login(users.adminUsername, TestUsers.PASSWORD);
        staffToken = api.login(users.staffUsername, TestUsers.PASSWORD);

        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/sink", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) ->
                    headers.put(k.toLowerCase(), v.get(0)));
            received.add(new Received(headers, body));
            exchange.sendResponseHeaders(204, -1);
        });
        receiver.start();
        IntegrationTestEnv.touch();
    }

    @AfterAll
    static void tearDown() {
        receiver.stop(0);
        env.truncateAll();
    }

    private String sinkUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/sink";
    }

    @Test
    void fullHttpFlow() {
        env.app.webhookService.deliverDue(100);
        received.clear();

        // create
        var create = api.post("/api/webhooks", adminToken, Map.of(
                "name", "e2e-" + UUID.randomUUID(),
                "url", sinkUrl(),
                "secret", "e2e-secret-0123456789",
                "events", List.of("TEST", "STOCKTAKE_POSTED")));
        assertEquals(201, create.status());
        assertEquals(true, create.json().get("isActive").asBoolean());
        assertEquals(2, create.json().get("events").size());
        long id = create.json().get("id").asLong();

        // list + get (ETag on detail)
        assertEquals(200, api.get("/api/webhooks", adminToken).status());
        var detail = api.get("/api/webhooks/" + id, adminToken);
        assertEquals(200, detail.status());
        assertTrue(detail.header("etag") != null);
        // 304 on revalidation
        var revalidated = api.send("GET", "/api/webhooks/" + id, adminToken, null,
                Map.of("If-None-Match", detail.header("etag")));
        assertEquals(304, revalidated.status());

        // staff is locked out
        assertEquals(403, api.get("/api/webhooks/" + id, staffToken).status());
        assertEquals(403, api.post("/api/webhooks/" + id + "/test", staffToken,
                Map.of()).status());

        // send a test event over HTTP
        var test = api.post("/api/webhooks/" + id + "/test", adminToken, Map.of());
        assertEquals(200, test.status());
        assertTrue(test.json().get("deliveryId").asLong() > 0);

        // the delivery shows up as PENDING in the outbox
        var pending = api.get("/api/webhooks/" + id + "/deliveries?status=PENDING",
                adminToken);
        assertEquals(200, pending.status());
        assertEquals(1, pending.json().get("totalElements").asInt());

        // drain via the service entry point (the dispatcher would do this)
        env.app.webhookService.deliverDue(100);
        assertEquals(1, received.size());
        Received call = received.peek();
        assertEquals("TEST", call.header("X-IMS-Event"));
        assertEquals(String.valueOf(test.json().get("deliveryId").asLong()),
                call.header("X-IMS-Delivery-Id"));
        assertEquals(
                WebhookClient.signature("e2e-secret-0123456789", call.body()),
                call.header("X-IMS-Signature"));

        // and now SUCCESS with the recorded status code
        var deliveries = api.get("/api/webhooks/" + id + "/deliveries", adminToken);
        assertEquals(200, deliveries.status());
        assertEquals("SUCCESS", deliveries.json().get("content").get(0)
                .get("status").asText());
        assertEquals(204, deliveries.json().get("content").get(0)
                .get("lastStatusCode").asInt());

        // update: pause the webhook
        var update = api.put("/api/webhooks/" + id, adminToken, Map.of(
                "name", create.field("name"),
                "url", sinkUrl(),
                "secret", "e2e-secret-0123456789",
                "events", List.of("TEST"),
                "isActive", false));
        assertEquals(200, update.status());
        assertEquals(false, update.json().get("isActive").asBoolean());

        // delete
        assertEquals(204, api.delete("/api/webhooks/" + id, adminToken).status());
        assertEquals(404, api.get("/api/webhooks/" + id, adminToken).status());
    }
}
