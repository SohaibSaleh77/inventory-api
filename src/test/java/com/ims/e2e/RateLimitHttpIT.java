package com.ims.e2e;

import com.ims.testing.ApiTestServer;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level rate limiting: the shared test instance normally runs with
 * the limiter disabled; this suite toggles it on (with the production
 * defaults of 50 rps / burst 100) and proves the 429 + Retry-After
 * contract under a parallel burst, then restores the off state.
 */
class RateLimitHttpIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static ApiTestServer api;
    static String adminToken;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        api = env.api();
        adminToken = api.login(users.adminUsername, TestUsers.PASSWORD);
        // enable enforcement with the production-shaped buckets
        env.app.generalRateLimiter.setEnabled(true);
        env.app.loginRateLimiter.setEnabled(true);
    }

    @AfterAll
    static void tearDown() {
        // restore the disabled-by-default test state for other suites
        env.app.generalRateLimiter.setEnabled(false);
        env.app.loginRateLimiter.setEnabled(false);
        env.truncateAll();
    }

    @Test
    void burstIsCappedThenRejectedWithRetryAfter() throws Exception {
        // 130 CONCURRENT requests overwhelm the burst bucket (100) long
        // before the 50 rps refill can mask it (sequential requests would
        // refill ~1 token each 20ms and never trip).
        int total = 130;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        String[] retryAfter = new String[1];

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ApiTestServer.ApiResponse response = api.get("/api/health/live",
                            null);
                    if (response.status() == 429) {
                        rejected.incrementAndGet();
                        synchronized (retryAfter) {
                            retryAfter[0] = response.header("retry-after");
                        }
                    } else if (response.status() == 200) {
                        accepted.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // counted as neither
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all requests must complete");
        pool.shutdownNow();

        assertTrue(rejected.get() > 0,
                "the burst cap must eventually reject (accepted=" + accepted.get()
                        + ", rejected=" + rejected.get() + ")");
        assertTrue(accepted.get() >= 50,
                "early requests must succeed, accepted=" + accepted.get());
        assertNotNull(retryAfter[0], "429 must carry Retry-After");
        assertTrue(Integer.parseInt(retryAfter[0]) >= 1);
        assertEquals(total - accepted.get() - rejected.get(), 0,
                "every request must be accounted for");
    }
}
