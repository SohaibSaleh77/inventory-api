package com.ims.common.exception;

/**
 * The request body (or Content-Length) exceeds the configured
 * http.maxRequestBodyBytes limit. Rejected before buffering.
 */
public final class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException(String message) {
        super(413, "PAYLOAD_TOO_LARGE", message);
    }
}
