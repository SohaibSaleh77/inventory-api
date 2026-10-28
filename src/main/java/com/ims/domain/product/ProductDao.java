package com.ims.domain.product;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for products. */
public final class ProductDao {

    private static final String COLUMNS = """
            SELECT id, sku, barcode, name, description, category_id, unit_of_measure,
                   standard_cost, list_price, reorder_point, reorder_qty, is_active,
                   version, created_at, updated_at
              FROM products
            """;

    public Optional<Product> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Product> findBySku(Connection c, String sku) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Batch lookup by ids (chunked IN queries) - used to fix N+1 patterns
     * in list endpoints. Missing ids are simply absent from the result.
     */
    public java.util.Map<Long, Product> findByIds(Connection c,
                                                  java.util.Collection<Long> ids)
            throws SQLException {
        java.util.Map<Long, Product> result = new java.util.HashMap<>();
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
                        Product product = map(rs);
                        result.put(product.getId(), product);
                    }
                }
            }
        }
        return result;
    }

    public Optional<Product> findByBarcode(Connection c, String barcode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE barcode = ?")) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean skuExists(Connection c, String sku) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM products WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean barcodeExists(Connection c, String barcode) throws SQLException {
        if (barcode == null) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM products WHERE barcode = ?")) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record ProductQuery(String search, Long categoryId, Boolean active,
                               String skuLike, String sortBy) {
    }

    public List<Product> search(Connection c, ProductQuery query, int limit, int offset)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query.search() != null && !query.search().isBlank()) {
            where.append(" AND (name LIKE ? OR sku LIKE ? OR description LIKE ?)");
            String like = "%" + query.search().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (query.skuLike() != null && !query.skuLike().isBlank()) {
            where.append(" AND sku LIKE ?");
            params.add(query.skuLike().trim() + "%");
        }
        if (query.categoryId() != null) {
            where.append(" AND category_id = ?");
            params.add(query.categoryId());
        }
        if (query.active() != null) {
            where.append(" AND is_active = ?");
            params.add(query.active());
        }
        String sort = switch (query.sortBy() == null ? "" : query.sortBy()) {
            case "name" -> "name";
            case "created" -> "created_at";
            default -> "sku";
        };
        String sql = COLUMNS + where + " ORDER BY " + sort + " LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Product> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countSearch(Connection c, ProductQuery query) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query.search() != null && !query.search().isBlank()) {
            where.append(" AND (name LIKE ? OR sku LIKE ? OR description LIKE ?)");
            String like = "%" + query.search().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (query.skuLike() != null && !query.skuLike().isBlank()) {
            where.append(" AND sku LIKE ?");
            params.add(query.skuLike().trim() + "%");
        }
        if (query.categoryId() != null) {
            where.append(" AND category_id = ?");
            params.add(query.categoryId());
        }
        if (query.active() != null) {
            where.append(" AND is_active = ?");
            params.add(query.active());
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM products" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Product insert(Connection c, Product p) throws SQLException {
        String sql = """
                INSERT INTO products (sku, barcode, name, description, category_id,
                                      unit_of_measure, standard_cost, list_price,
                                      reorder_point, reorder_qty, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, p.getSku());
            SqlUtils.setNullableString(ps, 2, p.getBarcode());
            ps.setString(3, p.getName());
            SqlUtils.setNullableString(ps, 4, p.getDescription());
            SqlUtils.setNullableLong(ps, 5, p.getCategoryId());
            ps.setString(6, p.getUnitOfMeasure());
            ps.setBigDecimal(7, p.getStandardCost());
            ps.setBigDecimal(8, p.getListPrice());
            ps.setBigDecimal(9, p.getReorderPoint());
            ps.setBigDecimal(10, p.getReorderQty());
            ps.setBoolean(11, p.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    p.setId(keys.getLong(1));
                }
            }
            return p;
        }
    }

    public boolean update(Connection c, Product p) throws SQLException {
        String sql = """
                UPDATE products
                   SET barcode = ?, name = ?, description = ?, category_id = ?,
                       unit_of_measure = ?, standard_cost = ?, list_price = ?,
                       reorder_point = ?, reorder_qty = ?, is_active = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            SqlUtils.setNullableString(ps, 1, p.getBarcode());
            ps.setString(2, p.getName());
            SqlUtils.setNullableString(ps, 3, p.getDescription());
            SqlUtils.setNullableLong(ps, 4, p.getCategoryId());
            ps.setString(5, p.getUnitOfMeasure());
            ps.setBigDecimal(6, p.getStandardCost());
            ps.setBigDecimal(7, p.getListPrice());
            ps.setBigDecimal(8, p.getReorderPoint());
            ps.setBigDecimal(9, p.getReorderQty());
            ps.setBoolean(10, p.isActive());
            ps.setLong(11, p.getId());
            ps.setInt(12, p.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    /** How many transactional rows reference this product (for delete checks). */
    public long countReferences(Connection c, long productId) throws SQLException {
        long total = 0;
        String[] tables = {"stock_movements", "purchase_order_lines", "sales_order_lines",
                "stock_adjustments", "stock_transfers", "supplier_items"};
        for (String table : tables) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE product_id = ?")) {
                ps.setLong(1, productId);
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
                "DELETE FROM products WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    static Product map(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(SqlUtils.getLong(rs, "id"));
        p.setSku(rs.getString("sku"));
        p.setBarcode(rs.getString("barcode"));
        p.setName(rs.getString("name"));
        p.setDescription(rs.getString("description"));
        p.setCategoryId(SqlUtils.getLong(rs, "category_id"));
        p.setUnitOfMeasure(rs.getString("unit_of_measure"));
        p.setStandardCost(rs.getBigDecimal("standard_cost"));
        p.setListPrice(rs.getBigDecimal("list_price"));
        p.setReorderPoint(rs.getBigDecimal("reorder_point"));
        p.setReorderQty(rs.getBigDecimal("reorder_qty"));
        p.setActive(rs.getBoolean("is_active"));
        p.setVersion(rs.getInt("version"));
        p.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        p.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return p;
    }
}
