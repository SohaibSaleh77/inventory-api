package com.ims.domain.webhook;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC DAO for webhook registrations and their delivery outbox.
 * Delivery rows are appended inside the business transaction (outbox
 * pattern); the dispatcher owns all later state transitions.
 */
public final class WebhookDao {

    private static final String WEBHOOK_COLUMNS = """
            SELECT id, name, url, secret, events, is_active, created_by, version,
                   created_at, updated_at
              FROM webhooks
            """;

    private static final String DELIVERY_COLUMNS = """
            SELECT id, webhook_id, event_type, payload, status, attempts,
                   next_attempt_at, last_status_code, last_error, delivered_at,
                   created_at
              FROM webhook_deliveries
            """;

    // ---- webhooks ----

    public Optional<Webhook> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                WEBHOOK_COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapWebhook(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Webhook> findByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                WEBHOOK_COLUMNS + " WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapWebhook(rs)) : Optional.empty();
            }
        }
    }

    public List<Webhook> listAll(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                WEBHOOK_COLUMNS + " ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Webhook> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapWebhook(rs));
                }
                return result;
            }
        }
    }

    /** Active webhooks whose subscription list contains the event. */
    public List<Webhook> listSubscribed(Connection c, String eventType)
            throws SQLException {
        String sql = WEBHOOK_COLUMNS
                + " WHERE is_active = 1 AND FIND_IN_SET(?, REPLACE(events, ' ', ''))";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                List<Webhook> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapWebhook(rs));
                }
                return result;
            }
        }
    }

    public Webhook insert(Connection c, Webhook webhook) throws SQLException {
        String sql = """
                INSERT INTO webhooks (name, url, secret, events, is_active, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, webhook.getName());
            ps.setString(2, webhook.getUrl());
            ps.setString(3, webhook.getSecret());
            ps.setString(4, webhook.getEvents());
            ps.setBoolean(5, webhook.isActive());
            SqlUtils.setNullableLong(ps, 6, webhook.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                webhook.setId(keys.getLong(1));
            }
            return findById(c, webhook.getId()).orElse(webhook);
        }
    }

    public boolean update(Connection c, Webhook webhook) throws SQLException {
        String sql = """
                UPDATE webhooks
                   SET name = ?, url = ?, secret = ?, events = ?, is_active = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, webhook.getName());
            ps.setString(2, webhook.getUrl());
            ps.setString(3, webhook.getSecret());
            ps.setString(4, webhook.getEvents());
            ps.setBoolean(5, webhook.isActive());
            ps.setLong(6, webhook.getId());
            ps.setInt(7, webhook.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean deleteById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM webhooks WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    // ---- deliveries ----

    public WebhookDelivery insertDelivery(Connection c, WebhookDelivery delivery)
            throws SQLException {
        String sql = """
                INSERT INTO webhook_deliveries
                       (webhook_id, event_type, payload, status, attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, 0, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, delivery.getWebhookId());
            ps.setString(2, delivery.getEventType());
            ps.setString(3, delivery.getPayload());
            ps.setString(4, delivery.getStatus());
            ps.setTimestamp(5, java.sql.Timestamp.from(delivery.getNextAttemptAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                delivery.setId(keys.getLong(1));
            }
            return delivery;
        }
    }

    /** Deliveries that are due now (PENDING with next_attempt_at in the past). */
    public List<WebhookDelivery> findDue(Connection c, Instant now, int limit)
            throws SQLException {
        String sql = DELIVERY_COLUMNS
                + " WHERE status = 'PENDING' AND next_attempt_at <= ?"
                + " ORDER BY id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(now));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<WebhookDelivery> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapDelivery(rs));
                }
                return result;
            }
        }
    }

    public Optional<WebhookDelivery> findDelivery(Connection c, long id)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                DELIVERY_COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDelivery(rs)) : Optional.empty();
            }
        }
    }

    public List<WebhookDelivery> searchDeliveries(Connection c, long webhookId,
                                                  String status, int limit,
                                                  int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE webhook_id = ?");
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
        }
        String sql = DELIVERY_COLUMNS + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 1;
            ps.setLong(index++, webhookId);
            if (status != null && !status.isBlank()) {
                ps.setString(index++, status.toUpperCase());
            }
            ps.setInt(index++, limit);
            ps.setInt(index, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<WebhookDelivery> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapDelivery(rs));
                }
                return result;
            }
        }
    }

    public long countDeliveries(Connection c, long webhookId, String status)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE webhook_id = ?");
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM webhook_deliveries" + where)) {
            ps.setLong(1, webhookId);
            if (status != null && !status.isBlank()) {
                ps.setString(2, status.toUpperCase());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Record a successful delivery. */
    public boolean markDelivered(Connection c, long deliveryId, int statusCode,
                                 Instant deliveredAt) throws SQLException {
        String sql = """
                UPDATE webhook_deliveries
                   SET status = 'SUCCESS', attempts = attempts + 1,
                       last_status_code = ?, last_error = NULL, delivered_at = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, statusCode);
            ps.setTimestamp(2, java.sql.Timestamp.from(deliveredAt));
            ps.setLong(3, deliveryId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Record a failed attempt; FAILED when attempts exhausted, else reschedule. */
    public void markAttemptFailed(Connection c, long deliveryId, int attemptNo,
                                  Integer statusCode, String error,
                                  Instant nextAttemptAt, boolean exhausted)
            throws SQLException {
        String sql = exhausted
                ? """
                  UPDATE webhook_deliveries
                     SET status = 'FAILED', attempts = ?, last_status_code = ?,
                         last_error = ?
                   WHERE id = ?
                  """
                : """
                  UPDATE webhook_deliveries
                     SET attempts = ?, last_status_code = ?, last_error = ?,
                         next_attempt_at = ?
                   WHERE id = ?
                  """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, attemptNo);
            SqlUtils.setNullableInt(ps, 2, statusCode);
            SqlUtils.setNullableString(ps, 3, truncate(error, 500));
            if (exhausted) {
                ps.setLong(4, deliveryId);
            } else {
                ps.setTimestamp(4, java.sql.Timestamp.from(nextAttemptAt));
                ps.setLong(5, deliveryId);
            }
            ps.executeUpdate();
        }
    }

    /** Operator-requested retry of a FAILED delivery: re-queue from now. */
    public boolean requeue(Connection c, long deliveryId) throws SQLException {
        String sql = """
                UPDATE webhook_deliveries
                   SET status = 'PENDING', attempts = 0, next_attempt_at = ?
                 WHERE id = ? AND status = 'FAILED'
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(Instant.now()));
            ps.setLong(2, deliveryId);
            return ps.executeUpdate() == 1;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value
                : value.substring(0, maxLength);
    }

    private static Webhook mapWebhook(ResultSet rs) throws SQLException {
        Webhook webhook = new Webhook();
        webhook.setId(SqlUtils.getLong(rs, "id"));
        webhook.setName(rs.getString("name"));
        webhook.setUrl(rs.getString("url"));
        webhook.setSecret(rs.getString("secret"));
        webhook.setEvents(rs.getString("events"));
        webhook.setActive(rs.getBoolean("is_active"));
        webhook.setCreatedBy(SqlUtils.getLong(rs, "created_by"));
        webhook.setVersion(rs.getInt("version"));
        webhook.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        webhook.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return webhook;
    }

    private static WebhookDelivery mapDelivery(ResultSet rs) throws SQLException {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(SqlUtils.getLong(rs, "id"));
        delivery.setWebhookId(rs.getLong("webhook_id"));
        delivery.setEventType(rs.getString("event_type"));
        delivery.setPayload(rs.getString("payload"));
        delivery.setStatus(rs.getString("status"));
        delivery.setAttempts(rs.getInt("attempts"));
        delivery.setNextAttemptAt(SqlUtils.getInstant(rs, "next_attempt_at"));
        delivery.setLastStatusCode(SqlUtils.getInt(rs, "last_status_code"));
        delivery.setLastError(rs.getString("last_error"));
        delivery.setDeliveredAt(SqlUtils.getInstant(rs, "delivered_at"));
        delivery.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        return delivery;
    }
}
