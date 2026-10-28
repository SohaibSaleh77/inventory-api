package com.ims.core;

import com.ims.db.ConnectionManager;
import com.ims.db.DatabaseMigrator;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.auth.AuthService;
import com.ims.domain.category.CategoryDao;
import com.ims.domain.category.CategoryService;
import com.ims.domain.customer.CustomerDao;
import com.ims.domain.customer.CustomerService;
import com.ims.domain.product.ProductDao;
import com.ims.domain.product.ProductService;
import com.ims.domain.purchase.PurchaseOrderDao;
import com.ims.domain.purchase.PurchaseOrderService;
import com.ims.domain.purchase.ReceiptDao;
import com.ims.domain.purchase.ReceivingService;
import com.ims.domain.report.ReportService;
import com.ims.domain.sales.FulfillmentService;
import com.ims.domain.sales.SalesOrderDao;
import com.ims.domain.sales.SalesOrderService;
import com.ims.domain.sales.SalesReturnDao;
import com.ims.domain.sales.SalesReturnService;
import com.ims.domain.sales.ShipmentDao;
import com.ims.domain.stock.StockAdjustmentDao;
import com.ims.domain.stock.StockItemDao;
import com.ims.domain.stock.StockMovementDao;
import com.ims.domain.stock.StockQueryService;
import com.ims.domain.stock.StockService;
import com.ims.domain.stocktake.StocktakeDao;
import com.ims.domain.stocktake.StocktakeService;
import com.ims.domain.supplier.SupplierDao;
import com.ims.domain.supplier.SupplierService;
import com.ims.domain.transfer.StockTransferDao;
import com.ims.domain.transfer.TransferService;
import com.ims.domain.user.UserDao;
import com.ims.domain.user.UserService;
import com.ims.domain.warehouse.WarehouseDao;
import com.ims.domain.warehouse.WarehouseService;
import com.ims.domain.webhook.WebhookClient;
import com.ims.domain.webhook.WebhookDao;
import com.ims.domain.webhook.WebhookDispatcher;
import com.ims.domain.webhook.WebhookService;
import com.ims.http.HttpApiServer;
import com.ims.http.IdempotencyService;
import com.ims.http.Metrics;
import com.ims.http.RateLimiter;
import com.ims.http.Router;
import com.ims.http.controller.AuditController;
import com.ims.http.controller.AuthController;
import com.ims.http.controller.CategoriesController;
import com.ims.http.controller.CustomersController;
import com.ims.http.controller.HealthController;
import com.ims.http.controller.MetaController;
import com.ims.http.controller.MetricsController;
import com.ims.http.controller.ProductsController;
import com.ims.http.controller.PurchaseOrdersController;
import com.ims.http.controller.ReportsController;
import com.ims.http.controller.SalesOrdersController;
import com.ims.http.controller.StocktakesController;
import com.ims.http.controller.StockController;
import com.ims.http.controller.SuppliersController;
import com.ims.http.controller.TransfersController;
import com.ims.http.controller.UsersController;
import com.ims.http.controller.WarehousesController;
import com.ims.http.controller.WebhooksController;
import com.ims.security.PasswordHasher;
import com.ims.security.TokenDao;
import com.ims.security.TokenService;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Constructor-based dependency wiring. Every component is created exactly
 * once, in dependency order; no reflection, no classpath scanning - the
 * object graph is explicit, reviewable, and trivially testable.
 */
public final class ApplicationContext {

    // infrastructure
    public final ConnectionManager connectionManager;
    public final TransactionManager transactions;
    public final DatabaseMigrator migrator;
    public final DocumentNumbers documentNumbers;
    public final AuditLogger auditLogger;
    public final PasswordHasher passwordHasher;
    public final TokenDao tokenDao;
    public final TokenService tokenService;

    // runtime services
    public final Metrics metrics;
    public final RateLimiter generalRateLimiter;
    public final RateLimiter loginRateLimiter;
    public final IdempotencyService idempotency;
    public final HousekeepingService housekeeping;
    public final WebhookDispatcher webhookDispatcher;

