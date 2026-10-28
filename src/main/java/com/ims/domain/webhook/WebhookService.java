package com.ims.domain.webhook;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Dates;
import com.ims.common.util.Json;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.webhook.dto.WebhookDtos.CreateRequest;
import com.ims.domain.webhook.dto.WebhookDtos.DeliveryResponse;
import com.ims.domain.webhook.dto.WebhookDtos.Response;
import com.ims.domain.webhook.dto.WebhookDtos.RetryResponse;
import com.ims.domain.webhook.dto.WebhookDtos.TestEventResponse;
import com.ims.domain.webhook.dto.WebhookDtos.UpdateRequest;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Webhook registration and the transactional outbox.
 *
 * <p>Business services call {@link #enqueueEvent} with the caller's
 * transactional connection, so a delivery row exists if and only if the
 * business change committed. {@link WebhookDispatcher} then drains the
 * outbox over HTTP with retries.
 *
 * <p>The STOCK_LOW evaluation lives here as well: {@code StockService} is the
 * single choke point for every stock mutation, and it hands the pre/post
 * available quantities to {@link #enqueueStockLowIfCrossed} which decides -
 * with one product lookup - whether the reorder threshold was crossed.
 */
public final class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** Delivery attempt cap before a delivery is parked as FAILED. */
    public static final int DEFAULT_MAX_ATTEMPTS = 6;

    private final TransactionManager tm;
    private final WebhookDao webhookDao;
    private final AuditLogger audit;
    private final WebhookClient client;
    private final int maxAttempts;

    public WebhookService(TransactionManager tm, WebhookDao webhookDao,
                          AuditLogger audit, WebhookClient client,
                          int maxAttempts) {
        this.tm = tm;
        this.webhookDao = webhookDao;
        this.audit = audit;
        this.client = client;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    // ---- registration CRUD ----

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.WEBHOOK_WRITE);
        String name = Validators.requireText(request.name(), "name", 100);
        String url = validateUrl(request.url());
        String secret = validateSecret(request.secret());
        Set<String> events = validateEvents(request.events());
        if (request.events() == null || request.events().isEmpty()) {
            throw new ValidationException("events", "at least one event is required");
        }

        return tm.inTransaction(conn -> {
            if (webhookDao.findByName(conn, name).isPresent()) {
                throw new ValidationException("name", "already in use: " + name);
            }
            Webhook webhook = new Webhook(name, url, secret,
                    WebhookEvents.render(events), actor.userId());
            webhook = webhookDao.insert(conn, webhook);
            audit.logAction(conn, actor, AuditLogs.WEBHOOK_CREATED, "WEBHOOK",
                    webhook.getId(), null, null, Response.from(webhook));
            log.info("Webhook {} registered for {} by {}", webhook.getId(), url,
                    actor.username());
            return Response.from(webhook);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.WEBHOOK_WRITE);
        String name = Validators.requireText(request.name(), "name", 100);
        String url = validateUrl(request.url());
        String secret = validateSecret(request.secret());
        Set<String> events = validateEvents(request.events());
        if (request.events() == null || request.events().isEmpty()) {
            throw new ValidationException("events", "at least one event is required");
        }
        boolean active = request.isActive() == null || request.isActive();

        return tm.inTransaction(conn -> {
            Webhook existing = webhookDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Webhook", id));
            Response before = Response.from(existing);
            existing.setName(name);
            existing.setUrl(url);
            existing.setSecret(secret);
            existing.setEvents(WebhookEvents.render(events));
            existing.setActive(active);
            if (!webhookDao.update(conn, existing)) {
                throw new OptimisticLockException("Webhook", id);
            }
            Webhook updated = webhookDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.WEBHOOK_UPDATED, "WEBHOOK", id,
                    null, before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public void delete(AuthContext actor, long id) {
        actor.require(Permission.WEBHOOK_WRITE);
        tm.inTransaction(conn -> {
            Webhook existing = webhookDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Webhook", id));
            Response before = Response.from(existing);
            webhookDao.deleteById(conn, id);
            audit.logAction(conn, actor, AuditLogs.WEBHOOK_DELETED, "WEBHOOK", id,
                    null, before, null);
            return null;
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.WEBHOOK_READ);
        return tm.withConnection(conn -> Response.from(webhookDao.findById(conn, id)
                .orElseThrow(() -> new NotFoundException("Webhook", id))));
    }

    public List<Response> list(AuthContext actor) {
        actor.require(Permission.WEBHOOK_READ);
        return tm.withConnection(conn -> webhookDao.listAll(conn).stream()
                .map(Response::from).toList());
    }

    public TestEventResponse sendTestEvent(AuthContext actor, long id) {
        actor.require(Permission.WEBHOOK_WRITE);
        return tm.inTransaction(conn -> {
            Webhook webhook = webhookDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Webhook", id));
            if (!webhook.isActive()) {
                throw new ValidationException("isActive",
                        "webhook is inactive - activate it before testing");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", "inventory-api webhook test event");
            data.put("sentBy", actor.username());
            WebhookDelivery delivery = enqueueFor(conn, webhook, WebhookEvents.TEST,
                    data, Dates.now());
            audit.logAction(conn, actor, AuditLogs.WEBHOOK_TEST_EVENT, "WEBHOOK",
                    webhook.getId(), null, null,
                    Map.of("deliveryId", delivery.getId()));
            return new TestEventResponse(delivery.getId(), WebhookEvents.TEST);
        });
    }

    public RetryResponse retryDelivery(AuthContext actor, long webhookId,
                                       long deliveryId) {
        actor.require(Permission.WEBHOOK_WRITE);
        return tm.inTransaction(conn -> {
            if (webhookDao.findById(conn, webhookId).isEmpty()) {
                throw new NotFoundException("Webhook", webhookId);
            }
            WebhookDelivery delivery = webhookDao.findDelivery(conn, deliveryId)
                    .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
            if (delivery.getWebhookId() != webhookId) {
                throw new ValidationException("deliveryId",
                        "delivery " + deliveryId + " does not belong to webhook "
                                + webhookId);
            }
            if (!WebhookDelivery.FAILED.equals(delivery.getStatus())) {
                throw new ValidationException("deliveryId",
                        "only FAILED deliveries can be retried (status: "
                                + delivery.getStatus() + ")");
            }
            if (!webhookDao.requeue(conn, deliveryId)) {
                throw new OptimisticLockException("Delivery", deliveryId);
            }
            audit.logAction(conn, actor, AuditLogs.WEBHOOK_DELIVERY_RETRIED,
                    "WEBHOOK", webhookId, null,
                    Map.of("deliveryId", deliveryId), null);
            return new RetryResponse(deliveryId, WebhookDelivery.PENDING);
        });
    }

    public PageRequest.Page<DeliveryResponse> deliveries(AuthContext actor,
            long webhookId, String status, PageRequest page) {
        actor.require(Permission.WEBHOOK_READ);
        return tm.withConnection(conn -> {
            if (webhookDao.findById(conn, webhookId).isEmpty()) {
                throw new NotFoundException("Webhook", webhookId);
            }
            long total = webhookDao.countDeliveries(conn, webhookId, status);
            List<DeliveryResponse> content = webhookDao
                    .searchDeliveries(conn, webhookId, status, page.size(),
                            page.offset())
                    .stream().map(DeliveryResponse::from).toList();
            return PageRequest.Page.of(content, page, total);
        });
    }

    // ---- outbox enqueue (called inside business transactions) ----

    /**
     * Append one delivery row per active webhook subscribed to the event.
     * MUST run inside the caller's transaction (transactional outbox).
     */
    public void enqueueEvent(Connection conn, String eventType,
                             Map<String, Object> data) throws SQLException {
        for (Webhook webhook : webhookDao.listSubscribed(conn, eventType)) {
            enqueueFor(conn, webhook, eventType, data, Dates.now());
        }
    }

    /**
     * STOCK_LOW evaluation: fire only when the available quantity crosses
     * from strictly above the reorder point to at-or-below it, so a product
     * that sits below its threshold does not fire on every later movement.
     */
    public void enqueueStockLowIfCrossed(Connection conn, long productId,
                                         long warehouseId, BigDecimal previousAvailable,
                                         BigDecimal newAvailable) throws SQLException {
        BigDecimal reorderPoint = null;
        String sku = null;
        String sql = "SELECT sku, reorder_point FROM products WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sku = rs.getString("sku");
                    reorderPoint = rs.getBigDecimal("reorder_point");
                }
            }
        }
        if (reorderPoint == null || reorderPoint.signum() <= 0) {
            return;
        }
        boolean crossed = previousAvailable.compareTo(reorderPoint) > 0
                && newAvailable.compareTo(reorderPoint) <= 0;
        if (!crossed) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId);
        data.put("sku", sku);
        data.put("warehouseId", warehouseId);
        data.put("qtyAvailable", newAvailable);
        data.put("reorderPoint", reorderPoint);
        enqueueEvent(conn, WebhookEvents.STOCK_LOW, data);
    }

    /** Build the stored payload envelope and insert one delivery row. */
    private WebhookDelivery enqueueFor(Connection conn, Webhook webhook,
                                       String eventType, Map<String, Object> data,
                                       Instant occurredAt) throws SQLException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("data", data);
        WebhookDelivery delivery = new WebhookDelivery(webhook.getId(), eventType,
                Json.write(envelope), Instant.now());
        return webhookDao.insertDelivery(conn, delivery);
    }

    // ---- dispatch (driven by WebhookDispatcher) ----

    /**
     * Deliver every due outbox row. Returns the number of deliveries that
     * reached a terminal state (SUCCESS or FAILED). Each delivery uses its
     * own transaction so one poisoned endpoint cannot block the rest.
     */
    public int deliverDue(int batchSize) {
        List<WebhookDelivery> due = tm.withConnection(conn ->
                webhookDao.findDue(conn, Dates.now(), batchSize));
        int completed = 0;
        for (WebhookDelivery delivery : due) {
            if (deliverOne(delivery)) {
                completed++;
            }
        }
        return completed;
    }

    /** Attempt a single delivery; true when it reached a terminal state. */
    private boolean deliverOne(WebhookDelivery delivery) {
        int attemptNo = delivery.getAttempts() + 1;
        String endpoint = "?";
        try {
            Optional<Webhook> registration = tm.withConnection(conn ->
                    webhookDao.findById(conn, delivery.getWebhookId()));
            if (registration.isEmpty() || !registration.get().isActive()) {
                // Registration deleted (cascade) or paused: park as FAILED so
                // it stops occupying the due queue.
                tm.inTransaction(conn -> {
                    webhookDao.markAttemptFailed(conn, delivery.getId(), attemptNo,
                            null, "webhook missing or inactive", Instant.now(), true);
                    return null;
                });
                return true;
            }
            endpoint = registration.get().getUrl();
            String secret = registration.get().getSecret();

            WebhookClient.Outcome outcome = client.attempt(
                    endpoint, delivery.getPayload(), secret,
                    delivery.getId(), delivery.getEventType());

            if (outcome.success()) {
                tm.inTransaction(conn -> {
                    webhookDao.markDelivered(conn, delivery.getId(),
                            outcome.statusCode(), Dates.now());
                    return null;
                });
                log.info("Webhook {} delivered {} event (delivery {}): HTTP {}",
                        delivery.getWebhookId(), delivery.getEventType(),
                        delivery.getId(), outcome.statusCode());
                return true;
            }

            boolean exhausted = attemptNo >= maxAttempts;
            tm.inTransaction(conn -> {
                webhookDao.markAttemptFailed(conn, delivery.getId(), attemptNo,
                        outcome.statusCode(), outcome.error(),
                        backoffUntil(attemptNo), exhausted);
                return null;
            });
            log.warn("Webhook {} delivery {} failed (attempt {}/{}): {}",
                    delivery.getWebhookId(), delivery.getId(), attemptNo,
                    maxAttempts, outcome.error());
            return exhausted;
        } catch (RuntimeException e) {
            boolean exhausted = attemptNo >= maxAttempts;
            String error = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            try {
                tm.inTransaction(conn -> {
                    webhookDao.markAttemptFailed(conn, delivery.getId(), attemptNo,
                            null, error, backoffUntil(attemptNo), exhausted);
                    return null;
                });
            } catch (RuntimeException suppressed) {
                log.error("Could not record webhook delivery failure", suppressed);
            }
            log.warn("Webhook delivery {} to {} failed (attempt {}/{}): {}",
                    delivery.getId(), endpoint, attemptNo, maxAttempts, error, e);
            return exhausted;
        }
    }

    /** Exponential backoff: 1m, 2m, 4m, 8m, 16m ... capped at 6h. */
    public Instant backoffUntil(int attemptNo) {
        long baseSeconds = 60L << Math.min(Math.max(attemptNo - 1, 0), 12);
        long capped = Math.min(baseSeconds, Duration.ofHours(6).toSeconds());
        return Dates.now().plusSeconds(capped);
    }

    // ---- validation helpers ----

    private String validateUrl(String value) {
        String url = Validators.requireText(value, "url", 500);
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new ValidationException("url",
                        "must be an http or https URL");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ValidationException("url", "must include a host");
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException("url", "is not a valid URL");
        }
        return url;
    }

    private String validateSecret(String value) {
        String secret = Validators.requireText(value, "secret", 100);
        if (secret.length() < 16) {
            throw new ValidationException("secret",
                    "must be at least 16 characters (use a random generated value)");
        }
        return secret;
    }

    private Set<String> validateEvents(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> events = new LinkedHashSet<>();
        for (String value : values) {
            String code = Validators.requireText(value, "events", 40)
                    .trim().toUpperCase();
            events.add(code);
        }
        if (!WebhookEvents.allKnown(events)) {
            throw new ValidationException("events",
                    "unknown event code(s); allowed: " + WebhookEvents.all());
        }
        return events;
    }

    /** Random signing secret generator for clients that do not supply one. */
    public static String generateSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
