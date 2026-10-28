package com.ims.domain.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * The HTTP leg of webhook delivery: POST the stored payload with an
 * HMAC-SHA256 signature header so receivers can authenticate the call.
 *
 * <p>Headers on every delivery:
 * <ul>
 *   <li>{@code Content-Type: application/json}</li>
 *   <li>{@code X-IMS-Event}: the event type code</li>
 *   <li>{@code X-IMS-Delivery-Id}: the outbox row id (idempotency handle)</li>
 *   <li>{@code X-IMS-Signature}: {@code sha256=<hex hmac of the raw body>}</li>
 * </ul>
 * Receivers recompute the HMAC over the raw request body with their shared
 * secret and compare; the delivery id makes at-least-once retries safe.
 */
public final class WebhookClient {

    /** Result of one delivery attempt. */
    public record Outcome(boolean success, Integer statusCode, String error) {
    }

    private final HttpClient client;
    private final Duration timeout;

    public WebhookClient(Duration timeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.timeout = timeout;
    }

    /**
     * POST the payload. Any 2xx status counts as delivered; anything else
     * (including transport errors) schedules a retry.
     */
    public Outcome attempt(String url, String payload, String secret,
                           long deliveryId, String eventType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-IMS-Event", eventType)
                    .header("X-IMS-Delivery-Id", String.valueOf(deliveryId))
                    .header("X-IMS-Signature", signature(secret, payload))
                    .POST(HttpRequest.BodyPublishers.ofString(payload,
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Outcome(true, status, null);
            }
            return new Outcome(false, status,
                    "HTTP " + status + ": " + truncate(response.body()));
        } catch (IOException e) {
            return new Outcome(false, null, e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, null, "interrupted");
        } catch (IllegalArgumentException e) {
            return new Outcome(false, null, "invalid endpoint URL");
        }
    }

    /** HMAC-SHA256 over the raw body bytes, hex encoded. */
    public static String signature(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Timing-safe comparison of two hex signatures. */
    public static boolean signatureMatches(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120);
    }
}