    // daos
    public final UserDao userDao;
    public final CategoryDao categoryDao;
    public final SupplierDao supplierDao;
    public final CustomerDao customerDao;
    public final WarehouseDao warehouseDao;
    public final ProductDao productDao;
    public final StockItemDao stockItemDao;
    public final StockMovementDao stockMovementDao;
    public final StockAdjustmentDao stockAdjustmentDao;
    public final StockTransferDao stockTransferDao;
    public final PurchaseOrderDao purchaseOrderDao;
    public final ReceiptDao receiptDao;
    public final SalesOrderDao salesOrderDao;
    public final ShipmentDao shipmentDao;
    public final SalesReturnDao salesReturnDao;

    // integration
    public final WebhookDao webhookDao;

    // services
    public final AuthService authService;
    public final UserService userService;
    public final CategoryService categoryService;
    public final SupplierService supplierService;
    public final CustomerService customerService;
    public final WarehouseService warehouseService;
    public final ProductService productService;
    public final StockService stockService;
    public final StockQueryService stockQueryService;
    public final TransferService transferService;
    public final PurchaseOrderService purchaseOrderService;
    public final ReceivingService receivingService;
    public final SalesOrderService salesOrderService;
    public final FulfillmentService fulfillmentService;
    public final SalesReturnService salesReturnService;
    public final ReportService reportService;
    public final StocktakeService stocktakeService;
    public final WebhookService webhookService;

    // http
    public final Router router;
    public final HttpApiServer apiServer;
    public final HealthController healthController;

    private final AppConfig config;
    private final int shutdownDrainSeconds;

