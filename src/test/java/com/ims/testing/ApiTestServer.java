package com.ims.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Boots the REAL HttpApiServer on an ephemeral port against the test
 * database and gives tests a tiny fluent HTTP client. Everything about the
 * running instance is production code - only the port differs.
 */
public final class ApiTestServer implements AutoCloseable {

    private final com.ims.http.HttpApiServer server;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    public final String baseUrl;

    public ApiTestServer(IntegrationTestEnv env) {
        this.server = env.app.apiServer;
        server.start();
        this.baseUrl = "http://127.0.0.1:" + server.port();
    }

    // ---- tiny HTTP client ----

    public record ApiResponse(int status, String body,
                             java.util.Map<String, String> headers) {

        public ApiResponse(int status, String body) {
            this(status, body, java.util.Map.of());
        }

        public boolean isOk() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            return headers == null ? null : headers.get(name.toLowerCase());
        }

        public <T> T json(Class<T> type) {
            return com.ims.common.util.Json.read(body, type);
        }

        public com.fasterxml.jackson.databind.JsonNode json() {
            return com.ims.common.util.Json.readTree(body);
        }

        public String field(String name) {
            com.fasterxml.jackson.databind.JsonNode node = json();
            return node.has(name) ? node.get(name).asText() : null;
        }
    }

    public ApiResponse get(String path, String token) {
        return send("GET", path, token, null);
    }

    public ApiResponse post(String path, String token, Object body) {
        return send("POST", path, token, body);
    }

    public ApiResponse put(String path, String token, Object body) {
        return send("PUT", path, token, body);
    }

    public ApiResponse delete(String path, String token) {
        return send("DELETE", path, token, null);
    }

    public ApiResponse send(String method, String path, String token, Object body) {
        return send(method, path, token, body, java.util.Map.of());
    }

    /** Send with extra request headers (Idempotency-Key, If-None-Match, ...). */
    public ApiResponse send(String method, String path, String token, Object body,
                            java.util.Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            extraHeaders.forEach(builder::header);
            String payload = body == null ? null
                    : (body instanceof String s ? s : com.ims.common.util.Json.write(body));
            builder.method(method,
                    payload == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(payload));
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k.toLowerCase(), v.get(0));
                }
            });
            return new ApiResponse(response.statusCode(), response.body(), headers);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP call failed: " + method + " " + path, e);
        }
    }

    /** Login via the real API and return the bearer token. */
    public String login(String username, String password) {
        ApiResponse response = post("/api/auth/login", null,
                java.util.Map.of("username", username, "password", password));
        if (!response.isOk()) {
            throw new IllegalStateException("Login failed for " + username
                    + ": " + response.body());
        }
        return response.json().get("token").asText();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
