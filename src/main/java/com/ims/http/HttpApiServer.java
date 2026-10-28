package com.ims.http;

import com.ims.common.exception.ForbiddenException;
import com.ims.common.exception.MethodNotAllowedException;
import com.ims.common.exception.TooManyRequestsException;
import com.ims.common.exception.UnauthenticatedException;
import com.ims.domain.auth.AuthService;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The API front controller.
 *
 * <p>Per exchange, in order:
 * <ol>
 *   <li>resolve the client IP (optionally from trusted proxy headers)</li>
 *   <li>assign/propagate an {@code X-Request-Id} and bind it to the
 *       {@link RequestContext} + logging MDC</li>
 *   <li>apply baseline security headers and CORS to EVERY response</li>
 *   <li>rate limit per IP (general bucket, plus a tight login bucket)</li>
 *   <li>route, authenticate, optionally run under an Idempotency-Key</li>
 *   <li>record Prometheus metrics (route pattern, status, duration) and an
 *       access log line</li>
 * </ol>
 */
public final class HttpApiServer {

    private static final Logger log = LoggerFactory.getLogger(HttpApiServer.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> IDEMPOTENT_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Immutable server behaviour settings, wired once from config. */
    public record Options(long maxRequestBodyBytes,
                          RateLimiter generalRateLimiter,
                          RateLimiter loginRateLimiter,
                          boolean rateLimitEnabled,
                          boolean trustProxyHeaders,
                          Set<String> corsAllowedOrigins,
                          IdempotencyService idempotency,
                          boolean idempotencyEnabled,
                          Metrics metrics) {
    }

    private final Router router;
    private final AuthService authService;
    private final Options options;
    private final com.sun.net.httpserver.HttpServer server;
    private final Set<String> publicPaths = new CopyOnWriteArraySet<>();
    /** Flipped during graceful shutdown; readiness probes report DOWN. */
    private volatile boolean draining;

    public HttpApiServer(String host, int port, Router router,
                         AuthService authService, Options options)
            throws IOException {
        this.router = router;
        this.authService = authService;
        this.options = options;
        this.server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(host, port), 1024);
        // Java 21: one virtual thread per request - blocking JDBC calls scale
        this.server.setExecutor(java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
    }

    /** Paths that do not require a bearer token (health, login, metrics...). */
    public HttpApiServer publicPath(String path) {
        publicPaths.add(path);
        return this;
    }

    public void start() {
        server.start();
        log.info("HTTP API listening on {}",
                server.getAddress().getAddress().getHostAddress()
                        + ":" + server.getAddress().getPort());
    }

    /**
     * Graceful stop: marks the server as draining (readiness goes DOWN),
     * then waits up to {@code drainSeconds} for in-flight exchanges before
     * forcibly closing connections.
     */
    public void stop(int drainSeconds) {
        draining = true;
        server.stop(Math.max(0, drainSeconds));
    }

    public boolean isDraining() {
        return draining;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ //

    private void dispatch(HttpExchange exchange) {
        long started = System.nanoTime();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String requestId = resolveRequestId(exchange);
        String clientIp = resolveClientIp(exchange);
        RequestContext.begin(requestId, clientIp,
                exchange.getRequestHeaders().getFirst("User-Agent"));
        MDC.put("requestId", requestId);
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        options.metrics().requestStarted();
        String routeLabel = "unmatched";
        int status = 500;
        AuthContext auth = null;
        try {
            applyBaselineHeaders(exchange);

            if ("OPTIONS".equals(method)) {
                handlePreflight(exchange);
                status = 204;
                return;
            }

            if (options.generalRateLimiter() != null) {
                RateLimiter.Decision general =
                        options.generalRateLimiter().acquire(clientIp);
                if (!general.allowed()) {
                    status = 429;
                    sendRateLimited(exchange, general.retryAfterSeconds());
                    return;
                }
                if ("POST".equals(method) && "/api/auth/login".equals(path)) {
                    RateLimiter.Decision login =
                            options.loginRateLimiter().acquire(clientIp);
                    if (!login.allowed()) {
                        status = 429;
                        sendRateLimited(exchange, login.retryAfterSeconds());
                        return;
                    }
                }
            }

            Router.Match match = router.match(method, path);
            if (match == null) {
                Set<String> allowed = router.allowedMethods(path);
                if (!allowed.isEmpty()) {
                    MethodNotAllowedException error = new MethodNotAllowedException(
                            "Method " + method + " not allowed on " + path, allowed);
                    exchange.getResponseHeaders().set("Allow",
                            String.join(", ", allowed));
                    routeLabel = "method-not-allowed";
                    status = error.httpStatus();
                    ErrorResponse.send(exchange, error);
                } else {
                    routeLabel = "not-found";
                    status = 404;
                    ErrorResponse.send(exchange, new com.ims.common.exception
                            .NotFoundException("No route for " + path));
                }
                return;
            }
            routeLabel = match.pattern();

            auth = authenticate(exchange, path);
            WebContext ctx = new WebContext(exchange, match.pathParams(), auth,
                    options.maxRequestBodyBytes());

            String idempotencyKey = exchange.getRequestHeaders()
                    .getFirst("Idempotency-Key");
            if (options.idempotencyEnabled() && auth != null
                    && idempotencyKey != null && !idempotencyKey.isBlank()
                    && IDEMPOTENT_METHODS.contains(method)) {
                status = dispatchIdempotent(exchange, ctx, match, auth,
                        idempotencyKey.trim(), method, path);
            } else {
                match.handler().handle(ctx);
                status = exchange.getResponseCode();
            }
        } catch (Exception e) {
            try {
                if (e instanceof com.ims.common.exception.PayloadTooLargeException) {
                    // Be a good HTTP citizen: discard (bounded) the remainder of
                    // an oversized body so the client can finish uploading and
                    // read the 413 instead of seeing a connection reset.
                    drainBounded(exchange.getRequestBody());
                }
                int sent = ErrorResponse.send(exchange, e);
                status = sent;
            } catch (IOException ioError) {
                log.error("Failed to send error response", ioError);
            }
        } finally {
            options.metrics().requestFinished();
            options.metrics().recordRequest(method, routeLabel, status,
                    (System.nanoTime() - started) / 1e9);
            logRequest(method, path, status, started, clientIp, auth);
            RequestContext.end();
            MDC.remove("requestId");
            exchange.close();
        }
    }

    /**
     * Execute a mutation under Idempotency-Key semantics: replay stored
     * responses for retries, store fresh successes atomically, reject key
     * reuse with a different payload.
     */
    private int dispatchIdempotent(HttpExchange exchange, WebContext ctx,
                                   Router.Match match, AuthContext auth,
                                   String key, String method, String path)
            throws Exception {
        String requestHash = options.idempotency().requestHash(ctx.body());
        var stored = options.idempotency().lookup(auth.userId(), key, requestHash);
        if (stored.isPresent()) {
            return replay(exchange, stored.get());
        }

        ctx.beginCapture();
        match.handler().handle(ctx);
        if (!ctx.hasCapturedResponse()) {
            throw new IllegalStateException(
                    "Handler completed without producing a response");
        }
        boolean storedNow = options.idempotency().store(auth.userId(), key,
                method, path, requestHash, ctx.capturedStatus(),
                ctx.capturedContentType(), ctx.capturedBody());
        if (!storedNow) {
            // A concurrent request with the same key won the insert: replay
            // its stored response instead of sending our own.
            var winner = options.idempotency().lookup(auth.userId(), key,
                    requestHash);
            if (winner.isPresent()) {
                return replay(exchange, winner.get());
            }
        }
        ctx.flushCaptured();
        return ctx.capturedStatus();
    }

    /** Send a stored idempotent response, marked as a replay. */
    private int replay(HttpExchange exchange,
                       IdempotencyService.StoredResponse response)
            throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Idempotency-Replayed", "true");
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (response.contentType() != null) {
            headers.set("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.statusCode(),
                body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) {
            if (body.length > 0) {
                out.write(body);
            }
        }
        return response.statusCode();
    }

    /** Discard up to {@code maxDrain} bytes of an unread request body. */
    private static void drainBounded(java.io.InputStream input) {
        long maxDrain = 64L * 1024 * 1024;
        byte[] chunk = new byte[8192];
        long total = 0;
        try {
            int read;
            while (total < maxDrain && (read = input.read(chunk)) != -1) {
                total += read;
            }
        } catch (IOException ignored) {
            // client already gave up - fine
        }
    }

    private void sendRateLimited(HttpExchange exchange, long retryAfterSeconds)
            throws IOException {
        exchange.getResponseHeaders().set("Retry-After",
                Long.toString(retryAfterSeconds));
        ErrorResponse.send(exchange, new TooManyRequestsException(
                "Rate limit exceeded - retry after " + retryAfterSeconds
                        + " seconds", retryAfterSeconds));
    }

    /**
     * Baseline security headers on every response (success, error, 204).
     * Set before any handler writes; HttpServer includes headers set
     * prior to sendResponseHeaders.
     */
    private void applyBaselineHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        applyCors(exchange);
    }

    /** Echo CORS headers when the Origin is explicitly allowed. */
    private void applyCors(HttpExchange exchange) {
        if (options.corsAllowedOrigins().isEmpty()) {
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !options.corsAllowedOrigins().contains(origin)) {
            return;
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", origin);
        headers.set("Vary", "Origin");
        headers.set("Access-Control-Allow-Credentials", "true");
    }

    private void handlePreflight(HttpExchange exchange) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Methods",
                "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers",
                "Authorization, Content-Type, Idempotency-Key, If-None-Match, "
                        + "X-Request-Id");
        headers.set("Access-Control-Max-Age", "600");
        exchange.sendResponseHeaders(204, -1);
    }

