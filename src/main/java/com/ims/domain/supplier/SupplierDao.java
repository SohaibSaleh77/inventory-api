package com.ims.domain.supplier;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for suppliers and their catalog items. */
public final class SupplierDao {

    private static final String COLUMNS = """
            SELECT id, code, name, contact_name, email, phone, address_line1, address_line2,
                   city, state_province, postal_code, country, payment_terms_days, rating,
                   notes, is_active, version, created_at, updated_at
              FROM suppliers
            """;

    public Optional<Supplier> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Supplier> findByCode(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean codeExists(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM suppliers WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Supplier> search(Connection c, String search, Boolean active,
                                 int limit, int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (code LIKE ? OR name LIKE ? OR email LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (active != null) {
            where.append(" AND is_active = ?");
            params.add(active);
        }
        String sql = COLUMNS + where + " ORDER BY code LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Supplier> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countSearch(Connection c, String search, Boolean active) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (code LIKE ? OR name LIKE ? OR email LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (active != null) {
            where.append(" AND is_active = ?");
            params.add(active);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM suppliers" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public long countPurchaseOrders(Connection c, long supplierId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM purchase_orders WHERE supplier_id = ?")) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Supplier insert(Connection c, Supplier s) throws SQLException {
        String sql = """
                INSERT INTO suppliers (code, name, contact_name, email, phone, address_line1,
                                       address_line2, city, state_province, postal_code,
                                       country, payment_terms_days, rating, notes, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, s.getCode());
            ps.setString(2, s.getName());
            SqlUtils.setNullableString(ps, 3, s.getContactName());
            SqlUtils.setNullableString(ps, 4, s.getEmail());
            SqlUtils.setNullableString(ps, 5, s.getPhone());
            SqlUtils.setNullableString(ps, 6, s.getAddressLine1());
            SqlUtils.setNullableString(ps, 7, s.getAddressLine2());
            SqlUtils.setNullableString(ps, 8, s.getCity());
            SqlUtils.setNullableString(ps, 9, s.getStateProvince());
            SqlUtils.setNullableString(ps, 10, s.getPostalCode());
            SqlUtils.setNullableString(ps, 11, s.getCountry());
            ps.setInt(12, s.getPaymentTermsDays());
            SqlUtils.setNullableLong(ps, 13, s.getRating() == null ? null : s.getRating().longValue());
            SqlUtils.setNullableString(ps, 14, s.getNotes());
            ps.setBoolean(15, s.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    s.setId(keys.getLong(1));
                }
            }
            return s;
        }
    }

    public boolean update(Connection c, Supplier s) throws SQLException {
        String sql = """
                UPDATE suppliers
                   SET name = ?, contact_name = ?, email = ?, phone = ?, address_line1 = ?,
                       address_line2 = ?, city = ?, state_province = ?, postal_code = ?,
                       country = ?, payment_terms_days = ?, rating = ?, notes = ?,
                       is_active = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.getName());
            SqlUtils.setNullableString(ps, 2, s.getContactName());
            SqlUtils.setNullableString(ps, 3, s.getEmail());
            SqlUtils.setNullableString(ps, 4, s.getPhone());
            SqlUtils.setNullableString(ps, 5, s.getAddressLine1());
            SqlUtils.setNullableString(ps, 6, s.getAddressLine2());
            SqlUtils.setNullableString(ps, 7, s.getCity());
            SqlUtils.setNullableString(ps, 8, s.getStateProvince());
            SqlUtils.setNullableString(ps, 9, s.getPostalCode());
            SqlUtils.setNullableString(ps, 10, s.getCountry());
            ps.setInt(11, s.getPaymentTermsDays());
            SqlUtils.setNullableLong(ps, 12, s.getRating() == null ? null : s.getRating().longValue());
            SqlUtils.setNullableString(ps, 13, s.getNotes());
            ps.setBoolean(14, s.isActive());
            ps.setLong(15, s.getId());
            ps.setInt(16, s.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM suppliers WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    // ---- supplier items ----

    public List<SupplierItem> findItems(Connection c, long supplierId) throws SQLException {
        String sql = """
                SELECT id, supplier_id, product_id, supplier_sku, unit_cost, is_preferred,
                       lead_time_days, version, created_at, updated_at
                  FROM supplier_items
                 WHERE supplier_id = ?
                 ORDER BY product_id
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SupplierItem> result = new ArrayList<>();
                while (rs.next()) {
                    SupplierItem item = new SupplierItem();
                    item.setId(SqlUtils.getLong(rs, "id"));
                    item.setSupplierId(SqlUtils.getLong(rs, "supplier_id"));
                    item.setProductId(SqlUtils.getLong(rs, "product_id"));
                    item.setSupplierSku(rs.getString("supplier_sku"));
                    item.setUnitCost(rs.getBigDecimal("unit_cost"));
                    item.setPreferred(rs.getBoolean("is_preferred"));
                    item.setLeadTimeDays(rs.getInt("lead_time_days"));
                    item.setVersion(rs.getInt("version"));
                    item.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
                    item.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
                    result.add(item);
                }
                return result;
            }
        }
    }

    public Optional<SupplierItem> findItem(Connection c, long supplierId, long productId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, supplier_id, product_id, supplier_sku, unit_cost, is_preferred,
                       lead_time_days, version, created_at, updated_at
                  FROM supplier_items
                 WHERE supplier_id = ? AND product_id = ?
                """)) {
            ps.setLong(1, supplierId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapItem(rs)) : Optional.empty();
            }
        }
    }

