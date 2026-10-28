package com.ims.common.exception;

/**
 * Base class for all domain exceptions that map to a specific HTTP status.
 * Carries a stable machine-readable error code plus a human message.
 */
public abstract class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    protected ApiException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    protected ApiException(int httpStatus, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }
}
