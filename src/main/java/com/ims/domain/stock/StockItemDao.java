package com.ims.domain.stock;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DAO for stock_items. The lock methods use SELECT ... FOR UPDATE to
 * serialize concurrent stock operations on the same (product, warehouse).
 */
public final class StockItemDao {

    private static final String COLUMNS = """
            SELECT id, product_id, warehouse_id, qty_on_hand, qty_reserved,
                   avg_unit_cost, last_counted_at, version, created_at, updated_at
              FROM stock_items
            """;

    public Optional<StockItem> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Read the stock row for update - MUST be called inside the transaction
     * that mutates it. Locks the row until commit/rollback.
     */
    public Optional<StockItem> lockByProductAndWarehouse(Connection c, long productId,
                                                         long warehouseId)
            throws SQLException {
        String sql = COLUMNS + " WHERE product_id = ? AND warehouse_id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setLong(2, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Plain read (no lock) for reporting and read paths. */
    public Optional<StockItem> findByProductAndWarehouse(Connection c, long productId,
                                                         long warehouseId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                COLUMNS + " WHERE product_id = ? AND warehouse_id = ?")) {
            ps.setLong(1, productId);
            ps.setLong(2, warehouseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public StockItem getOrCreate(Connection c, long productId, long warehouseId)
            throws SQLException {
        Optional<StockItem> existing = lockByProductAndWarehouse(c, productId, warehouseId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Race-safe create: the unique key backs us up; retry the lock after insert
        String sql = """
                INSERT INTO stock_items (product_id, warehouse_id, qty_on_hand,
                                         qty_reserved, avg_unit_cost)
                VALUES (?, ?, 0, 0, 0)
                ON DUPLICATE KEY UPDATE id = id
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setLong(2, warehouseId);
            ps.executeUpdate();
        }
        return lockByProductAndWarehouse(c, productId, warehouseId)
                .orElseThrow(() -> new SQLException(
                        "stock_items row missing after getOrCreate"));
    }

    public boolean updateQuantities(Connection c, StockItem item) throws SQLException {
        String sql = """
                UPDATE stock_items
                   SET qty_on_hand = ?, qty_reserved = ?, avg_unit_cost = ?,
                       last_counted_at = ?, version = version + 1
                 WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, item.getQtyOnHand());
            ps.setBigDecimal(2, item.getQtyReserved());
            ps.setBigDecimal(3, item.getAvgUnitCost());
            SqlUtils.setNullableTimestamp(ps, 4, item.getLastCountedAt());
            ps.setLong(5, item.getId());
            return ps.executeUpdate() == 1;
        }
    }

    public List<StockItem> findNonZeroForProduct(Connection c, long productId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                COLUMNS + " WHERE product_id = ? AND (qty_on_hand <> 0 OR qty_reserved <> 0)")) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                List<StockItem> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public record StockLevelView(long stockItemId, long productId, String sku,
                                 String productName, long warehouseId,
                                 String warehouseCode, String warehouseName,
                                 java.math.BigDecimal qtyOnHand,
                                 java.math.BigDecimal qtyReserved,
                                 java.math.BigDecimal qtyAvailable,
                                 java.math.BigDecimal avgUnitCost,
                                 java.math.BigDecimal stockValue) {
    }

    /** Search across the v_stock_levels view with filters. */
    public List<StockLevelView> searchLevels(Connection c, String skuLike, Long warehouseId,
                                             Boolean lowOnly, int limit, int offset)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (skuLike != null && !skuLike.isBlank()) {
            where.append(" AND product_sku LIKE ?");
            params.add(skuLike.trim().toUpperCase() + "%");
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (Boolean.TRUE.equals(lowOnly)) {
            where.append(" AND qty_on_hand <= reorder_point AND reorder_point > 0");
        }
        String sql = "SELECT * FROM v_stock_levels" + where
                + " ORDER BY product_sku, warehouse_code LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StockLevelView> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new StockLevelView(
                            rs.getLong("stock_item_id"),
                            rs.getLong("product_id"),
                            rs.getString("product_sku"),
                            rs.getString("product_name"),
                            rs.getLong("warehouse_id"),
                            rs.getString("warehouse_code"),
                            rs.getString("warehouse_name"),
                            rs.getBigDecimal("qty_on_hand"),
                            rs.getBigDecimal("qty_reserved"),
                            rs.getBigDecimal("qty_available"),
                            rs.getBigDecimal("avg_unit_cost"),
                            rs.getBigDecimal("stock_value")));
                }
                return result;
            }
        }
    }

    public long countLevels(Connection c, String skuLike, Long warehouseId, Boolean lowOnly)
            throws SQLException {
        StringBuilder where = new StringBuilder(
                " WHERE EXISTS (SELECT 1 FROM stock_items si WHERE si.product_id = p.id");
        List<Object> params = new ArrayList<>();
        if (warehouseId != null) {
            where.append(" AND si.warehouse_id = ?");
            params.add(warehouseId);
        }
        if (Boolean.TRUE.equals(lowOnly)) {
            where.append(" AND si.qty_on_hand <= p.reorder_point");
        }
        where.append(")");
        if (skuLike != null && !skuLike.isBlank()) {
            where.append(" AND p.sku LIKE ?");
            params.add(skuLike.trim().toUpperCase() + "%");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(DISTINCT p.id) FROM products p"
                        + (Boolean.TRUE.equals(lowOnly) ? " WHERE p.reorder_point > 0" : "")
                        + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static StockItem map(ResultSet rs) throws SQLException {
        StockItem item = new StockItem();
        item.setId(SqlUtils.getLong(rs, "id"));
        item.setProductId(SqlUtils.getLong(rs, "product_id"));
        item.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        item.setQtyOnHand(rs.getBigDecimal("qty_on_hand"));
        item.setQtyReserved(rs.getBigDecimal("qty_reserved"));
        item.setAvgUnitCost(rs.getBigDecimal("avg_unit_cost"));
        item.setLastCountedAt(SqlUtils.getInstant(rs, "last_counted_at"));
        item.setVersion(rs.getInt("version"));
        item.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        item.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return item;
    }

    /** Ensure the table has a row for every (product, warehouse) - used by tests. */
    public int countRows(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM stock_items")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
