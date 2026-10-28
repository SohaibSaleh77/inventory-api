package com.ims.common.exception;

/** 409 - the request conflicts with the current state of the resource. */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(409, "CONFLICT", message);
    }

    public ConflictException(String message, Throwable cause) {
        super(409, "CONFLICT", message, cause);
    }
}
