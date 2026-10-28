package com.ims.domain.customer;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for customers. */
public final class CustomerDao {

    private static final String COLUMNS = """
            SELECT id, code, name, customer_type, email, phone, address_line1, address_line2,
                   city, state_province, postal_code, country, credit_limit, notes,
                   is_active, version, created_at, updated_at
              FROM customers
            """;

    public Optional<Customer> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Customer> findByCode(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean codeExists(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM customers WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Customer> search(Connection c, String search, Boolean active,
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
                List<Customer> result = new ArrayList<>();
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
                "SELECT COUNT(*) FROM customers" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public long countSalesOrders(Connection c, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM sales_orders WHERE customer_id = ?")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Customer insert(Connection c, Customer customer) throws SQLException {
        String sql = """
                INSERT INTO customers (code, name, customer_type, email, phone,
                                       address_line1, address_line2, city, state_province,
                                       postal_code, country, credit_limit, notes, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, customer.getCode());
            ps.setString(2, customer.getName());
            ps.setString(3, customer.getCustomerType());
            SqlUtils.setNullableString(ps, 4, customer.getEmail());
            SqlUtils.setNullableString(ps, 5, customer.getPhone());
            SqlUtils.setNullableString(ps, 6, customer.getAddressLine1());
            SqlUtils.setNullableString(ps, 7, customer.getAddressLine2());
            SqlUtils.setNullableString(ps, 8, customer.getCity());
            SqlUtils.setNullableString(ps, 9, customer.getStateProvince());
            SqlUtils.setNullableString(ps, 10, customer.getPostalCode());
            SqlUtils.setNullableString(ps, 11, customer.getCountry());
            ps.setBigDecimal(12, customer.getCreditLimit());
            SqlUtils.setNullableString(ps, 13, customer.getNotes());
            ps.setBoolean(14, customer.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    customer.setId(keys.getLong(1));
                }
            }
            return customer;
        }
    }

    public boolean update(Connection c, Customer customer) throws SQLException {
        String sql = """
                UPDATE customers
                   SET name = ?, customer_type = ?, email = ?, phone = ?, address_line1 = ?,
                       address_line2 = ?, city = ?, state_province = ?, postal_code = ?,
                       country = ?, credit_limit = ?, notes = ?, is_active = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, customer.getName());
            ps.setString(2, customer.getCustomerType());
            SqlUtils.setNullableString(ps, 3, customer.getEmail());
            SqlUtils.setNullableString(ps, 4, customer.getPhone());
            SqlUtils.setNullableString(ps, 5, customer.getAddressLine1());
            SqlUtils.setNullableString(ps, 6, customer.getAddressLine2());
            SqlUtils.setNullableString(ps, 7, customer.getCity());
            SqlUtils.setNullableString(ps, 8, customer.getStateProvince());
            SqlUtils.setNullableString(ps, 9, customer.getPostalCode());
            SqlUtils.setNullableString(ps, 10, customer.getCountry());
            ps.setBigDecimal(11, customer.getCreditLimit());
            SqlUtils.setNullableString(ps, 12, customer.getNotes());
            ps.setBoolean(13, customer.isActive());
            ps.setLong(14, customer.getId());
            ps.setInt(15, customer.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM customers WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    static Customer map(ResultSet rs) throws SQLException {
        Customer customer = new Customer();
        customer.setId(SqlUtils.getLong(rs, "id"));
        customer.setCode(rs.getString("code"));
        customer.setName(rs.getString("name"));
        customer.setCustomerType(rs.getString("customer_type"));
        customer.setEmail(rs.getString("email"));
        customer.setPhone(rs.getString("phone"));
        customer.setAddressLine1(rs.getString("address_line1"));
        customer.setAddressLine2(rs.getString("address_line2"));
        customer.setCity(rs.getString("city"));
        customer.setStateProvince(rs.getString("state_province"));
        customer.setPostalCode(rs.getString("postal_code"));
        customer.setCountry(rs.getString("country"));
        customer.setCreditLimit(rs.getBigDecimal("credit_limit"));
        customer.setNotes(rs.getString("notes"));
        customer.setActive(rs.getBoolean("is_active"));
        customer.setVersion(rs.getInt("version"));
        customer.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        customer.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return customer;
    }
}
