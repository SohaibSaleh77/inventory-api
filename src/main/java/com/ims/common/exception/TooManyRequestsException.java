package com.ims.common.exception;

/**
 * The client (per IP) has exceeded its rate-limit budget. Clients must
 * slow down and retry after the advertised delay.
 */
public final class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(429, "RATE_LIMITED", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Advised wait time before the next attempt. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
