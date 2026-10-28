package com.ims.domain.audit;

import com.ims.common.util.Json;
import com.ims.common.util.PageRequest;
import com.ims.common.util.SqlUtils;
import com.ims.db.TransactionManager;
import com.ims.security.AuthContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes and queries audit records. Every mutating service method funnels
 * through {@link #logAction} inside the same transaction as the change itself,
 * so an audit row exists if and only if the change committed.
 */
public final class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final TransactionManager tm;

    public AuditLogger(TransactionManager tm) {
        this.tm = tm;
    }

    /**
     * Record an action within the caller's transaction. The connection must be
     * the transactional one so the audit row commits atomically with the change.
     * All payload objects are serialized to valid JSON documents (JSON columns).
     */
    public void logAction(Connection conn, AuthContext actor, String action,
                          String entityType, Long entityId, String entityNo,
                          Object before, Object after) {
        String beforeJson = before == null ? null : Json.snapshot(before);
        String afterJson = after == null ? null : Json.snapshot(after);
        // Network origin of the mutation (populated when serving an HTTP
        // request; background jobs like housekeeping audit as "system").
        String clientIp = com.ims.http.RequestContext.clientIp();
        String sql = """
                INSERT INTO audit_logs (actor_id, actor_username, action, entity_type,
                                        entity_id, entity_no, before_json, after_json,
                                        ip_address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            SqlUtils.setNullableLong(ps, 1, actor == null ? null : actor.userId());
            ps.setString(2, actor == null ? "system" : actor.username());
            ps.setString(3, action);
            ps.setString(4, entityType);
            SqlUtils.setNullableLong(ps, 5, entityId);
            SqlUtils.setNullableString(ps, 6, entityNo);
            SqlUtils.setNullableString(ps, 7, beforeJson);
            SqlUtils.setNullableString(ps, 8, afterJson);
            SqlUtils.setNullableString(ps, 9, clientIp);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to write audit log for {} {} {}",
                    action, entityType, entityId, e);
            throw com.ims.db.SQLExceptionMapper.translate("audit log", e);
        }
    }

    public record AuditQuery(String action, String entityType, Long entityId,
                             String actorUsername, Instant from, Instant to,
                             PageRequest page) {
    }

    public long count(AuditQuery query) {
        return tm.withConnection(conn -> count(conn, query));
    }

    public long count(Connection conn, AuditQuery query) throws SQLException {
        QueryParams params = buildQuery(query);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM audit_logs" + params.where())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public java.util.List<AuditLog> search(AuditQuery query) {
        return tm.withConnection(conn -> search(conn, query));
    }

    public java.util.List<AuditLog> search(Connection conn, AuditQuery query)
            throws SQLException {
        QueryParams params = buildQuery(query);
        String sql = "SELECT * FROM audit_logs" + params.where()
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            ps.setInt(params.args().size() + 1, query.page().size());
            ps.setInt(params.args().size() + 2, query.page().offset());
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<AuditLog> result = new java.util.ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    /** Count of audit rows for an entity - used by invariant tests. */
    public long countForEntity(Connection conn, String entityType, long entityId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_type = ? AND entity_id = ?")) {
            ps.setString(1, entityType);
            ps.setLong(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private record QueryParams(String where, java.util.List<Object> args) {
    }

    private QueryParams buildQuery(AuditQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (query.action() != null && !query.action().isBlank()) {
            where.append(" AND action = ?");
            args.add(query.action());
        }
        if (query.entityType() != null && !query.entityType().isBlank()) {
            where.append(" AND entity_type = ?");
            args.add(query.entityType());
        }
        if (query.entityId() != null) {
            where.append(" AND entity_id = ?");
            args.add(query.entityId());
        }
        if (query.actorUsername() != null && !query.actorUsername().isBlank()) {
            where.append(" AND actor_username = ?");
            args.add(query.actorUsername());
        }
        if (query.from() != null) {
            where.append(" AND occurred_at >= ?");
            args.add(java.sql.Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND occurred_at <= ?");
            args.add(java.sql.Timestamp.from(query.to()));
        }
        return new QueryParams(where.toString(), args);
    }

    private void bind(PreparedStatement ps, QueryParams params) throws SQLException {
        for (int i = 0; i < params.args().size(); i++) {
            ps.setObject(i + 1, params.args().get(i));
        }
    }

    static AuditLog map(ResultSet rs) throws SQLException {
        AuditLog entry = new AuditLog();
        entry.setId(SqlUtils.getLong(rs, "id"));
        entry.setOccurredAt(SqlUtils.getInstant(rs, "occurred_at"));
        entry.setActorId(SqlUtils.getLong(rs, "actor_id"));
        entry.setActorUsername(rs.getString("actor_username"));
        entry.setAction(rs.getString("action"));
        entry.setEntityType(rs.getString("entity_type"));
        entry.setEntityId(SqlUtils.getLong(rs, "entity_id"));
        entry.setEntityNo(rs.getString("entity_no"));
        entry.setBeforeJson(rs.getString("before_json"));
        entry.setAfterJson(rs.getString("after_json"));
        entry.setIpAddress(rs.getString("ip_address"));
        entry.setDetail(rs.getString("detail"));
        return entry;
    }
}
