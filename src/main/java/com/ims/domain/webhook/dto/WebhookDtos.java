package com.ims.domain.webhook.dto;

import com.ims.domain.webhook.Webhook;
import com.ims.domain.webhook.WebhookDelivery;

import java.time.Instant;
import java.util.List;

/** Request/response records for the webhook API. */
public final class WebhookDtos {

    private WebhookDtos() {
    }

    public record CreateRequest(String name, String url, String secret,
                                List<String> events) {
    }

    public record UpdateRequest(String name, String url, String secret,
                                List<String> events, Boolean isActive) {
    }

    /**
     * Webhook registration as returned to clients. The signing secret is
     * shown only to ADMIN/MANAGER (WEBHOOK_READ holders) because verifying
     * receivers need it and leaking it lets anyone forge signatures.
     */
    public record Response(long id, String name, String url, String secret,
                           List<String> events, boolean isActive,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Webhook webhook) {
            return new Response(webhook.getId(), webhook.getName(), webhook.getUrl(),
                    webhook.getSecret(), List.copyOf(webhook.eventSet()),
                    webhook.isActive(), webhook.getCreatedAt(),
                    webhook.getUpdatedAt());
        }
    }

    public record DeliveryResponse(long id, long webhookId, String eventType,
                                   String payload, String status, int attempts,
                                   Instant nextAttemptAt, Integer lastStatusCode,
                                   String lastError, Instant deliveredAt,
                                   Instant createdAt) {

        public static DeliveryResponse from(WebhookDelivery delivery) {
            return new DeliveryResponse(delivery.getId(), delivery.getWebhookId(),
                    delivery.getEventType(), delivery.getPayload(),
                    delivery.getStatus(), delivery.getAttempts(),
                    delivery.getNextAttemptAt(), delivery.getLastStatusCode(),
                    delivery.getLastError(), delivery.getDeliveredAt(),
                    delivery.getCreatedAt());
        }
    }

    public record TestEventResponse(long deliveryId, String eventType) {
    }

    public record RetryResponse(long deliveryId, String status) {
    }
}
