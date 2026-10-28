package com.ims.tools;

import com.ims.db.ConnectionManager;
import com.ims.db.DatabaseMigrator;
import com.ims.core.AppConfig;

/**
 * Standalone schema migration runner (the DDL-privileged counterpart of the
 * application). Typical use:
 *
 * <pre>
 *   IMS_DB_URL='jdbc:mysql://127.0.0.1:3306/inventory_db?...' \
 *   IMS_DB_USERNAME=root IMS_DB_PASSWORD=... \
 *   java -cp target/classes:target/lib/* com.ims.tools.Migrate
 * </pre>
 *
 * The application user (ims_app) intentionally has no DDL privileges; run
 * this tool with a migration-role account instead.
 */
public final class Migrate {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        String url = firstNonBlank(System.getenv("IMS_MIGRATE_URL"),
                System.getenv("IMS_DB_URL"), config.dbUrl());
        String user = firstNonBlank(System.getenv("IMS_MIGRATE_USERNAME"),
                System.getenv("IMS_DB_USERNAME"), config.dbUsername());
        String password = firstNonBlank(System.getenv("IMS_MIGRATE_PASSWORD"),
                System.getenv("IMS_DB_PASSWORD"), config.dbPassword());

        System.out.println("Migrating: " + url + " as " + user);
        try (ConnectionManager cm = new ConnectionManager(url, user, password,
                4, 1, 10000, 60000, 1700000, 0)) {
            DatabaseMigrator migrator = new DatabaseMigrator(cm);
            DatabaseMigrator.MigrationResult result = migrator.migrate();
            System.out.println("Applied " + result.applied() + " migration(s).");
            result.appliedMigrations().forEach(m ->
                    System.out.println("  V" + m.version() + " - " + m.description()));
            System.out.println("Pending after run: " + migrator.pendingCount());
        }
        System.out.println("Migration complete.");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Migrate() {
    }
}
