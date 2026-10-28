package com.ims.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory token-bucket rate limiter keyed by client IP.
 *
 * <p>Two independent limiters are used in production: a general one for the
 * whole API and a much tighter one for the login endpoint (credential
 * stuffing defence in depth on top of the per-account lockout).
 *
 * <p>State is per-process. When running multiple replicas behind a load
 * balancer the effective budget is the sum of the replicas - the limits
 * should be treated as a safety net, not an exact quota; precise global
 * quotas belong to the edge (ingress/API gateway).
 *
 * <p>Implementation: classic token bucket per key, refilled lazily on
 * access (no background threads). Idle buckets are evicted by a size
 * guard so a scan of unique source IPs cannot grow the map unboundedly.
 */
public final class RateLimiter {

    /** Visible for tests asserting bounded memory under key-scan load. */
    public static final int MAX_TRACKED_KEYS = 100_000;
    private static final int EVICTION_TARGET = 50_000;
    /** A bucket idle for 10x its refill window can be evicted safely. */
    private static final double IDLE_EVICT_MULTIPLIER = 10.0;

    private final double capacity;
    private final double refillPerSecond;
    private volatile boolean enabled;
    private final AtomicLong rejectedTotal = new AtomicLong();
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param requestsPerSecond steady refill rate
     * @param burst             bucket capacity (max instantaneous burst)
     * @param enabled           when false, {@link #acquire} always succeeds
     */
    public RateLimiter(double requestsPerSecond, double burst, boolean enabled) {
        if (requestsPerSecond <= 0 || burst < 1) {
            throw new IllegalArgumentException("rate limit must be positive");
        }
        this.refillPerSecond = requestsPerSecond;
        this.capacity = burst;
        this.enabled = enabled;
    }

    /**
     * Runtime toggle (operational escape hatch; also used by tests to exercise
     * the limiter against the otherwise-unthrottled test instance).
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public static RateLimiter disabled() {
        return new RateLimiter(1, 1, false);
    }

    /**
     * Try to take one token for the key.
     *
     * @return a decision carrying success or the advised retry-after delay
     */
    public Decision acquire(String key) {
        if (!enabled) {
            return Decision.ok();
        }
        Bucket decision = buckets.compute(key, (k, bucket) -> {
            long now = System.nanoTime();
            if (bucket == null) {
                if (buckets.size() >= MAX_TRACKED_KEYS) {
                    evictStaleBuckets(now);
                }
                return new Bucket(capacity, now);
            }
            bucket.refill(now);
            return bucket;
        });
        boolean ok = decision.tryConsume();
        if (!ok) {
            rejectedTotal.incrementAndGet();
        }
        return ok ? Decision.ok()
                : Decision.deny((long) Math.ceil(1.0 / refillPerSecond));
    }

    /** Total rejections since process start (exported as a metric). */
    public long rejectedTotal() {
        return rejectedTotal.get();
    }

    /** Number of tracked client buckets (exported as a metric). */
    public int trackedKeys() {
        return buckets.size();
    }

    private void evictStaleBuckets(long nowNanos) {
        double idleNanos = IDLE_EVICT_MULTIPLIER * (1.0 / refillPerSecond) * 1e9;
        buckets.entrySet().removeIf(e ->
                nowNanos - e.getValue().lastRefillNanos > idleNanos);
        // Still oversized (sustained attack from >50k IPs): drop to target size
        // keeping arbitrary entries - correctness preserved, memory bounded.
        if (buckets.size() > MAX_TRACKED_KEYS) {
            int toRemove = buckets.size() - EVICTION_TARGET;
            var it = buckets.entrySet().iterator();
            while (toRemove-- > 0 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double initialTokens, long nowNanos) {
            this.tokens = initialTokens;
            this.lastRefillNanos = nowNanos;
        }

        synchronized void refill(long nowNanos) {
            double elapsedSeconds = (nowNanos - lastRefillNanos) / 1e9;
            if (elapsedSeconds <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = nowNanos;
        }

        synchronized boolean tryConsume() {
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** Result of an acquire call. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static Decision ok() {
            return new Decision(true, 0);
        }

        static Decision deny(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    /** Deterministic test hook: jittered sleep for retry paths. */
    static void backoff(int attempt) {
        try {
            Thread.sleep(30L + ThreadLocalRandom.current().nextInt(70));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
