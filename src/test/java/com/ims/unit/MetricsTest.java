package com.ims.unit;

import com.ims.http.Metrics;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus text-format rendering: correct HELP/TYPE headers, monotone
 * histogram buckets, and stable series names.
 */
class MetricsTest {

    @Test
    void renderContainsCoreSeries() {
        Metrics metrics = new Metrics();
        metrics.bindVersion("test-1");
        metrics.recordRequest("GET", "/api/products", 200, 0.05);
        metrics.requestStarted();
        metrics.requestFinished();
        String rendered = metrics.render();

        assertTrue(rendered.contains("# TYPE http_server_requests_total counter"));
        assertTrue(rendered.contains(
                "http_server_requests_total{method=\"GET\",route=\"/api/products\",status=\"200\"} 1"));
        assertTrue(rendered.contains("# TYPE http_server_request_duration_seconds histogram"));
        assertTrue(rendered.contains("ims_http_requests_active"));
        assertTrue(rendered.contains("ims_build_info{version=\"test-1\"} 1"));
        assertTrue(rendered.contains("jvm_memory_used_bytes{area=\"heap\"}"));
        assertTrue(rendered.contains("ims_process_uptime_seconds"));
    }

    @Test
    void histogramBucketsAreCumulativeAndMonotone() {
        Metrics metrics = new Metrics();
        metrics.recordRequest("GET", "/r", 200, 0.004);  // le=0.005
        metrics.recordRequest("GET", "/r", 200, 0.02);   // le=0.025
        metrics.recordRequest("GET", "/r", 200, 0.02);
        metrics.recordRequest("GET", "/r", 200, 9.0);    // +Inf
        String rendered = metrics.render();

        Pattern line = Pattern.compile(
                "http_server_request_duration_seconds_bucket\\{method=\"GET\",route=\"/r\",le=\"(\\+Inf|[0-9.]+)\"\\} (\\d+)");
        long prev = -1;
        java.util.regex.Matcher m = line.matcher(rendered);
        int found = 0;
        while (m.find()) {
            long value = Long.parseLong(m.group(2));
            assertTrue(value >= prev, "buckets must be monotone: " + rendered);
            prev = value;
            found++;
        }
        assertTrue(found >= 12, "expected all buckets (11 + Inf), found " + found);
        // +Inf must equal the total count
        assertTrue(rendered.contains(
                "http_server_request_duration_seconds_count{method=\"GET\",route=\"/r\"} 4"));
        assertTrue(rendered.contains(
                "http_server_request_duration_seconds_sum{method=\"GET\",route=\"/r\"}"),
                "sum of durations present");
    }

    @Test
    void routeLabelsAreSanitised() {
        Metrics metrics = new Metrics();
        metrics.recordRequest("GET", "/api/x\"y", 200, 0.01);
        String rendered = metrics.render();
        // embedded quote replaced, no malformed label
        assertTrue(rendered.contains("route=\"/api/x'y\""));
    }

    @Test
    void rateLimitGaugesRenderWhenBound() {
        Metrics metrics = new Metrics();
        metrics.bindRateLimiters(
                new com.ims.http.RateLimiter(10, 10, true),
                new com.ims.http.RateLimiter(1.0 / 60, 1, true));
        String rendered = metrics.render();
        assertTrue(rendered.contains("ims_rate_limited_total{bucket=\"general\"}"));
        assertTrue(rendered.contains("ims_rate_limited_total{bucket=\"login\"}"));
    }
}
