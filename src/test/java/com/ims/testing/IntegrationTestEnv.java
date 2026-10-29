package com.ims.testing;

import com.ims.db.DatabaseMigrator;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

/**
 * One database instance shared by the whole integration-test JVM:
 * runs migrations once, exposes the shared pool/transaction manager, and
 * provides table truncation for test isolation.
 */
public final class IntegrationTestEnv implements BeforeAllCallback,
        ExtensionContext.Store.CloseableResource {

    private static volatile IntegrationTestEnv instance;

    public final com.ims.db.ConnectionManager connectionManager;
    public final com.ims.db.TransactionManager transactions;
    public final com.ims.db.DocumentNumbers documentNumbers;
    public final com.ims.core.ApplicationContext app;

    private IntegrationTestEnv() {
        String url = System.getProperty("db.url",
                "jdbc:mysql://127.0.0.1:3306/inventory_db_test?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL");
        String user = System.getProperty("db.username", "ims_test");
        String password = System.getProperty("db.password");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "db.password system property (or IMS_TEST_DB_PASSWORD env) is required "
                            + "for integration tests");
        }
        this.connectionManager = new com.ims.db.ConnectionManager(url, user, password,
                24, 4, 8000, 300000, 1500000, 0);
        this.transactions = new com.ims.db.TransactionManager(connectionManager);

        // Schema: apply migrations once per JVM
        DatabaseMigrator migrator = new DatabaseMigrator(connectionManager);
        migrator.migrate();

        // Full application wiring against the test database
        this.app = buildApp(url, user, password);

        this.documentNumbers = app.documentNumbers;
    }

    private com.ims.core.ApplicationContext buildApp(String url, String user,
                                                     String password) {
        // The real ApplicationContext reads classpath application.properties;
        // override via system properties for the test database.
        System.setProperty("db.url", url);
        System.setProperty("db.username", user);
        System.setProperty("db.password", password);
        System.setProperty("http.port", "0");
        System.setProperty("security.password.iterations", "120000");
        // Tests hammer the API from a single address; the dedicated
        // RateLimitHttpIT exercises the limiter explicitly instead.
        System.setProperty("http.rateLimit.enabled", "false");
        // Housekeeping is exercised directly by HousekeepingIT, not on a timer.
        System.setProperty("app.housekeeping.enabled", "false");
        // Same for the webhook dispatcher: WebhookServiceIT drains the outbox
        // explicitly, so no background delivery races the assertions.
        System.setProperty("app.webhooks.enabled", "false");
        try {
            return new com.ims.core.ApplicationContext(
                    com.ims.core.AppConfig.load());
        } catch (Exception e) {
            throw new IllegalStateException("Could not build application context", e);
        }
    }

    private volatile com.ims.testing.ApiTestServer sharedApi;

    /**
     * The application contains a single HttpApiServer instance; tests share
     * it (started once per JVM, never stopped - the JVM exits after tests).
     */
    public synchronized com.ims.testing.ApiTestServer api() {
        if (sharedApi == null) {
            sharedApi = new com.ims.testing.ApiTestServer(this);
        }
        return sharedApi;
    }

    public static IntegrationTestEnv get() {
        IntegrationTestEnv result = instance;
        if (result == null) {
            synchronized (IntegrationTestEnv.class) {
                result = instance;
                if (result == null) {
                    instance = result = new IntegrationTestEnv();
                }
            }
        }
        return result;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // register the resource so close() runs once at JVM shutdown
        context.getRoot().getStore(GLOBAL).put("ims-env", this);
    }

    @Override
    public void close() {
        app.close();
    }

    // ---- isolation ----

    private static final List<String> TRUNCATE_ORDER = List.of(
            "receipt_lines", "receipts", "shipment_lines", "shipments",
            "sales_return_lines", "sales_returns", "reservations",
            "sales_order_lines", "sales_orders",
            "purchase_order_lines", "purchase_orders",
            "stock_adjustments", "stock_transfers", "stock_movements",
            "stock_items", "supplier_items", "products", "categories",
            "suppliers", "customers", "warehouses",
            "stocktake_lines", "stocktakes",
            "webhook_deliveries", "webhooks",
            "idempotency_keys", "audit_logs", "api_tokens", "users",
            "doc_sequences");

    /** Wipe all business data (FK checks off), keeping schema_migrations. */
    public void truncateAll() {
        try (Connection c = connectionManager.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : TRUNCATE_ORDER) {
                    st.executeUpdate("TRUNCATE TABLE " + table);
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Truncation failed", e);
        }
    }

    /** Lighter reset for stateful flows: removes documents and stock only. */
    public void resetDocuments() {
        try (Connection c = connectionManager.getConnection();
             Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : List.of("receipt_lines", "receipts", "shipment_lines",
                    "shipments", "sales_return_lines", "sales_returns", "reservations",
                    "sales_order_lines", "sales_orders", "purchase_order_lines",
                    "purchase_orders", "stock_adjustments", "stock_transfers",
                    "stock_movements", "stock_items", "idempotency_keys", "audit_logs")) {
                st.executeUpdate("TRUNCATE TABLE " + table);
            }
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (SQLException e) {
            throw new IllegalStateException("Reset failed", e);
        }
    }

    public int rowCount(String table) {
        try (Connection c = connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Count failed for " + table, e);
        }
    }

    /** Marker so the file is not reported as a test. */
    public static void touch() {
    }

    static {
        // ensure ./logs exists for the app file appender
        try {
            Files.createDirectories(Path.of("logs"));
        } catch (Exception ignored) {
            // test cwd may be read-only - logback file appender failure is tolerated
        }
    }
}
