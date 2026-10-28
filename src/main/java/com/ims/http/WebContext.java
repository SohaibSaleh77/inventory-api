package com.ims.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.ims.common.exception.BadRequestException;
import com.ims.common.exception.PayloadTooLargeException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Csv;
import com.ims.common.util.Json;
import com.ims.security.AuthContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-request wrapper: exposes path params, query params, JSON body reading
 * (with a hard size cap), the authenticated principal, and response helpers
 * (JSON, ETag-cached JSON, CSV download, idempotency capture).
 */
public final class WebContext {
    private final HttpExchange exchange;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final AuthContext auth;
    private final long maxRequestBodyBytes;
    private String cachedBody;

    // ---- idempotency response capture ----
    private boolean captureMode;
    private boolean captured;
    private int capturedStatus;
    private String capturedContentType;
    private byte[] capturedBody;

    public WebContext(HttpExchange exchange, Map<String, String> pathParams,
                      AuthContext auth, long maxRequestBodyBytes) {
        this.exchange = exchange;
        this.pathParams = pathParams;
        this.queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
        this.auth = auth;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    public HttpExchange exchange() {
        return exchange;
    }

    public AuthContext auth() {
        return auth;
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public long pathParamAsLong(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new BadRequestException("Missing path parameter: " + name);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Path parameter " + name + " must be numeric");
        }
    }

    public String query(String name) {
        return queryParams.get(name);
    }

    public String query(String name, String defaultValue) {
        return queryParams.getOrDefault(name, defaultValue);
    }

    public Long queryAsLong(String name) {
        String value = queryParams.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Query parameter " + name + " must be numeric");
        }
    }

    public Integer queryAsInt(String name) {
        Long value = queryAsLong(name);
        return value == null ? null : value.intValue();
    }

