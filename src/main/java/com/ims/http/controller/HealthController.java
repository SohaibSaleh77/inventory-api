package com.ims.http.controller;

import com.ims.db.ConnectionManager;
import com.ims.db.DatabaseMigrator;
import com.ims.http.HttpApiServer;
import com.ims.http.Router;
import com.ims.http.WebContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoints (all public):
 * <ul>
 *   <li>{@code GET /api/health} - combined status (kept for compatibility)</li>
 *   <li>{@code GET /api/health/live} - liveness: is the process able to
 *       serve at all. Never touches the database; used by orchestrators to
 *       decide restarts.</li>
 *   <li>{@code GET /api/health/ready} - readiness: database reachable AND
 *       schema up to date AND not draining. Used to pull the instance
 *       from/into the load-balancer pool. Reports DOWN during graceful
 *       shutdown so in-flight requests can drain.</li>
 * </ul>
 */
public final class HealthController {

    private final ConnectionManager connectionManager;
    private final DatabaseMigrator migrator;
    private volatile HttpApiServer apiServer;

    public HealthController(ConnectionManager connectionManager,
                            DatabaseMigrator migrator) {
        this.connectionManager = connectionManager;
        this.migrator = migrator;
    }

    /** Late binding: the server is constructed after the router (see
     * ApplicationContext wiring) and injected here before startup. */
    public void bindServer(HttpApiServer apiServer) {
        this.apiServer = apiServer;
    }

    public void register(Router router) {
        router.add("GET", "/api/health", this::health);
        router.add("GET", "/api/health/live", this::live);
        router.add("GET", "/api/health/ready", this::ready);
    }

    private void health(WebContext ctx) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean dbOk = connectionManager.isHealthy();
        long pending = dbOk ? migrator.pendingCount() : -1;
        boolean draining = apiServer != null && apiServer.isDraining();
        body.put("status", dbOk && pending == 0 && !draining ? "UP" : "DEGRADED");
        body.put("database", dbOk ? "UP" : "DOWN");
        body.put("pendingMigrations", pending);
        body.put("draining", draining);
        ctx.send(dbOk && !draining ? 200 : 503, body);
    }

    private void live(WebContext ctx) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        ctx.send(200, body);
    }

    private void ready(WebContext ctx) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (apiServer != null && apiServer.isDraining()) {
            body.put("status", "DOWN");
            body.put("reason", "draining");
            ctx.send(503, body);
            return;
        }
        boolean dbOk = connectionManager.isHealthy();
        long pending = dbOk ? migrator.pendingCount() : -1;
        body.put("status", dbOk && pending == 0 ? "UP" : "DOWN");
        body.put("database", dbOk ? "UP" : "DOWN");
        body.put("pendingMigrations", pending);
        ctx.send(dbOk && pending == 0 ? 200 : 503, body);
    }
}
