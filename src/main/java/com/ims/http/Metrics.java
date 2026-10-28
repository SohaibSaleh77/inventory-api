package com.ims.http;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Prometheus text-format (v0.0.4) metrics registry.
 *
 * <p>Exports:
 * <ul>
 *   <li>{@code http_server_requests_total{method,route,status}} counter</li>
 *   <li>{@code http_server_request_duration_seconds} cumulative histogram
 *       with fixed buckets (le labels) + _sum/_count, keyed by route</li>
 *   <li>{@code ims_rate_limited_total} and {@code ims_rate_limit_tracked_keys}
 *       from the rate limiters</li>
 *   <li>{@code ims_http_requests_active} gauge for in-flight requests</li>
 *   <li>HikariCP pool gauges and JVM basics (heap, uptime)</li>
 *   <li>{@code ims_build_info{version}} </li>
 * </ul>
 *
 * <p>Routes are recorded as the route PATTERN (e.g. /api/products/{id}),
 * never the raw path, so label cardinality is bounded by the route table.
 * All counters are monotonic since process start.
 */
public final class Metrics {

    /** Histogram buckets in seconds (aligned with common Prometheus defaults). */
    static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1,
            0.25, 0.5, 1.0, 2.5, 5.0, 10.0};

    private static final String[] BUCKET_LABELS = new String[BUCKETS.length + 1];

    static {
        for (int i = 0; i < BUCKETS.length; i++) {
            BUCKET_LABELS[i] = formatBucket(BUCKETS[i]);
        }
        BUCKET_LABELS[BUCKETS.length] = "+Inf";
    }

    public interface PoolGauges {
        int active();

        int idle();

        int total();

        int pending();
    }

    private record CounterKey(String method, String route, String status) {
    }

    private record HistKey(String method, String route) {
    }

    private final Map<CounterKey, AtomicLong> requestCounters = new ConcurrentHashMap<>();
    private final Map<HistKey, long[]> histogramCounts = new ConcurrentHashMap<>();
    private final Map<HistKey, java.util.concurrent.atomic.DoubleAdder> histogramSums
            = new ConcurrentHashMap<>();
    private final Map<HistKey, AtomicLong> histogramTotals = new ConcurrentHashMap<>();
    private final AtomicLong activeRequests = new AtomicLong();
    private final long startedAtNanos = System.nanoTime();
    private final long startedAtEpochMillis = System.currentTimeMillis();
    private volatile PoolGauges poolGauges;
    private volatile String version = "unknown";
    private volatile RateLimiter generalRateLimiter;
    private volatile RateLimiter loginRateLimiter;

    /** Configure the HikariCP gauge source (called once at wiring time). */
    public void bindPool(PoolGauges gauges) {
        this.poolGauges = gauges;
    }

    /** Record the running application version for ims_build_info. */
    public void bindVersion(String version) {
        this.version = version == null || version.isBlank() ? "unknown" : version;
    }

    /** Expose rate limiter gauges (wired once at startup). */
    public void bindRateLimiters(RateLimiter general, RateLimiter login) {
        this.generalRateLimiter = general;
        this.loginRateLimiter = login;
    }

    /** Track in-flight request count. */
    public void requestStarted() {
        activeRequests.incrementAndGet();
    }

    public void requestFinished() {
        activeRequests.decrementAndGet();
    }

    /** Record one completed request. */
    public void recordRequest(String method, String routePattern, int status,
                              double durationSeconds) {
        CounterKey key = new CounterKey(safe(method), safe(routePattern),
                Integer.toString(status));
        requestCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();

        HistKey histKey = new HistKey(safe(method), safe(routePattern));
        long[] counts = histogramCounts.computeIfAbsent(histKey,
                k -> new long[BUCKETS.length + 1]);
        double sum;
        synchronized (counts) {
            int bucketIndex = BUCKETS.length; // +Inf
            for (int i = 0; i < BUCKETS.length; i++) {
                if (durationSeconds <= BUCKETS[i]) {
                    bucketIndex = i;
                    break;
                }
            }
            counts[bucketIndex]++;
        }
        histogramSums.computeIfAbsent(histKey, k -> new java.util.concurrent.atomic.DoubleAdder())
                .add(durationSeconds);
        histogramTotals.computeIfAbsent(histKey, k -> new AtomicLong())
                .incrementAndGet();
    }

    /** Render the full registry in Prometheus text exposition format. */
    public String render() {
        StringBuilder out = new StringBuilder(16 * 1024);

        // ---- http_server_requests_total ----
        out.append("# HELP http_server_requests_total Total HTTP requests.\n");
        out.append("# TYPE http_server_requests_total counter\n");
        Map<String, List<String>> sorted = new TreeMap<>();
        requestCounters.forEach((key, counter) -> {
            String labels = "method=\"" + key.method() + "\",route=\""
                    + key.route() + "\",status=\"" + key.status() + "\"";
            sorted.computeIfAbsent(labels, l -> new ArrayList<>())
                    .add("http_server_requests_total{" + labels + "} "
                            + counter.get());
        });
        sorted.values().forEach(lines -> lines.forEach(l -> out.append(l).append('\n')));

        // ---- http_server_request_duration_seconds histogram ----
        out.append("# HELP http_server_request_duration_seconds ")
                .append("HTTP request duration in seconds.\n");
        out.append("# TYPE http_server_request_duration_seconds histogram\n");
        Map<String, Object[]> hist = new TreeMap<>();
        histogramCounts.forEach((key, counts) -> hist.put(key.method() + "|" + key.route(),
                new Object[]{key, counts}));
        hist.values().forEach(entry -> {
            HistKey key = (HistKey) entry[0];
            long[] counts = (long[]) entry[1];
            java.util.concurrent.atomic.DoubleAdder sum = histogramSums.get(key);
            AtomicLong total = histogramTotals.get(key);
            synchronized (counts) {
                long cumulative = 0;
                String base = "http_server_request_duration_seconds_bucket"
                        + "{method=\"" + key.method() + "\",route=\"" + key.route() + "\",le=\"";
                for (int i = 0; i < counts.length; i++) {
                    cumulative += counts[i];
                    out.append(base).append(BUCKET_LABELS[i]).append("\"} ")
                            .append(cumulative).append('\n');
                }
                out.append("http_server_request_duration_seconds_count")
                        .append("{method=\"").append(key.method())
                        .append("\",route=\"").append(key.route()).append("\"} ")
                        .append(total == null ? 0 : total.get()).append('\n');
                out.append("http_server_request_duration_seconds_sum")
                        .append("{method=\"").append(key.method())
                        .append("\",route=\"").append(key.route()).append("\"} ")
                        .append(sum == null ? 0.0 : sum.sum()).append('\n');
            }
        });

        // ---- in-flight gauge ----
        out.append("# HELP ims_http_requests_active Currently in-flight requests.\n");
        out.append("# TYPE ims_http_requests_active gauge\n");
        out.append("ims_http_requests_active ").append(activeRequests.get()).append('\n');

        // ---- process / jvm ----
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory());
        out.append("# HELP jvm_memory_used_bytes JVM memory usage.\n");
        out.append("# TYPE jvm_memory_used_bytes gauge\n");
        out.append("jvm_memory_used_bytes{area=\"heap\"} ").append(usedMb).append('\n');
        out.append("jvm_memory_max_bytes{area=\"heap\"} ")
                .append(rt.maxMemory()).append('\n');

        long uptimeSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
        out.append("# HELP ims_process_uptime_seconds Process uptime.\n");
        out.append("# TYPE ims_process_uptime_seconds gauge\n");
        out.append("ims_process_uptime_seconds ").append(uptimeSeconds).append('\n');

        out.append("# HELP ims_process_start_time_seconds Process start time.\n");
        out.append("# TYPE ims_process_start_time_seconds gauge\n");
        out.append("ims_process_start_time_seconds ")
                .append(startedAtEpochMillis / 1000L).append('\n');

        out.append("# HELP jvm_threads_current JVM thread count.\n");
        out.append("# TYPE jvm_threads_current gauge\n");
        out.append("jvm_threads_current ")
                .append(ManagementFactory.getThreadMXBean().getThreadCount()).append('\n');

        // ---- build info ----
        out.append("# HELP ims_build_info Build information.\n");
        out.append("# TYPE ims_build_info gauge\n");
        out.append("ims_build_info{version=\"").append(version).append("\"} 1\n");

        // ---- rate limiting ----
        out.append("# HELP ims_rate_limited_total Requests rejected by rate limiting.\n");
        out.append("# TYPE ims_rate_limited_total counter\n");
        appendGauge(out, "ims_rate_limited_total{bucket=\"general\"}",
                generalRateLimiter == null ? null
                        : (int) generalRateLimiter.rejectedTotal());
        appendGauge(out, "ims_rate_limited_total{bucket=\"login\"}",
                loginRateLimiter == null ? null
                        : (int) loginRateLimiter.rejectedTotal());
        out.append("# HELP ims_rate_limit_tracked_clients Rate limiter tracked client buckets.\n");
        out.append("# TYPE ims_rate_limit_tracked_clients gauge\n");
        appendGauge(out, "ims_rate_limit_tracked_clients{bucket=\"general\"}",
                generalRateLimiter == null ? null : generalRateLimiter.trackedKeys());
        appendGauge(out, "ims_rate_limit_tracked_clients{bucket=\"login\"}",
                loginRateLimiter == null ? null : loginRateLimiter.trackedKeys());

        // ---- connection pool ----
        PoolGauges gauges = this.poolGauges;
        Integer active = null;
        Integer idle = null;
        Integer total = null;
        Integer pending = null;
        if (gauges != null) {
            active = gauges.active();
            idle = gauges.idle();
            total = gauges.total();
            pending = gauges.pending();
        }
        out.append("# HELP db_pool_connections Connection pool state.\n");
        out.append("# TYPE db_pool_connections gauge\n");
        appendGauge(out, "db_pool_connections{state=\"active\"}", active);
        appendGauge(out, "db_pool_connections{state=\"idle\"}", idle);
        appendGauge(out, "db_pool_connections{state=\"total\"}", total);
        appendGauge(out, "db_pool_threads_pending", pending);

        return out.toString();
    }

    private static void appendGauge(StringBuilder out, String name, Integer value) {
        out.append(name).append(' ')
                .append(value == null ? 0 : value).append('\n');
    }

    private static String safe(String value) {
        return value == null ? "UNKNOWN" : value.replace("\"", "'");
    }

    private static String formatBucket(double bucket) {
        return bucket == Math.floor(bucket) && !Double.isInfinite(bucket)
                ? String.format("%.0f", bucket) : String.format("%s", bucket);
    }
}
