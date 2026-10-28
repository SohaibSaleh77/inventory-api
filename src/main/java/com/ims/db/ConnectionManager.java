package com.ims.db;

import com.ims.common.exception.InternalErrorException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns the HikariCP pool. All connections come from here - never via
 * DriverManager in application code.
 */
public final class ConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final HikariDataSource dataSource;

    public ConnectionManager(String jdbcUrl, String username, String password,
                             int maxSize, int minIdle, long connectionTimeoutMs,
                             long idleTimeoutMs, long maxLifetimeMs,
                             long leakDetectionThresholdMs) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setLeakDetectionThreshold(leakDetectionThresholdMs);
        config.setPoolName("ims-pool");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        // Correctness over latency: fail fast on connection loss
        config.setInitializationFailTimeout(connectionTimeoutMs);
        this.dataSource = new HikariDataSource(config);
        log.info("Connection pool started (maxSize={}, minIdle={})", maxSize, minIdle);
    }

    public Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new InternalErrorException("Could not acquire a database connection", e);
        }
    }

    public boolean isHealthy() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Connection pool closed");
        }
    }
}
