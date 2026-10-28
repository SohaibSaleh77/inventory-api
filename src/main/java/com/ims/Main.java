package com.ims;

import com.ims.common.util.Dates;
import com.ims.core.AppConfig;
import com.ims.core.ApplicationContext;
import com.ims.domain.user.User;
import com.ims.domain.user.UserDao;
import com.ims.domain.user.UserService;
import com.ims.domain.user.dto.UserDtos;
import com.ims.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Boot sequence:
 * <ol>
 *   <li>load configuration,</li>
 *   <li>run pending schema migrations (configurable),</li>
 *   <li>bootstrap an initial ADMIN account when the users table is empty,</li>
 *   <li>start the HTTP API and await shutdown.</li>
 * </ol>
 *
 * <p>Shutdown (SIGTERM/SIGINT): the readiness endpoint flips to DOWN so the
 * load balancer stops routing, then the HTTP server drains in-flight
 * requests for up to {@code app.shutdownDrainSeconds} before the connection
 * pool closes. This is what makes zero-downtime rolling restarts possible.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "dev";
        }
        log.info("Starting {} v{} ({} mode)", config.getString("app.name", "IMS"),
                version,
                Files.exists(Path.of("config/application.properties"))
                        ? "external-config" : "classpath-config");

        ApplicationContext context = new ApplicationContext(config);

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received - draining ({}s) ...",
                    context.shutdownDrainSeconds());
            try {
                context.close();
            } finally {
                shutdownLatch.countDown();
            }
        }, "shutdown"));

        if (config.getBoolean("app.migrateOnStartup", true)) {
            var result = context.migrator.migrate();
            log.info("Migrations applied: {} (schema is up to date)", result.applied());
        }

        bootstrapAdmin(context);

        context.apiServer.start();
        log.info("Inventory Management System v{} ready on http://{}:{}",
                version,
                config.httpHost(), config.httpPort());
        shutdownLatch.await();
        log.info("Shutdown complete");
    }

    /**
     * First-run bootstrap: when no user exists, create an ADMIN with a
     * generated password (also written to ./logs/bootstrap-admin.txt in the
     * dev environment; production should set IMS_BOOTSTRAP_ADMIN_PASSWORD).
     */
    private static void bootstrapAdmin(ApplicationContext context) throws Exception {
        UserDao userDao = context.userDao;
        Boolean bootstrapNeeded = context.transactions.withConnection(
                conn -> userDao.count(conn) == 0);
        if (!bootstrapNeeded) {
            return;
        }
        String password = System.getenv("IMS_BOOTSTRAP_ADMIN_PASSWORD");
        boolean generated = false;
        if (password == null || password.isBlank()) {
            password = context.userService.generateTempPassword();
            generated = true;
        }
        String finalPassword = password;
        context.transactions.inTransaction(conn -> {
            User admin = new User("admin", "admin@ims.local",
                    context.passwordHasher.hash(finalPassword),
                    "System Administrator", Role.ADMIN);
            admin.setPasswordChangedAt(Dates.now());
            userDao.insert(conn, admin);
            return null;
        });
        if (generated) {
            Path logDir = Path.of("logs");
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve("bootstrap-admin.txt"),
                    "Bootstrap admin password: " + password
                            + "\n(change it immediately)\n");
            log.warn("BOOTSTRAP: created user 'admin' - password written to "
                    + "logs/bootstrap-admin.txt (set IMS_BOOTSTRAP_ADMIN_PASSWORD "
                    + "to control this in production)");
        } else {
            log.info("BOOTSTRAP: created user 'admin' with the password from "
                    + "IMS_BOOTSTRAP_ADMIN_PASSWORD");
        }
    }

    private Main() {
    }
}
