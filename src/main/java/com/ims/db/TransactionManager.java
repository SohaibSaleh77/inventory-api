package com.ims.db;

import com.ims.common.exception.InternalErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Programmatic transaction scoping without framework magic.
 *
 * <p>Semantics: REQUIRED - joins an existing transaction when one is active
 * on the current thread, otherwise begins a new one. DAOs receive the
 * {@link Connection} explicitly from services, so the transaction boundary is
 * always visible in code and impossible to use accidentally outside a scope.
 *
 * <p>Deadlock handling: when a NEW transaction is the deadlock victim
 * (MySQL SQLState 40001 - the whole transaction is rolled back by InnoDB),
 * the closure is re-executed up to {@code deadlockMaxRetries} times with a
 * short jittered backoff. Nested/joined scopes are never retried (the owner
 * of the outer transaction performs the retry). Lock-wait timeouts are NOT
 * retried: MySQL 8 with the default innodb_rollback_on_timeout=OFF rolls
 * back only the statement, leaving the transaction in a half-applied state
 * that must not be silently re-run.
 *
 * <p>All mutations of more than one row MUST run inside a transaction; the
 * service-layer tests enforce this by asserting ledger/stock consistency.
 */
public final class TransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    private final ConnectionManager connectionManager;
    private final int deadlockMaxRetries;

    public TransactionManager(ConnectionManager connectionManager) {
        this(connectionManager, 0);
    }

    public TransactionManager(ConnectionManager connectionManager,
                              int deadlockMaxRetries) {
        this.connectionManager = connectionManager;
        this.deadlockMaxRetries = Math.max(0, deadlockMaxRetries);
    }

    /** Functional interface allowing checked SQLException inside scopes. */
    @FunctionalInterface
    public interface SqlCall<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * Run inside a transaction (REQUIRED semantics). Reuses the transaction of
     * the current thread when present - this is how DAO-level helpers compose
     * into larger atomic operations. Void bodies simply {@code return null;}.
     */
    public <T> T inTransaction(SqlCall<T> call) {
        Connection existing = CURRENT.get();
        if (existing != null) {
            try {
                return call.execute(existing);
            } catch (SQLException e) {
                throw translate(e);
            }
        }

        int attempt = 0;
        while (true) {
            try {
                return runNewTransaction(call);
            } catch (DeadlockRollbackException e) {
                if (attempt >= deadlockMaxRetries) {
                    throw translate(e.sqlException());
                }
                attempt++;
                long backoffMs = 20L + ThreadLocalRandom.current().nextInt(80);
                log.warn("Deadlock victim (attempt {}, retrying in {} ms): {}",
                        attempt, backoffMs, e.sqlException().getMessage());
                sleep(backoffMs);
            }
        }
    }

    private <T> T runNewTransaction(SqlCall<T> call) {
        Connection connection = connectionManager.getConnection();
        boolean committed = false;
        try {
            connection.setAutoCommit(false);
            CURRENT.set(connection);
            T result = call.execute(connection);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException e) {
            rollback(connection);
            if (e instanceof SQLException sql) {
                if (isDeadlock(sql)) {
                    throw new DeadlockRollbackException(sql);
                }
                throw translate(sql);
            }
            throw (RuntimeException) e;
        } finally {
            CURRENT.remove();
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // connection is about to be returned/closed anyway
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Hikari proxies: close returns to pool
            }
            if (committed) {
                log.trace("Transaction committed");
            }
        }
    }

    /** Short-lived read connection when no transaction is active. If a
     * transaction IS active, joins it so read-your-writes works inside a tx. */
    public <T> T withConnection(SqlCall<T> call) {
        Connection existing = CURRENT.get();
        if (existing != null) {
            try {
                return call.execute(existing);
            } catch (SQLException e) {
                throw translate(e);
            }
        }
        try (Connection connection = connectionManager.getConnection()) {
            return call.execute(connection);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    public boolean isTransactionActive() {
        return CURRENT.get() != null;
    }

    /** Exposed for DAOs that must run outside a pool-managed scope (tests only). */
    public Connection currentTransaction() {
        Connection c = CURRENT.get();
        if (c == null) {
            throw new IllegalStateException("No active transaction on this thread");
        }
        return c;
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.error("Rollback failed - connection will be discarded by the pool", e);
        }
    }

    private RuntimeException translate(SQLException e) {
        return SQLExceptionMapper.translate("transaction", e);
    }

    private static boolean isDeadlock(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("40001");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Internal marker: the whole transaction was rolled back - safe to retry. */
    private static final class DeadlockRollbackException extends RuntimeException {
        private final transient SQLException sqlException;

        DeadlockRollbackException(SQLException cause) {
            super(cause);
            this.sqlException = cause;
        }

        SQLException sqlException() {
            return sqlException;
        }
    }
}
