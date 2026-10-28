package com.ims.domain.warehouse;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for warehouses. */
public final class WarehouseDao {

    private static final String COLUMNS = """
            SELECT id, code, name, address_line1, city, state_province, postal_code,
                   country, is_active, version, created_at, updated_at
              FROM warehouses
            """;

    public Optional<Warehouse> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Warehouse> findByCode(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Batch lookup by ids (chunked IN queries) - used to fix N+1 patterns
     * in list endpoints. Missing ids are simply absent from the result.
     */
    public java.util.Map<Long, Warehouse> findByIds(Connection c,
                                                    java.util.Collection<Long> ids)
            throws SQLException {
        java.util.Map<Long, Warehouse> result = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Long> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        for (int from = 0; from < distinct.size(); from += 500) {
            List<Long> chunk = distinct.subList(from,
                    Math.min(from + 500, distinct.size()));
            String placeholders = String.join(",",
                    java.util.Collections.nCopies(chunk.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement(
                    COLUMNS + " WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Warehouse warehouse = map(rs);
                        result.put(warehouse.getId(), warehouse);
                    }
                }
            }
        }
        return result;
    }

    public boolean codeExists(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM warehouses WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Warehouse> findAll(Connection c, boolean includeInactive) throws SQLException {
        String sql = COLUMNS + (includeInactive ? " ORDER BY code"
                : " WHERE is_active = 1 ORDER BY code");
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Warehouse> result = new ArrayList<>();
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        }
    }

    public Warehouse insert(Connection c, Warehouse w) throws SQLException {
        String sql = """
                INSERT INTO warehouses (code, name, address_line1, city, state_province,
                                        postal_code, country, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, w.getCode());
            ps.setString(2, w.getName());
            SqlUtils.setNullableString(ps, 3, w.getAddressLine1());
            SqlUtils.setNullableString(ps, 4, w.getCity());
            SqlUtils.setNullableString(ps, 5, w.getStateProvince());
            SqlUtils.setNullableString(ps, 6, w.getPostalCode());
            SqlUtils.setNullableString(ps, 7, w.getCountry());
            ps.setBoolean(8, w.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    w.setId(keys.getLong(1));
                }
            }
            return w;
        }
    }

    public boolean update(Connection c, Warehouse w) throws SQLException {
        String sql = """
                UPDATE warehouses
                   SET name = ?, address_line1 = ?, city = ?, state_province = ?,
                       postal_code = ?, country = ?, is_active = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, w.getName());
            SqlUtils.setNullableString(ps, 2, w.getAddressLine1());
            SqlUtils.setNullableString(ps, 3, w.getCity());
            SqlUtils.setNullableString(ps, 4, w.getStateProvince());
            SqlUtils.setNullableString(ps, 5, w.getPostalCode());
            SqlUtils.setNullableString(ps, 6, w.getCountry());
            ps.setBoolean(7, w.isActive());
            ps.setLong(8, w.getId());
            ps.setInt(9, w.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    /** Referenced by stock or documents? (FK RESTRICT blocks deletes anyway - this is for clean 409s.) */
    public long countReferences(Connection c, long warehouseId) throws SQLException {
        long total = 0;
        String[] tables = {"stock_items", "purchase_orders", "sales_orders",
                "stock_adjustments", "stock_transfers"};
        for (String table : tables) {
            String col = switch (table) {
                case "stock_transfers" -> "(from_warehouse_id = ? OR to_warehouse_id = ?)";
                default -> "warehouse_id = ?";
            };
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + col)) {
                ps.setLong(1, warehouseId);
                if (col.contains("OR")) {
                    ps.setLong(2, warehouseId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total += rs.getLong(1);
                }
            }
        }
        return total;
    }

    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM warehouses WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    static Warehouse map(ResultSet rs) throws SQLException {
        Warehouse w = new Warehouse();
        w.setId(SqlUtils.getLong(rs, "id"));
        w.setCode(rs.getString("code"));
        w.setName(rs.getString("name"));
        w.setAddressLine1(rs.getString("address_line1"));
        w.setCity(rs.getString("city"));
        w.setStateProvince(rs.getString("state_province"));
        w.setPostalCode(rs.getString("postal_code"));
        w.setCountry(rs.getString("country"));
        w.setActive(rs.getBoolean("is_active"));
        w.setVersion(rs.getInt("version"));
        w.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        w.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return w;
    }
}