    public ApplicationContext(AppConfig config) throws Exception {
        this.config = config;
        this.shutdownDrainSeconds =
                config.getInt("app.shutdownDrainSeconds", 10);

        // infrastructure
        this.connectionManager = new ConnectionManager(
                config.dbUrl(), config.dbUsername(), config.dbPassword(),
                config.getInt("db.pool.maxSize", 20),
                config.getInt("db.pool.minIdle", 4),
                config.getLong("db.pool.connectionTimeoutMs", 8000),
                config.getLong("db.pool.idleTimeoutMs", 300000),
                config.getLong("db.pool.maxLifetimeMs", 1500000),
                config.getLong("db.pool.leakDetectionThresholdMs", 15000));
        this.transactions = new TransactionManager(connectionManager,
                config.getInt("db.retry.deadlockMaxRetries", 2));
        this.migrator = new DatabaseMigrator(connectionManager);
        this.documentNumbers = new DocumentNumbers();
        this.auditLogger = new AuditLogger(transactions);
        this.passwordHasher = new PasswordHasher(
                config.getInt("security.password.iterations", 210000),
                config.getInt("security.password.saltBytes", 16));
        this.tokenDao = new TokenDao();
        this.tokenService = new TokenService(transactions, tokenDao,
                Duration.ofMinutes(config.getLong("security.token.ttlMinutes", 240)));

        // runtime services
        boolean rateLimitEnabled = config.getBoolean("http.rateLimit.enabled", true);
        this.generalRateLimiter = new RateLimiter(
                config.getDouble("http.rateLimit.requestsPerSecond", 50),
                config.getDouble("http.rateLimit.burst", 100),
                rateLimitEnabled);
        this.loginRateLimiter = new RateLimiter(
                config.getDouble("http.rateLimit.loginRequestsPerMinute", 10) / 60.0,
                config.getDouble("http.rateLimit.loginRequestsPerMinute", 10),
                rateLimitEnabled);
        this.metrics = new Metrics();
        this.idempotency = new IdempotencyService(transactions,
                Duration.ofHours(config.getLong("app.idempotency.ttlHours", 24)));

        // daos
        this.userDao = new UserDao();
        this.categoryDao = new CategoryDao();
        this.supplierDao = new SupplierDao();
        this.customerDao = new CustomerDao();
        this.warehouseDao = new WarehouseDao();
        this.productDao = new ProductDao();
        this.stockItemDao = new StockItemDao();
        this.stockMovementDao = new StockMovementDao();
        this.stockAdjustmentDao = new StockAdjustmentDao();
        this.stockTransferDao = new StockTransferDao();
        this.purchaseOrderDao = new PurchaseOrderDao();
        this.receiptDao = new ReceiptDao();
        this.salesOrderDao = new SalesOrderDao();
        this.shipmentDao = new ShipmentDao();
        this.salesReturnDao = new SalesReturnDao();
        this.webhookDao = new WebhookDao();

        // services
        this.authService = new AuthService(transactions, userDao, tokenDao,
                tokenService, passwordHasher, auditLogger,
                config.getInt("security.login.maxFailedAttempts", 5),
                Duration.ofMinutes(config.getLong("security.login.lockoutMinutes", 15)));
        this.userService = new UserService(transactions, userDao, passwordHasher,
                auditLogger);
        this.categoryService = new CategoryService(transactions, categoryDao,
                auditLogger);
        this.supplierService = new SupplierService(transactions, supplierDao,
                productDao, auditLogger);
        this.customerService = new CustomerService(transactions, customerDao,
                auditLogger);
        this.warehouseService = new WarehouseService(transactions, warehouseDao,
                auditLogger);
        this.productService = new ProductService(transactions, productDao, categoryDao,
                auditLogger);
        this.webhookService = new WebhookService(transactions, webhookDao,
                auditLogger, new WebhookClient(
                        Duration.ofSeconds(config.getLong("app.webhooks.timeoutSeconds", 10))),
                config.getInt("app.webhooks.maxAttempts", 6));
        this.stockService = new StockService(transactions, stockItemDao,
                stockMovementDao, documentNumbers, webhookService);
        this.stockQueryService = new StockQueryService(transactions, stockItemDao,
                stockMovementDao, stockAdjustmentDao, stockService, productDao,
                warehouseDao, auditLogger, documentNumbers);
        this.transferService = new TransferService(transactions, stockTransferDao,
                productDao, warehouseDao, stockItemDao, stockService, auditLogger,
                documentNumbers);
        this.purchaseOrderService = new PurchaseOrderService(transactions,
                purchaseOrderDao, supplierDao, warehouseDao, productDao,
                webhookService, auditLogger, documentNumbers);
        this.receivingService = new ReceivingService(transactions, purchaseOrderDao,
                receiptDao, warehouseDao, productDao, stockService, auditLogger,
                documentNumbers);
        this.salesOrderService = new SalesOrderService(transactions, salesOrderDao,
                customerDao, warehouseDao, productDao, stockService, webhookService,
                auditLogger, documentNumbers);
        this.fulfillmentService = new FulfillmentService(transactions, salesOrderDao,
                shipmentDao, warehouseDao, productDao, stockItemDao, stockService,
                webhookService, auditLogger, documentNumbers);
        this.salesReturnService = new SalesReturnService(transactions, salesOrderDao,
                salesReturnDao, shipmentDao, warehouseDao, productDao, stockService,
                auditLogger, documentNumbers);
        this.reportService = new ReportService(transactions);
        this.stocktakeService = new StocktakeService(transactions,
                new StocktakeDao(), warehouseDao, productDao, stockAdjustmentDao,
                stockService, webhookService, auditLogger, documentNumbers);

        // http: routes first, then the server (health late-binds the server)
        this.router = new Router();
        this.healthController = new HealthController(connectionManager, migrator);
        new AuthController(authService).register(router);
        new UsersController(userService).register(router);
        new CategoriesController(categoryService).register(router);
        new SuppliersController(supplierService).register(router);
        new CustomersController(customerService).register(router);
        new WarehousesController(warehouseService).register(router);
        new ProductsController(productService, stockService).register(router);
        new StockController(stockQueryService).register(router);
        new TransfersController(transferService).register(router);
        new PurchaseOrdersController(purchaseOrderService, receivingService)
                .register(router);
        new SalesOrdersController(salesOrderService, fulfillmentService,
                salesReturnService).register(router);
        new ReportsController(reportService).register(router);
        new AuditController(auditLogger).register(router);
        new StocktakesController(stocktakeService).register(router);
        new WebhooksController(webhookService).register(router);
        healthController.register(router);
        new MetaController(config.getString("app.name", "IMS"), appVersion(),
                Map.of(
                        "metrics", config.getBoolean("http.metrics.enabled", true),
                        "rateLimiting", rateLimitEnabled,
                        "idempotency", config.getBoolean("app.idempotency.enabled", true),
                        "webhooks", config.getBoolean("app.webhooks.enabled", true),
                        "gracefulShutdown", true))
                .register(router);
        new MetricsController(metrics,
                config.getBoolean("http.metrics.enabled", true)).register(router);

        boolean corsEnabledByOrigins = !config
                .getString("http.cors.allowedOrigins", "").isBlank();
        Set<String> corsOrigins = corsEnabledByOrigins
                ? Arrays.stream(config.getString("http.cors.allowedOrigins", "")
                        .split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();

        HttpApiServer.Options serverOptions = new HttpApiServer.Options(
                config.getLong("http.maxRequestBodyBytes", 1_048_576),
                generalRateLimiter,
                loginRateLimiter,
                rateLimitEnabled,
                config.getBoolean("http.trustProxyHeaders", false),
                corsOrigins,
                idempotency,
                config.getBoolean("app.idempotency.enabled", true),
                metrics);
        this.apiServer = new HttpApiServer(config.httpHost(), config.httpPort(),
                router, authService, serverOptions);
        healthController.bindServer(apiServer);
        this.apiServer.publicPath("/api/health");
        this.apiServer.publicPath("/api/health/live");
        this.apiServer.publicPath("/api/health/ready");
        this.apiServer.publicPath("/api/auth/login");
        this.apiServer.publicPath("/api/meta");
        this.apiServer.publicPath("/api/metrics");

        // observability bindings
        this.metrics.bindPool(new Metrics.PoolGauges() {
            private com.zaxxer.hikari.HikariPoolMXBean pool() {
                return connectionManager.getDataSource().getHikariPoolMXBean();
            }

            @Override public int active() {
                var pool = pool();
                return pool == null ? 0 : pool.getActiveConnections();
            }

            @Override public int idle() {
                var pool = pool();
                return pool == null ? 0 : pool.getIdleConnections();
            }

            @Override public int total() {
                var pool = pool();
                return pool == null ? 0 : pool.getTotalConnections();
            }

            @Override public int pending() {
                var pool = pool();
                return pool == null ? 0 : pool.getThreadsAwaitingConnection();
            }
        });
        this.metrics.bindVersion(appVersion());
        this.metrics.bindRateLimiters(generalRateLimiter, loginRateLimiter);

        // housekeeping
        this.housekeeping = new HousekeepingService(tokenService, idempotency,
                config.getLong("app.housekeeping.intervalMinutes", 60));
        if (config.getBoolean("app.housekeeping.enabled", true)) {
            this.housekeeping.start(config.getLong("app.housekeeping.intervalMinutes", 60));
        }

        // webhook outbox dispatcher
        this.webhookDispatcher = new WebhookDispatcher(webhookService);
        if (config.getBoolean("app.webhooks.enabled", true)) {
            this.webhookDispatcher.start(
                    config.getLong("app.webhooks.dispatchIntervalSeconds", 30));
        }
    }

    /** Version from the jar manifest (falls back to "dev" in exploded runs). */
    public String appVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    public AppConfig config() {
        return config;
    }

    public int shutdownDrainSeconds() {
        return shutdownDrainSeconds;
    }

    public void close() {
        webhookDispatcher.close();
        housekeeping.close();
        apiServer.stop(shutdownDrainSeconds);
        connectionManager.close();
    }
}