    private AuthContext authenticate(HttpExchange exchange, String path)
            throws IOException {
        if (publicPaths.contains(path)) {
            return null; // public endpoint
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthenticatedException("Missing or invalid Authorization header");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return authService.authenticate(token)
                .orElseThrow(() -> new UnauthenticatedException("Invalid or expired token"));
    }

    private String resolveRequestId(HttpExchange exchange) {
        String incoming = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (incoming != null && incoming.matches("[A-Za-z0-9._-]{1,64}")) {
            return incoming;
        }
        byte[] entropy = new byte[8];
        RANDOM.nextBytes(entropy);
        return HexFormat.of().formatHex(entropy);
    }

    private String resolveClientIp(HttpExchange exchange) {
        if (options.trustProxyHeaders()) {
            // Only trust the FIRST entry (client-supplied part) when the proxy
            // is trusted to overwrite the header; otherwise fall back to socket.
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
            String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown" : remote.getAddress().getHostAddress();
    }

    private void logRequest(String method, String path, int status, long startedNanos,
                            String clientIp, AuthContext auth) {
        long micros = (System.nanoTime() - startedNanos) / 1000;
        String user = auth == null ? "-" : auth.username();
        if (status >= 500) {
            log.warn("{} {} -> {} ({} us) user={} ip={} requestId={}",
                    method, path, status, micros, user, clientIp,
                    RequestContext.requestId());
        } else {
            log.info("{} {} -> {} ({} us) user={} ip={} requestId={}",
                    method, path, status, micros, user, clientIp,
                    RequestContext.requestId());
        }
    }

    /** Convenience guard used by controllers. */
    public static void requirePermission(WebContext ctx, Permission permission) {
        if (ctx.auth() == null) {
            throw new UnauthenticatedException("Authentication required");
        }
        if (!ctx.auth().has(permission)) {
            throw new ForbiddenException("Role " + ctx.auth().role()
                    + " lacks permission " + permission);
        }
    }
}
