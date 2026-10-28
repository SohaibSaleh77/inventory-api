package com.ims.domain.user;

import com.ims.common.util.PageRequest;
import com.ims.common.util.SqlUtils;
import com.ims.security.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for users. Every method takes the connection explicitly. */
public final class UserDao {

    private static final String SELECT_COLUMNS = """
            SELECT id, username, email, password_hash, full_name, role, status,
                   failed_login_attempts, locked_until, password_changed_at, last_login_at,
                   version, created_at, updated_at
              FROM users
            """;

    public Optional<User> findById(Connection c, long id) throws SQLException {
        String sql = SELECT_COLUMNS + " WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<User> findByUsername(Connection c, String username) throws SQLException {
        String sql = SELECT_COLUMNS + " WHERE username = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<User> findByEmail(Connection c, String email) throws SQLException {
        String sql = SELECT_COLUMNS + " WHERE email = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean usernameExists(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean emailExists(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long count(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public List<User> search(Connection c, String search, Role role, String status,
                             PageRequest page) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (username LIKE ? OR full_name LIKE ? OR email LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            where.append(" AND role = ?");
            params.add(role.name());
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        String sql = SELECT_COLUMNS + where
                + " ORDER BY id " + page.sortDir() + " LIMIT ? OFFSET ?";
        params.add(page.size());
        params.add(page.offset());
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(map(rs));
                }
                return users;
            }
        }
    }

    public long countSearch(Connection c, String search, Role role, String status)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (username LIKE ? OR full_name LIKE ? OR email LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            where.append(" AND role = ?");
            params.add(role.name());
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM users" + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public User insert(Connection c, User user) throws SQLException {
        String sql = """
                INSERT INTO users (username, email, password_hash, full_name, role, status,
                                   password_changed_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, user.getFullName());
            ps.setString(5, user.getRole().name());
            ps.setString(6, user.getStatus());
            SqlUtils.setNullableTimestamp(ps, 7, user.getPasswordChangedAt());
            SqlUtils.setNullableTimestamp(ps, 8, user.getLastLoginAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    user.setId(keys.getLong(1));
                }
            }
            return user;
        }
    }

    /** Full update guarded by optimistic locking (version). */
    public boolean update(Connection c, User user) throws SQLException {
        String sql = """
                UPDATE users
                   SET email = ?, full_name = ?, role = ?, status = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.getEmail());
            ps.setString(2, user.getFullName());
            ps.setString(3, user.getRole().name());
            ps.setString(4, user.getStatus());
            ps.setLong(5, user.getId());
            ps.setInt(6, user.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean updatePassword(Connection c, long id, String passwordHash,
                                  Instant changedAt) throws SQLException {
        String sql = """
                UPDATE users
                   SET password_hash = ?, password_changed_at = ?,
                       failed_login_attempts = 0, locked_until = NULL
                 WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            SqlUtils.setNullableTimestamp(ps, 2, changedAt);
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean registerSuccessfulLogin(Connection c, long id) throws SQLException {
        String sql = """
                UPDATE users
                   SET failed_login_attempts = 0, locked_until = NULL,
                       last_login_at = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** Returns the new attempt count, or -1 when the update did not apply. */
    public int registerFailedLogin(Connection c, User user, int maxAttempts,
                                   Duration lockout) throws SQLException {
        int attempts = user.getFailedLoginAttempts() + 1;
        boolean lock = attempts >= maxAttempts;
        String sql = """
                UPDATE users
                   SET failed_login_attempts = ?,
                       locked_until = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, attempts);
            SqlUtils.setNullableTimestamp(ps, 2,
                    lock ? Instant.now().plus(lockout) : null);
            ps.setLong(3, user.getId());
            return ps.executeUpdate() == 1 ? attempts : -1;
        }
    }

    public boolean setStatus(Connection c, long id, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        }
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    static User map(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(SqlUtils.getLong(rs, "id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.setRole(Role.valueOf(rs.getString("role")));
        user.setStatus(rs.getString("status"));
        user.setFailedLoginAttempts(rs.getInt("failed_login_attempts"));
        user.setLockedUntil(SqlUtils.getInstant(rs, "locked_until"));
        user.setPasswordChangedAt(SqlUtils.getInstant(rs, "password_changed_at"));
        user.setLastLoginAt(SqlUtils.getInstant(rs, "last_login_at"));
        user.setVersion(rs.getInt("version"));
        user.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        user.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return user;
    }
}
