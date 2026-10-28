package com.ims.http;

import com.ims.common.exception.ApiException;
import com.ims.common.exception.TooManyRequestsException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform error responses (RFC 9457 problem+json inspired).
 * Internal errors never leak stack traces to clients; every error carries
 * the request's correlation id so clients and operators can find the
 * matching log line.
 */
public final class ErrorResponse {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponse.class);

    private ErrorResponse() {
    }

    /** Render the error and return the HTTP status that was sent. */
    public static int send(com.sun.net.httpserver.HttpExchange exchange, Throwable t)
            throws IOException {
        int status;
        String code;
        String message;
        Map<String, Object> extras = new LinkedHashMap<>();

        if (t instanceof ApiException api) {
            status = api.httpStatus();
            code = api.errorCode();
            message = api.getMessage();
            if (api instanceof ValidationException validation
                    && !validation.fieldErrors().isEmpty()) {
                extras.put("fieldErrors", validation.fieldErrors());
            }
            if (api instanceof TooManyRequestsException limited) {
                extras.put("retryAfterSeconds", limited.retryAfterSeconds());
            }
        } else if (t instanceof UncheckedIOException | t instanceof IOException) {
            status = 400;
            code = "IO_ERROR";
            message = "Request/response IO problem: "
                    + t.getClass().getSimpleName();
        } else if (t instanceof IllegalArgumentException) {
            status = 400;
            code = "BAD_ARGUMENT";
            message = t.getMessage() == null ? "Invalid argument" : t.getMessage();
        } else if (t instanceof IllegalStateException) {
            status = 409;
            code = "ILLEGAL_STATE";
            message = t.getMessage() == null ? "Illegal state" : t.getMessage();
        } else {
            status = 500;
            code = "INTERNAL_ERROR";
            message = "An unexpected error occurred";
            log.error("Unhandled exception serving {} {} requestId={}",
                    exchange.getRequestMethod(), exchange.getRequestURI(),
                    RequestContext.requestId(), t);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://ims.example/errors/" + code);
        body.put("title", code);
        body.put("status", status);
        body.put("detail", message);
        body.put("requestId", RequestContext.requestId());
        body.putAll(extras);

        byte[] bytes = Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }
}
