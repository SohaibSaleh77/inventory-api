package com.ims.unit;

import com.ims.domain.webhook.WebhookClient;
import com.ims.domain.webhook.WebhookEvents;
import com.ims.domain.webhook.WebhookService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Webhook primitives: signatures, event sets, backoff schedule. */
class WebhookTest {

    @Test
    void signatureIsStableHmacAndVerifiable() {
        String secret = "0123456789abcdef0123456789abcdef";
        String payload = "{\"eventType\":\"TEST\"}";
        String signature = WebhookClient.signature(secret, payload);
        assertTrue(signature.startsWith("sha256="));
        assertEquals(7 + 64, signature.length());
        // deterministic
        assertEquals(signature, WebhookClient.signature(secret, payload));
        // secret material changes the signature
        assertFalse(signature.equals(WebhookClient.signature("another-secret-value!", payload)));
        // verification helper
        assertTrue(WebhookClient.signatureMatches(signature,
                WebhookClient.signature(secret, payload)));
        assertFalse(WebhookClient.signatureMatches(signature,
                WebhookClient.signature(secret, payload + " ")));
        assertFalse(WebhookClient.signatureMatches(signature, null));
    }

    @Test
    void eventSetParsesAndValidates() {
        Set<String> events = WebhookEvents.parse(" STOCK_LOW , TEST ,,");
        assertEquals(Set.of("STOCK_LOW", "TEST"), events);
        assertEquals("STOCK_LOW,TEST", WebhookEvents.render(events));
        assertTrue(WebhookEvents.allKnown(events));
        assertFalse(WebhookEvents.allKnown(Set.of("STOCK_LOW", "NOT_A_THING")));
        assertTrue(WebhookEvents.parse(null).isEmpty());
        assertTrue(WebhookEvents.parse("  ").isEmpty());
        assertTrue(WebhookEvents.all().containsAll(
                List.of("STOCK_LOW", "PURCHASE_ORDER_APPROVED", "SALES_ORDER_CONFIRMED",
                        "SALES_ORDER_SHIPPED", "STOCKTAKE_POSTED", "TEST")));
    }

    @Test
    void backoffDoublesAndCaps() {
        WebhookService service = new WebhookService(null, null, null,
                new WebhookClient(Duration.ofSeconds(1)), 6);
        assertCloseTo(Duration.ofMinutes(1), step(service, 1));
        assertCloseTo(Duration.ofMinutes(2), step(service, 2));
        assertCloseTo(Duration.ofMinutes(4), step(service, 3));
        assertCloseTo(Duration.ofMinutes(8), step(service, 4));
        assertCloseTo(Duration.ofMinutes(16), step(service, 5));
        assertCloseTo(Duration.ofMinutes(32), step(service, 6));
        assertCloseTo(Duration.ofMinutes(64), step(service, 7));
        assertCloseTo(Duration.ofHours(6), step(service, 99));
    }

    private Duration step(WebhookService service, int attempt) {
        Instant before = Instant.now();
        Instant until = service.backoffUntil(attempt);
        return Duration.between(before, until).abs();
    }

    private static void assertCloseTo(Duration expected, Duration actual) {
        Duration slop = Duration.ofSeconds(5);
        assertTrue(actual.compareTo(expected.minus(slop)) >= 0
                        && actual.compareTo(expected.plus(slop)) <= 0,
                "expected ~" + expected + " but was " + actual);
    }
}
