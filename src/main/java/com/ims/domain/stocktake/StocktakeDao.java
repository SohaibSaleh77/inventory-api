package com.ims.domain.stocktake;

import com.ims.common.util.SqlUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * JDBC DAO for stocktakes and their count lines. Lines are joined with
 * products for display (SKU/name) and with users (counter name).
 */
public final class StocktakeDao {

    private static final String COLUMNS = """
            SELECT id, stocktake_no, warehouse_id, name, blind, status, note,
                   created_by, opened_at, closed_at, posted_at, posted_by, version,
                   created_at, updated_at
              FROM stocktakes
            """;

    private static final String LINE_COLUMNS = """
            SELECT l.id, l.stocktake_id, l.product_id, l.expected_qty, l.counted_qty,
                   l.recounts, l.counted_by, l.counted_at, l.created_at,
                   p.sku AS product_sku, p.name AS product_name,
                   u.username AS counted_by_username
              FROM stocktake_lines l
              JOIN products p ON p.id = l.product_id
              LEFT JOIN users u ON u.id = l.counted_by
            """;

    // ---- stocktakes ----

    public Optional<Stocktake> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Stocktake> search(Connection c, Long warehouseId, String status,
                                  int limit, int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        String sql = COLUMNS + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Stocktake> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countSearch(Connection c, Long warehouseId, String status)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM stocktakes" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Stocktake insert(Connection c, Stocktake stocktake) throws SQLException {
        String sql = """
                INSERT INTO stocktakes (stocktake_no, warehouse_id, name, blind,
                                        status, note, created_by)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, stocktake.getStocktakeNo());
            ps.setLong(2, stocktake.getWarehouseId());
            ps.setString(3, stocktake.getName());
            ps.setBoolean(4, stocktake.isBlind());
            SqlUtils.setNullableString(ps, 5, stocktake.getNote());
            SqlUtils.setNullableLong(ps, 6, stocktake.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                stocktake.setId(keys.getLong(1));
            }
            return stocktake;
        }
    }

    /** Optimistic-lock status transition; returns false on version clash. */
    public boolean updateStatus(Connection c, long id, String fromStatus,
                                String toStatus, Instant timestamp, Long actorId,
                                int expectedVersion) throws SQLException {
        String sql = """
                UPDATE stocktakes
                   SET status = ?,
                       opened_at = CASE WHEN ? = 'OPEN' THEN ? ELSE opened_at END,
                       closed_at = CASE WHEN ? = 'REVIEW' THEN ? ELSE closed_at END,
                       posted_at = CASE WHEN ? = 'POSTED' THEN ? ELSE posted_at END,
                       posted_by = CASE WHEN ? = 'POSTED' THEN ? ELSE posted_by END,
                       version = version + 1
                 WHERE id = ? AND status = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, toStatus);
            ps.setString(2, toStatus);
            ps.setTimestamp(3, timestamp == null ? null : Timestamp.from(timestamp));
            ps.setString(4, toStatus);
            ps.setTimestamp(5, timestamp == null ? null : Timestamp.from(timestamp));
            ps.setString(6, toStatus);
            ps.setTimestamp(7, timestamp == null ? null : Timestamp.from(timestamp));
            ps.setString(8, toStatus);
            SqlUtils.setNullableLong(ps, 9, actorId);
            ps.setLong(10, id);
            ps.setString(11, fromStatus);
            ps.setInt(12, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    // ---- lines ----

    public int insertLines(Connection c, long stocktakeId,
                           List<StocktakeLine> lines) throws SQLException {
        if (lines.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO stocktake_lines (stocktake_id, product_id, expected_qty)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (StocktakeLine line : lines) {
                ps.setLong(1, stocktakeId);
                ps.setLong(2, line.getProductId());
                ps.setBigDecimal(3, line.getExpectedQty());
                ps.addBatch();
            }
            return Arrays.stream(ps.executeBatch()).sum();
        }
    }

    /** Count sheet with product details; ordered by SKU for stable output. */
    public List<StocktakeLine> findLines(Connection c, long stocktakeId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                LINE_COLUMNS + " WHERE l.stocktake_id = ? ORDER BY p.sku")) {
            ps.setLong(1, stocktakeId);
            try (ResultSet rs = ps.executeQuery()) {
                List<StocktakeLine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLine(rs));
                }
                return result;
            }
        }
    }

    public Optional<StocktakeLine> findLine(Connection c, long stocktakeId,
                                            long productId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                LINE_COLUMNS + " WHERE l.stocktake_id = ? AND l.product_id = ?")) {
            ps.setLong(1, stocktakeId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapLine(rs)) : Optional.empty();
            }
        }
    }

    /** Aggregate line counters for one stocktake (list/detail summaries). */
    public record LineStats(int lineCount, int countedLines, int varianceLines) {
    }

    public LineStats lineStats(Connection c, long stocktakeId) throws SQLException {
        String sql = """
                SELECT COUNT(*)                                            AS line_count,
                       COALESCE(SUM(counted_qty IS NOT NULL), 0)          AS counted_lines,
                       COALESCE(SUM(counted_qty IS NOT NULL
                                    AND counted_qty <> expected_qty), 0)   AS variance_lines
                  FROM stocktake_lines
                 WHERE stocktake_id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, stocktakeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new LineStats(rs.getInt("line_count"),
                        rs.getInt("counted_lines"), rs.getInt("variance_lines"));
            }
        }
    }

    /** Upsert a count: first entry sets it, later entries are recounts. */
    public boolean recordCount(Connection c, StocktakeLine line, long counterId)
            throws SQLException {
        String sql = """
                UPDATE stocktake_lines
                   SET counted_qty = ?, recounts = recounts + 1,
                       counted_by = ?, counted_at = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, line.getCountedQty());
            ps.setLong(2, counterId);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setLong(4, line.getId());
            return ps.executeUpdate() == 1;
        }
    }

    /** Stamp the counted moment on the stock row (cycle-count bookkeeping). */
    public void touchStockItems(Connection c, long warehouseId, long productId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE stock_items SET last_counted_at = ? "
                        + "WHERE product_id = ? AND warehouse_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setLong(2, productId);
            ps.setLong(3, warehouseId);
            ps.executeUpdate();
        }
    }

    /**
     * Book quantities for a fresh count sheet: every active product with the
     * warehouse's current on-hand (0 when never stocked there).
     */
    public List<CountSheetRow> buildCountSheet(Connection c, long warehouseId)
            throws SQLException {
        String sql = """
                SELECT p.id AS product_id,
                       COALESCE(si.qty_on_hand, 0) AS expected_qty
                  FROM products p
                  LEFT JOIN stock_items si
                         ON si.product_id = p.id AND si.warehouse_id = ?
                 WHERE p.is_active = 1
                 ORDER BY p.sku
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CountSheetRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CountSheetRow(rs.getLong("product_id"),
                            rs.getBigDecimal("expected_qty")));
                }
                return rows;
            }
        }
    }

    /** Raw count-sheet material: product id + frozen book quantity. */
    public record CountSheetRow(long productId, BigDecimal expectedQty) {
    }

    private static Stocktake map(ResultSet rs) throws SQLException {
        Stocktake stocktake = new Stocktake();
        stocktake.setId(SqlUtils.getLong(rs, "id"));
        stocktake.setStocktakeNo(rs.getString("stocktake_no"));
        stocktake.setWarehouseId(rs.getLong("warehouse_id"));
        stocktake.setName(rs.getString("name"));
        stocktake.setBlind(rs.getBoolean("blind"));
        stocktake.setStatus(rs.getString("status"));
        stocktake.setNote(rs.getString("note"));
        stocktake.setCreatedBy(SqlUtils.getLong(rs, "created_by"));
        stocktake.setOpenedAt(SqlUtils.getInstant(rs, "opened_at"));
        stocktake.setClosedAt(SqlUtils.getInstant(rs, "closed_at"));
        stocktake.setPostedAt(SqlUtils.getInstant(rs, "posted_at"));
        stocktake.setPostedBy(SqlUtils.getLong(rs, "posted_by"));
        stocktake.setVersion(rs.getInt("version"));
        stocktake.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        stocktake.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return stocktake;
    }

    private static StocktakeLine mapLine(ResultSet rs) throws SQLException {
        StocktakeLine line = new StocktakeLine();
        line.setId(SqlUtils.getLong(rs, "id"));
        line.setStocktakeId(rs.getLong("stocktake_id"));
        line.setProductId(rs.getLong("product_id"));
        line.setExpectedQty(rs.getBigDecimal("expected_qty"));
        line.setCountedQty(rs.getBigDecimal("counted_qty"));
        line.setRecounts(rs.getInt("recounts"));
        line.setCountedBy(SqlUtils.getLong(rs, "counted_by"));
        line.setCountedAt(SqlUtils.getInstant(rs, "counted_at"));
        line.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        line.setProductSku(rs.getString("product_sku"));
        line.setProductName(rs.getString("product_name"));
        line.setCountedByUsername(rs.getString("counted_by_username"));
        return line;
    }
}