    public Boolean queryAsBool(String name) {
        String value = queryParams.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Read the request body (cached). Enforces the configured hard size cap:
     * an oversized body is rejected with 413 before being fully buffered.
     */
    public String body() {
        if (cachedBody == null) {
            String declaredLength = exchange.getRequestHeaders()
                    .getFirst("Content-Length");
            if (declaredLength != null) {
                try {
                    long declared = Long.parseLong(declaredLength.trim());
                    if (declared > maxRequestBodyBytes) {
                        throw new PayloadTooLargeException(
                                "Request body exceeds the " + maxRequestBodyBytes
                                        + " byte limit");
                    }
                } catch (NumberFormatException ignored) {
                    // malformed Content-Length - the bounded read below still applies
                }
            }
            try (InputStream is = exchange.getRequestBody()) {
                cachedBody = new String(readBounded(is), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new BadRequestException("Could not read request body", e);
            }
        }
        return cachedBody;
    }

    /** Read at most maxRequestBodyBytes+1 bytes; more than the cap means 413. */
    private byte[] readBounded(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer =
                new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = is.read(chunk)) != -1) {
            total += read;
            if (total > maxRequestBodyBytes) {
                throw new PayloadTooLargeException(
                        "Request body exceeds the " + maxRequestBodyBytes
                                + " byte limit");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    public boolean hasBody() {
        return exchange.getRequestHeaders().getFirst("Content-Length") != null
                && !body().isEmpty();
    }

    /** Parse the JSON body; empty body throws a 400 (use optBody for optional). */
    public <T> T body(Class<T> type) {
        if (body().isBlank()) {
            throw new BadRequestException("Request body is required");
        }
        return Json.read(body(), type);
    }

    /** Parse the JSON body leniently: empty body yields null. */
    public <T> T optBody(Class<T> type) {
        if (body() == null || body().isBlank()) {
            return null;
        }
        return Json.read(body(), type);
    }

    /** Parse the JSON body into a tree (lenient: null for empty body). */
    public JsonNode jsonBody() {
        if (body().isBlank()) {
            throw new BadRequestException("Request body is required");
        }
        return Json.readTree(body());
    }

    // ---- idempotency capture mode ----

    /** Enable response capture: send* calls buffer instead of writing to the wire. */
    public void beginCapture() {
        this.captureMode = true;
        this.captured = false;
    }

    /** Whether a successful response has been captured. */
    public boolean hasCapturedResponse() {
        return captured;
    }

    /** The captured status code (valid after {@link #hasCapturedResponse()}). */
    public int capturedStatus() {
        return capturedStatus;
    }

    /** The captured Content-Type header value. */
    public String capturedContentType() {
        return capturedContentType;
    }

    /** The captured response bytes. */
    public byte[] capturedBody() {
        return capturedBody;
    }

    /** Write the previously captured response to the client. */
    public void flushCaptured() throws IOException {
        writeRaw(capturedStatus, capturedContentType, capturedBody);
    }

    // ---- responses ----

    public void ok(Object payload) throws IOException {
        send(200, payload);
    }

    /** 200 with a strong ETag; honours If-None-Match with 304 Not Modified. */
    public void etagged(Object payload) throws IOException {
        byte[] bytes = payload == null ? new byte[0]
                : Json.write(payload).getBytes(StandardCharsets.UTF_8);
        String etag = strongEtag(bytes);
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null && etagMatches(ifNoneMatch, etag)) {
            var headers = exchange.getResponseHeaders();
            headers.set("ETag", etag);
            headers.set("Cache-Control", "private, must-revalidate");
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("ETag", etag);
        headers.set("Cache-Control", "private, must-revalidate");
        deliver(200, bytes);
    }

    public void created(Object payload) throws IOException {
        send(201, payload);
    }

    public void noContent() throws IOException {
        send(204, null);
    }

    public void send(int status, Object payload) throws IOException {
        byte[] bytes = payload == null ? new byte[0]
                : Json.write(payload).getBytes(StandardCharsets.UTF_8);
        String contentType = payload == null ? null
                : "application/json; charset=utf-8";
        if (captureMode) {
            capture(status, contentType, bytes);
            return;
        }
        writeRaw(status, contentType, bytes);
    }

    /**
     * Stream a CSV download (RFC 4180, CRLF line endings, BOM-less UTF-8).
     * Rows are streamed in batches to keep memory flat for large exports.
     */
    public void sendCsv(String filename, List<String> header,
                        Iterable<List<String>> rows) throws IOException {
        if (captureMode) {
            // capture path: materialise (CSV responses are never idempotency-replayed
            // in practice, but keep behaviour consistent)
            java.util.List<List<String>> all = new java.util.ArrayList<>();
            rows.forEach(all::add);
            byte[] bytes = Csv.render(header, all).getBytes(StandardCharsets.UTF_8);
            capture(200, "text/csv; charset=utf-8", bytes);
            return;
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/csv; charset=utf-8");
        headers.set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);
        try (var out = new java.io.BufferedOutputStream(
                exchange.getResponseBody(), 32 * 1024)) {
            StringBuilder sb = new StringBuilder();
            if (header != null && !header.isEmpty()) {
                sb.append(String.join(",",
                                header.stream().map(Csv::escape).toList()))
                        .append("\r\n");
            }
            for (List<String> row : rows) {
                sb.append(String.join(",",
                                row.stream().map(Csv::escape).toList()))
                        .append("\r\n");
                if (sb.length() > 32 * 1024) {
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }
    }

    // ---- internals ----

    private void capture(int status, String contentType, byte[] bytes) {
        if (captured) {
            return; // first successful response wins
        }
        this.capturedStatus = status;
        this.capturedContentType = contentType;
        this.capturedBody = bytes;
        this.captured = true;
    }

    private void writeRaw(int status, String contentType, byte[] bytes)
            throws IOException {
        var headers = exchange.getResponseHeaders();
        if (contentType != null) {
            headers.set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                out.write(bytes);
            }
        }
    }

    private void deliver(int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                out.write(bytes);
            }
        }
    }

    private static String strongEtag(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(2 + 2 * hash.length);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return "\"" + sb + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * If-None-Match matching per RFC 9110: supports a comma-separated list
     * of etags, the wildcard *, and weak comparison of the same opaque value.
     */
    static boolean etagMatches(String ifNoneMatchHeader, String etag) {
        if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank()) {
            return false;
        }
        if ("*".equals(ifNoneMatchHeader.trim())) {
            return true;
        }
        for (String candidate : ifNoneMatchHeader.split(",")) {
            String normalized = candidate.trim();
            if (normalized.startsWith("W/")) {
                normalized = normalized.substring(2);
            }
            if (normalized.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
