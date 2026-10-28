package com.ims.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free schema migration runner (Flyway-inspired).
 *
 * <p>Discovers classpath:/db/migration/Vnnn__description.sql resources,
 * applies unseen versions in order inside a transaction per migration, and
 * records each applied version with a SHA-256 checksum. Applied checksums are
 * verified so accidental edits to already-applied files fail fast.
 */
public final class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)__(.+)\\.sql");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("V\\d+__.*\\.sql");

    public record Migration(int version, String description, String fileName) {
        public String versionLabel() {
            return version + " (" + description + ")";
        }
    }

    public record MigrationResult(int applied, List<Migration> appliedMigrations,
                                  List<Migration> skipped) {
    }

    private final ConnectionManager connectionManager;

    public DatabaseMigrator(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public MigrationResult migrate() {
        List<Migration> discovered = discoverMigrations();
        List<Migration> appliedMigrations = new ArrayList<>();
        try (Connection connection = connectionManager.getConnection()) {
            ensureHistoryTable(connection);
            for (Migration migration : discovered) {
                if (alreadyApplied(connection, migration.version())) {
                    verifyChecksum(connection, migration);
                    continue;
                }
                apply(connection, migration);
                appliedMigrations.add(migration);
            }
            return new MigrationResult(appliedMigrations.size(), appliedMigrations, List.of());
        } catch (SQLException e) {
            throw SQLExceptionMapper.translate("migrate", e);
        }
    }

    /**
     * Returns the number of pending migrations (used by health checks / CI).
     * Never performs DDL: the application user typically has DML-only grants.
     */
    public long pendingCount() {
        List<Migration> discovered = discoverMigrations();
        try (Connection connection = connectionManager.getConnection()) {
            long pending = 0;
            for (Migration migration : discovered) {
                if (!alreadyApplied(connection, migration.version())) {
                    pending++;
                }
            }
            return pending;
        } catch (SQLException e) {
            // schema_migrations table missing => everything is pending
            if (e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState())) {
                return discovered.size();
            }
            throw SQLExceptionMapper.translate("count pending migrations", e);
        }
    }

    private List<Migration> discoverMigrations() {
        try {
            List<Migration> migrations = new ArrayList<>();
            var resources = listMigrationResources();
            for (String resourceName : resources) {
                Matcher matcher = VERSION_PATTERN.matcher(resourceName);
                if (matcher.matches()) {
                    migrations.add(new Migration(
                            Integer.parseInt(matcher.group(1)),
                            matcher.group(2).replace('_', ' '),
                            resourceName));
                }
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new IllegalStateException("Could not discover migration resources", e);
        }
    }

    private List<String> listMigrationResources() throws IOException {
        List<String> names = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        java.net.URL dirUrl = cl.getResource("db/migration");

        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            // exploded classpath (target/classes): read the directory listing
            try (InputStream is = cl.getResourceAsStream("db/migration")) {
                if (is == null) {
                    throw new IOException("db/migration not readable on classpath");
                }
                String listing = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                for (String token : listing.split("\\s+")) {
                    if (RESOURCE_PATTERN.matcher(token).matches()) {
                        names.add(token);
                    }
                }
            }
        } else {
            // packaged classpath (jar): enumerate entries of every classpath jar
            String[] classpathEntries = System.getProperty("java.class.path", "")
                    .split(java.io.File.pathSeparator);
            for (String entry : classpathEntries) {
                java.io.File file = new java.io.File(entry);
                if (!file.isFile() || !entry.endsWith(".jar")) {
                    continue;
                }
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                    jar.stream().forEach(e -> {
                        String name = e.getName();
                        if (name.startsWith("db/migration/")) {
                            String fileName = name.substring("db/migration/".length());
                            if (RESOURCE_PATTERN.matcher(fileName).matches()) {
                                names.add(fileName);
                            }
                        }
                    });
                } catch (IOException jarError) {
                    // unopenable classpath entry - skip it
                }
            }
        }
        if (names.isEmpty()) {
            throw new IOException("No migration files found on classpath "
                    + "(looked in db/migration/)");
        }
        return names;
    }

    private void ensureHistoryTable(Connection connection) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version      INT          NOT NULL PRIMARY KEY,
                    description  VARCHAR(200) NOT NULL,
                    file_name    VARCHAR(200) NOT NULL,
                    checksum     CHAR(64)     NOT NULL,
                    executed_by  VARCHAR(64)  NOT NULL DEFAULT 'ims-migrator',
                    executed_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    execution_ms INT          NOT NULL DEFAULT 0
                ) ENGINE = InnoDB
                """;
        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
        }
    }

    private boolean alreadyApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void verifyChecksum(Connection connection, Migration migration) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT checksum FROM schema_migrations WHERE version = ?")) {
            ps.setInt(1, migration.version());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String recorded = rs.getString(1);
                    String actual = checksum(readResource(migration.fileName()));
                    if (!recorded.equalsIgnoreCase(actual)) {
                        throw new IllegalStateException(
                                "Migration " + migration.fileName()
                                        + " was modified after being applied (checksum mismatch)."
                                        + " Restore the original file or add a new migration.");
                    }
                }
            }
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        String sql = readResource(migration.fileName());
        String checksum = checksum(sql);
        long started = System.nanoTime();

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String statement : splitStatements(sql)) {
                try (Statement st = connection.createStatement()) {
                    st.execute(statement);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO schema_migrations (version, description, file_name, checksum, execution_ms)
                    VALUES (?, ?, ?, ?, ?)""")) {
                ps.setInt(1, migration.version());
                ps.setString(2, migration.description());
                ps.setString(3, migration.fileName());
                ps.setString(4, checksum);
                ps.setInt(5, (int) ((System.nanoTime() - started) / 1_000_000));
                ps.executeUpdate();
            }
            connection.commit();
            log.info("Applied migration V{} __ {}", migration.version(), migration.description());
        } catch (Exception e) {
            connection.rollback();
            if (e instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new SQLException("Migration failed: " + e.getMessage(), e);
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    /**
     * Statement splitter: honours single-quoted strings and comments, splits
     * on ';'. Multi-statement trigger bodies are intentionally not supported
     * (single-statement bodies are - see V003).
     */
    public static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char prev = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (prev == '*' && c == '/') {
                    inBlockComment = false;
                }
                prev = c;
                continue;
            }
            if (!inSingleQuote && c == '-' && next == '-') {
                inLineComment = true;
                continue;
            }
            if (!inSingleQuote && c == '/' && next == '*') {
                inBlockComment = true;
                prev = c;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            if (c == ';' && !inSingleQuote) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
            prev = c;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private String readResource(String resourceName) {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("db/migration/" + resourceName)) {
            if (is == null) {
                throw new IllegalStateException("Migration resource not found: " + resourceName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read migration " + resourceName, e);
        }
    }

    private String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
