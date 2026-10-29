package com.ims.integration;

import com.ims.db.DatabaseMigrator;
import com.ims.testing.IntegrationTestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrator semantics: idempotent re-runs, checksum verification, pending
 * count. Runs against the (already migrated) test schema via the shared env.
 */
class DatabaseMigratorIT {

    static IntegrationTestEnv env;
    static DatabaseMigrator migrator;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        migrator = new DatabaseMigrator(env.connectionManager);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void reRunningMigrationsIsIdempotent() {
        DatabaseMigrator.MigrationResult first = migrator.migrate();
        assertEquals(0, first.applied(), "schema is already migrated by the env");
        assertEquals(0, migrator.pendingCount());
        DatabaseMigrator.MigrationResult second = migrator.migrate();
        assertEquals(0, second.applied());
    }

    @Test
    void allVersionsRecordedWithChecksums() throws Exception {
        try (Connection c = env.connectionManager.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version, description, checksum FROM schema_migrations "
                             + "ORDER BY version")) {
            int count = 0;
            while (rs.next()) {
                assertTrue(rs.getString("checksum").matches("[0-9a-f]{64}"),
                        "checksum must be sha-256 hex");
                count++;
            }
            assertEquals(9, count, "V001..V009 recorded");
        }
    }

    @Test
    void migrationHistorySurvivesTruncation() {
        // truncateAll() must never wipe schema_migrations
        env.truncateAll();
        assertEquals(0, migrator.pendingCount());
    }

    @Test
    void appendOnlyLedgerProtectedByTrigger() {
        // create a real ledger row through the business flow
        var users = com.ims.testing.TestUsers.create(env);
        var fx = new com.ims.testing.Fixtures(env.app);
        var cat = fx.category(users.manager, "TRG-CAT", null);
        var sup = fx.supplier(users.manager, "TRG-SUP");
        var wh = fx.warehouse(users.manager, "TRG-WH");
        var prd = fx.product(users.manager, "TRG-SKU", cat.code(),
                java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, null);
        fx.receiveStock(users.manager, sup.code(), wh.code(), prd.sku(), 5,
                java.math.BigDecimal.ONE);

        // the immutability trigger must reject UPDATE (and DELETE) of that row
        com.ims.common.exception.ConflictException updateRejected =
                assertThrows(com.ims.common.exception.ConflictException.class,
                        () -> env.transactions.inTransaction(conn -> {
                            try (var ps = conn.prepareStatement(
                                    "UPDATE stock_movements SET note = 'hack'")) {
                                ps.executeUpdate();
                            }
                            return null;
                        }));
        assertTrue(updateRejected.getMessage().contains("append-only"),
                "message should point at the ledger rule, got: "
                        + updateRejected.getMessage());
        com.ims.common.exception.ConflictException deleteRejected =
                assertThrows(com.ims.common.exception.ConflictException.class,
                        () -> env.transactions.inTransaction(conn -> {
                            try (var ps = conn.prepareStatement(
                                    "DELETE FROM stock_movements")) {
                                ps.executeUpdate();
                            }
                            return null;
                        }));
        assertTrue(deleteRejected.getMessage().contains("append-only"));
    }
}
