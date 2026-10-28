package com.ims.domain.stock;

import com.ims.common.util.SqlUtils;
import com.ims.db.DocumentNumbers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** DAO for stock_adjustments. */
public final class StockAdjustmentDao {

    public StockAdjustment insert(Connection c, DocumentNumbers docNumbers,
                                  StockAdjustment adjustment) throws SQLException {
        adjustment.setAdjustmentNo(
                docNumbers.next(c, DocumentNumbers.DocType.ADJUSTMENT));
        String sql = """
                INSERT INTO stock_adjustments (adjustment_no, warehouse_id, product_id,
                                               quantity_delta, reason, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, adjustment.getAdjustmentNo());
            ps.setLong(2, adjustment.getWarehouseId());
            ps.setLong(3, adjustment.getProductId());
            ps.setBigDecimal(4, adjustment.getQuantityDelta());
            ps.setString(5, adjustment.getReason());
            SqlUtils.setNullableString(ps, 6, adjustment.getNote());
            SqlUtils.setNullableLong(ps, 7, adjustment.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    adjustment.setId(keys.getLong(1));
                }
            }
            return adjustment;
        }
    }

    public List<StockAdjustment> search(Connection c, Long productId, Long warehouseId,
                                        String reason, int limit, int offset)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (productId != null) {
            where.append(" AND product_id = ?");
            params.add(productId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (reason != null && !reason.isBlank()) {
            where.append(" AND reason = ?");
            params.add(reason);
        }
        String sql = "SELECT * FROM stock_adjustments" + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StockAdjustment> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    /** Real row count for the same filters as {@link #search}. */
    public long countSearch(Connection c, Long productId, Long warehouseId,
                            String reason) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (productId != null) {
            where.append(" AND product_id = ?");
            params.add(productId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (reason != null && !reason.isBlank()) {
            where.append(" AND reason = ?");
            params.add(reason);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM stock_adjustments" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static StockAdjustment map(ResultSet rs) throws SQLException {
        StockAdjustment a = new StockAdjustment();
        a.setId(SqlUtils.getLong(rs, "id"));
        a.setAdjustmentNo(rs.getString("adjustment_no"));
        a.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        a.setProductId(SqlUtils.getLong(rs, "product_id"));
        a.setQuantityDelta(rs.getBigDecimal("quantity_delta"));
        a.setReason(rs.getString("reason"));
        a.setNote(rs.getString("note"));
        a.setCreatedBy(SqlUtils.getLong(rs, "created_by"));
        a.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        return a;
    }
}
