package com.ims.core;

import com.ims.http.IdempotencyService;
import com.ims.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background housekeeping: purges expired/revoked API tokens and stale
 * idempotency records on a fixed schedule. Previously this logic existed
 * but nothing ever invoked it - api_tokens grew without bound in
 * production.
 *
 * <p>Runs on a single daemon scheduled thread; failures are logged and
 * retried on the next tick (the job is intentionally idempotent).
 */
public final class HousekeepingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingService.class);

    private final TokenService tokenService;
    private final IdempotencyService idempotencyService;
    private final ScheduledExecutorService scheduler;

    public HousekeepingService(TokenService tokenService,
                               IdempotencyService idempotencyService,
                               long intervalMinutes) {
        this.tokenService = tokenService;
        this.idempotencyService = idempotencyService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ims-housekeeping");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Start the periodic job. */
    public void start(long intervalMinutes) {
        // First purge shortly after boot (cheap, catches overnight growth),
        // then on the fixed interval.
        scheduler.scheduleAtFixedRate(this::runOnce, 1, intervalMinutes,
                TimeUnit.MINUTES);
        log.info("Housekeeping scheduled every {} minute(s)", intervalMinutes);
    }

    /** One housekeeping pass (also invoked directly by integration tests). */
    public void runOnce() {
        try {
            int tokens = tokenService.purgeExpired();
            int idempotency = idempotencyService.purgeExpired();
            if (tokens > 0 || idempotency > 0) {
                log.info("Housekeeping: purged {} expired token(s), "
                                + "{} expired idempotency record(s)",
                        tokens, idempotency);
            }
        } catch (RuntimeException e) {
            log.error("Housekeeping pass failed - will retry next tick", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
