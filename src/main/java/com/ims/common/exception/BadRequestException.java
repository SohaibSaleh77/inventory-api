package com.ims.common.exception;

/** 400 - malformed request that cannot be parsed or understood. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(400, "BAD_REQUEST", message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(400, "BAD_REQUEST", message, cause);
    }
}
