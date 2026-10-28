package com.ims.domain.category;

import com.ims.common.util.SqlUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC DAO for categories. */
public final class CategoryDao {

    private static final String COLUMNS = """
            SELECT id, parent_id, code, name, description, is_active, version,
                   created_at, updated_at
              FROM categories
            """;

    public Optional<Category> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Category> findByCode(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean codeExists(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM categories WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean nameExistsUnderParent(Connection c, Long parentId, String name)
            throws SQLException {
        String sql = parentId == null
                ? "SELECT 1 FROM categories WHERE parent_id IS NULL AND name = ?"
                : "SELECT 1 FROM categories WHERE parent_id = ? AND name = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (parentId == null) {
                ps.setString(1, name);
            } else {
                ps.setLong(1, parentId);
                ps.setString(2, name);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Category> findAll(Connection c, boolean includeInactive) throws SQLException {
        String sql = COLUMNS + (includeInactive ? " ORDER BY parent_id, name"
                : " WHERE is_active = 1 ORDER BY parent_id, name");
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Category> result = new ArrayList<>();
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        }
    }

    public List<Category> findChildren(Connection c, long parentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                COLUMNS + " WHERE parent_id = ? ORDER BY name")) {
            ps.setLong(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Category> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countProducts(Connection c, long categoryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM products WHERE category_id = ?")) {
            ps.setLong(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public long countChildren(Connection c, long categoryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM categories WHERE parent_id = ?")) {
            ps.setLong(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** All ancestors of a category (for cycle detection), ordered root-first. */
    public List<Long> ancestorIds(Connection c, long categoryId) throws SQLException {
        List<Long> ancestors = new ArrayList<>();
        Long current = categoryId;
        int depthGuard = 0;
        while (current != null && depthGuard++ < 100) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT parent_id FROM categories WHERE id = ?")) {
                ps.setLong(1, current);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        break;
                    }
                    current = SqlUtils.getLong(rs, "parent_id");
                    if (current != null) {
                        ancestors.add(current);
                    }
                }
            }
        }
        return ancestors;
    }

    public Category insert(Connection c, Category category) throws SQLException {
        String sql = """
                INSERT INTO categories (parent_id, code, name, description, is_active)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            SqlUtils.setNullableLong(ps, 1, category.getParentId());
            ps.setString(2, category.getCode());
            ps.setString(3, category.getName());
            SqlUtils.setNullableString(ps, 4, category.getDescription());
            ps.setBoolean(5, category.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    category.setId(keys.getLong(1));
                }
            }
            return category;
        }
    }

    public boolean update(Connection c, Category category) throws SQLException {
        String sql = """
                UPDATE categories
                   SET parent_id = ?, name = ?, description = ?, is_active = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            SqlUtils.setNullableLong(ps, 1, category.getParentId());
            ps.setString(2, category.getName());
            SqlUtils.setNullableString(ps, 3, category.getDescription());
            ps.setBoolean(4, category.isActive());
            ps.setLong(5, category.getId());
            ps.setInt(6, category.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM categories WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    static Category map(ResultSet rs) throws SQLException {
        Category category = new Category();
        category.setId(SqlUtils.getLong(rs, "id"));
        category.setParentId(SqlUtils.getLong(rs, "parent_id"));
        category.setCode(rs.getString("code"));
        category.setName(rs.getString("name"));
        category.setDescription(rs.getString("description"));
        category.setActive(rs.getBoolean("is_active"));
        category.setVersion(rs.getInt("version"));
        category.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        category.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return category;
    }

    /** Fetch all in one query, preserve order, used to build the tree. */
    public Map<Long, Category> findAllById(Connection c) throws SQLException {
        Map<Long, Category> byId = new LinkedHashMap<>();
        for (Category category : findAll(c, true)) {
            byId.put(category.getId(), category);
        }
        return byId;
    }

    public Instant now() {
        return Instant.now();
    }
}
