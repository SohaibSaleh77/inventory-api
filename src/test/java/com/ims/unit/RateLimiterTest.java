package com.ims.unit;

import com.ims.http.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token-bucket limiter behaviour: burst capacity, steady-state refill,
 * per-key isolation, disabled mode and rejection accounting.
 */
class RateLimiterTest {

    @Test
    void disabledLimiterAlwaysAllows() {
        RateLimiter limiter = RateLimiter.disabled();
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.acquire("1.2.3.4").allowed());
        }
        assertEquals(0, limiter.rejectedTotal());
    }

    @Test
    void burstCapacityIsEnforced() {
        // 1 rps, burst 3: first 3 pass, 4th is rejected
        RateLimiter limiter = new RateLimiter(1.0, 3, true);
        assertTrue(limiter.acquire("a").allowed());
        assertTrue(limiter.acquire("a").allowed());
        assertTrue(limiter.acquire("a").allowed());
        RateLimiter.Decision denied = limiter.acquire("a");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1);
        assertEquals(1, limiter.rejectedTotal());
    }

    @Test
    void bucketRefillsOverTime() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(50.0, 2, true);
        assertTrue(limiter.acquire("a").allowed());
        assertTrue(limiter.acquire("a").allowed());
        assertFalse(limiter.acquire("a").allowed());
        // at 50 rps a token regenerates every ~20ms
        Thread.sleep(120);
        assertTrue(limiter.acquire("a").allowed());
    }

    @Test
    void keysAreIsolated() {
        RateLimiter limiter = new RateLimiter(1.0, 1, true);
        assertTrue(limiter.acquire("10.0.0.1").allowed());
        assertFalse(limiter.acquire("10.0.0.1").allowed());
        assertTrue(limiter.acquire("10.0.0.2").allowed());
    }

    @Test
    void retryAfterMatchesRefillRate() {
        RateLimiter limiter = new RateLimiter(0.5, 1, true);
        assertTrue(limiter.acquire("a").allowed());
        RateLimiter.Decision denied = limiter.acquire("a");
        assertFalse(denied.allowed());
        assertEquals(2, denied.retryAfterSeconds()); // 1/0.5
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimiter(0, 10, true));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimiter(10, 0, true));
    }

    @Test
    void evictionKeepsMemoryBounded() {
        RateLimiter limiter = new RateLimiter(100.0, 1, true);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < RateLimiter.MAX_TRACKED_KEYS + 100; i++) {
            String key = "10." + (i / 65536 % 256) + "." + (i / 256 % 256) + "." + (i % 256);
            keys.add(key);
            limiter.acquire(key);
        }
        assertTrue(limiter.trackedKeys() <= RateLimiter.MAX_TRACKED_KEYS,
                "tracked keys must stay bounded, got " + limiter.trackedKeys());
    }

    @Test
    void runtimeToggleDisablesEnforcement() {
        RateLimiter limiter = new RateLimiter(1.0, 1, true);
        assertTrue(limiter.acquire("a").allowed());
        assertFalse(limiter.acquire("a").allowed());
        limiter.setEnabled(false);
        assertTrue(limiter.acquire("a").allowed());
        assertTrue(limiter.acquire("a").allowed());
        limiter.setEnabled(true);
        assertFalse(limiter.acquire("a").allowed());
    }
}