    public SupplierItem upsertItem(Connection c, SupplierItem item) throws SQLException {
        String sql = """
                INSERT INTO supplier_items (supplier_id, product_id, supplier_sku, unit_cost,
                                            is_preferred, lead_time_days)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                     supplier_sku = VALUES(supplier_sku),
                     unit_cost = VALUES(unit_cost),
                     is_preferred = VALUES(is_preferred),
                     lead_time_days = VALUES(lead_time_days),
                     version = version + 1
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, item.getSupplierId());
            ps.setLong(2, item.getProductId());
            SqlUtils.setNullableString(ps, 3, item.getSupplierSku());
            ps.setBigDecimal(4, item.getUnitCost());
            ps.setBoolean(5, item.isPreferred());
            ps.setInt(6, item.getLeadTimeDays());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    item.setId(keys.getLong(1));
                }
            }
            return item;
        }
    }

    public boolean deleteItem(Connection c, long supplierId, long productId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM supplier_items
                 WHERE supplier_id = ? AND product_id = ?
                """)) {
            ps.setLong(1, supplierId);
            ps.setLong(2, productId);
            return ps.executeUpdate() == 1;
        }
    }

    private SupplierItem mapItem(ResultSet rs) throws SQLException {
        SupplierItem item = new SupplierItem();
        item.setId(SqlUtils.getLong(rs, "id"));
        item.setSupplierId(SqlUtils.getLong(rs, "supplier_id"));
        item.setProductId(SqlUtils.getLong(rs, "product_id"));
        item.setSupplierSku(rs.getString("supplier_sku"));
        item.setUnitCost(rs.getBigDecimal("unit_cost"));
        item.setPreferred(rs.getBoolean("is_preferred"));
        item.setLeadTimeDays(rs.getInt("lead_time_days"));
        item.setVersion(rs.getInt("version"));
        item.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        item.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return item;
    }

    static Supplier map(ResultSet rs) throws SQLException {
        Supplier s = new Supplier();
        s.setId(SqlUtils.getLong(rs, "id"));
        s.setCode(rs.getString("code"));
        s.setName(rs.getString("name"));
        s.setContactName(rs.getString("contact_name"));
        s.setEmail(rs.getString("email"));
        s.setPhone(rs.getString("phone"));
        s.setAddressLine1(rs.getString("address_line1"));
        s.setAddressLine2(rs.getString("address_line2"));
        s.setCity(rs.getString("city"));
        s.setStateProvince(rs.getString("state_province"));
        s.setPostalCode(rs.getString("postal_code"));
        s.setCountry(rs.getString("country"));
        s.setPaymentTermsDays(rs.getInt("payment_terms_days"));
        Integer rating = SqlUtils.getInt(rs, "rating");
        s.setRating(rating);
        s.setNotes(rs.getString("notes"));
        s.setActive(rs.getBoolean("is_active"));
        s.setVersion(rs.getInt("version"));
        s.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        s.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return s;
    }
}
