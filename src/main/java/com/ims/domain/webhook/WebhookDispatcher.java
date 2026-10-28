package com.ims.domain.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background outbox drainer: periodically delivers due webhook rows.
 *
 * <p>Single daemon thread, fixed rate. One failing endpoint never blocks
 * the queue for long because each delivery attempt is individually timed
 * out and individually transacted; failures reschedule with exponential
 * backoff and eventually park as FAILED for operator attention.
 */
public final class WebhookDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /** How many due deliveries one pass may process. */
    static final int BATCH_SIZE = 50;

    private final WebhookService webhooks;
    private final ScheduledExecutorService scheduler;

    public WebhookDispatcher(WebhookService webhooks) {
        this.webhooks = webhooks;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ims-webhook-dispatch");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Start periodic dispatch. */
    public void start(long intervalSeconds) {
        scheduler.scheduleAtFixedRate(this::runOnce, 2, intervalSeconds,
                TimeUnit.SECONDS);
        log.info("Webhook dispatcher scheduled every {} second(s)", intervalSeconds);
    }

    /** One dispatch pass (also invoked directly by integration tests). */
    public void runOnce() {
        try {
            webhooks.deliverDue(BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Webhook dispatch pass failed - will retry next tick", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
