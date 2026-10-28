package com.ims.domain.stock;

import com.ims.common.util.SqlUtils;
import com.ims.db.DocumentNumbers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** DAO for the append-only stock_movements ledger (insert + query only). */
public final class StockMovementDao {

    private static final String COLUMNS = """
            SELECT id, movement_no, product_id, warehouse_id, movement_type, quantity,
                   unit_cost, balance_after, ref_type, ref_id, ref_no, reason, note,
                   performed_by, performed_at, created_at
              FROM stock_movements
            """;

    /** Insert a ledger row. Movement number is allocated in the same transaction. */
    public StockMovement insert(Connection c, DocumentNumbers docNumbers,
                                StockMovement movement) throws SQLException {
        movement.setMovementNo(docNumbers.next(c, DocumentNumbers.DocType.MOVEMENT));
        String sql = """
                INSERT INTO stock_movements (movement_no, product_id, warehouse_id,
                                             movement_type, quantity, unit_cost,
                                             balance_after, ref_type, ref_id, ref_no,
                                             reason, note, performed_by, performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, movement.getMovementNo());
            ps.setLong(2, movement.getProductId());
            ps.setLong(3, movement.getWarehouseId());
            ps.setString(4, movement.getMovementType());
            ps.setBigDecimal(5, movement.getQuantity());
            SqlUtils.setNullableBigDecimal(ps, 6, movement.getUnitCost());
            ps.setBigDecimal(7, movement.getBalanceAfter());
            SqlUtils.setNullableString(ps, 8, movement.getRefType());
            SqlUtils.setNullableLong(ps, 9, movement.getRefId());
            SqlUtils.setNullableString(ps, 10, movement.getRefNo());
            SqlUtils.setNullableString(ps, 11, movement.getReason());
            SqlUtils.setNullableString(ps, 12, movement.getNote());
            SqlUtils.setNullableLong(ps, 13, movement.getPerformedBy());
            ps.setTimestamp(14, java.sql.Timestamp.from(
                    movement.getPerformedAt() == null ? java.time.Instant.now()
                            : movement.getPerformedAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    movement.setId(keys.getLong(1));
                }
            }
            return movement;
        }
    }

    public record MovementQuery(Long productId, Long warehouseId, String movementType,
                                String refType, Long refId, String from, String to) {
    }

    public List<StockMovement> search(Connection c, MovementQuery query,
                                      int limit, int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query.productId() != null) {
            where.append(" AND product_id = ?");
            params.add(query.productId());
        }
        if (query.warehouseId() != null) {
            where.append(" AND warehouse_id = ?");
            params.add(query.warehouseId());
        }
        if (query.movementType() != null && !query.movementType().isBlank()) {
            where.append(" AND movement_type = ?");
            params.add(query.movementType());
        }
        if (query.refType() != null && !query.refType().isBlank()) {
            where.append(" AND ref_type = ?");
            params.add(query.refType());
        }
        if (query.refId() != null) {
            where.append(" AND ref_id = ?");
            params.add(query.refId());
        }
        if (query.from() != null && !query.from().isBlank()) {
            where.append(" AND performed_at >= ?");
            params.add(query.from());
        }
        if (query.to() != null && !query.to().isBlank()) {
            where.append(" AND performed_at <= ?");
            params.add(query.to());
        }
        String sql = COLUMNS + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StockMovement> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countSearch(Connection c, MovementQuery query) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query.productId() != null) {
            where.append(" AND product_id = ?");
            params.add(query.productId());
        }
        if (query.warehouseId() != null) {
            where.append(" AND warehouse_id = ?");
            params.add(query.warehouseId());
        }
        if (query.movementType() != null && !query.movementType().isBlank()) {
            where.append(" AND movement_type = ?");
            params.add(query.movementType());
        }
        if (query.refType() != null && !query.refType().isBlank()) {
            where.append(" AND ref_type = ?");
            params.add(query.refType());
        }
        if (query.refId() != null) {
            where.append(" AND ref_id = ?");
            params.add(query.refId());
        }
        if (query.from() != null && !query.from().isBlank()) {
            where.append(" AND performed_at >= ?");
            params.add(query.from());
        }
        if (query.to() != null && !query.to().isBlank()) {
            where.append(" AND performed_at <= ?");
            params.add(query.to());
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM stock_movements" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Reconciliation query: sum of all signed ledger quantities for one
     * (product, warehouse) - MUST equal the current qty_on_hand.
     */
    public java.math.BigDecimal sumQuantity(Connection c, long productId, long warehouseId)
            throws SQLException {
        String sql = "SELECT COALESCE(SUM(quantity), 0) FROM stock_movements "
                + "WHERE product_id = ? AND warehouse_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setLong(2, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    static StockMovement map(ResultSet rs) throws SQLException {
        StockMovement m = new StockMovement();
        m.setId(SqlUtils.getLong(rs, "id"));
        m.setMovementNo(rs.getString("movement_no"));
        m.setProductId(SqlUtils.getLong(rs, "product_id"));
        m.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        m.setMovementType(rs.getString("movement_type"));
        m.setQuantity(rs.getBigDecimal("quantity"));
        m.setUnitCost(SqlUtils.getDecimal(rs, "unit_cost"));
        m.setBalanceAfter(rs.getBigDecimal("balance_after"));
        m.setRefType(rs.getString("ref_type"));
        m.setRefId(SqlUtils.getLong(rs, "ref_id"));
        m.setRefNo(rs.getString("ref_no"));
        m.setReason(rs.getString("reason"));
        m.setNote(rs.getString("note"));
        m.setPerformedBy(SqlUtils.getLong(rs, "performed_by"));
        m.setPerformedAt(SqlUtils.getInstant(rs, "performed_at"));
        m.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        return m;
    }
}
