package com.ims.http;

/**
 * Per-request context carried through the call stack via a thread-local
 * (safe on virtual threads: one request = one thread). Populated by the
 * front controller before routing, cleared when the exchange completes.
 *
 * <p>Gives every layer (audit logging, services, error rendering) access
 * to the correlation id and the client IP without threading them through
 * every method signature.
 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private final String requestId;
    private final String clientIp;
    private final String userAgent;

    private RequestContext(String requestId, String clientIp, String userAgent) {
        this.requestId = requestId;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    /** Bind a context to the current thread (called once per exchange). */
    public static void begin(String requestId, String clientIp, String userAgent) {
        CURRENT.set(new RequestContext(requestId, clientIp, userAgent));
    }

    /** Clear the context at the end of the exchange. */
    public static void end() {
        CURRENT.remove();
    }

    /** Current correlation id, or "-" when the call is not serving a request. */
    public static String requestId() {
        RequestContext ctx = CURRENT.get();
        return ctx == null ? "-" : ctx.requestId;
    }

    /** Client IP of the current request, or null outside a request. */
    public static String clientIp() {
        RequestContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.clientIp;
    }

    /** Calling request's User-Agent, or null outside a request. */
    public static String userAgent() {
        RequestContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.userAgent;
    }
}
