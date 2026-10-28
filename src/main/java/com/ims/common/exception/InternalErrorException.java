package com.ims.common.exception;

/** 500 - unexpected internal failure. Details are logged, never leaked to clients. */
public class InternalErrorException extends ApiException {

    public InternalErrorException(String message) {
        super(500, "INTERNAL_ERROR", message);
    }

    public InternalErrorException(String message, Throwable cause) {
        super(500, "INTERNAL_ERROR", message, cause);
    }
}
