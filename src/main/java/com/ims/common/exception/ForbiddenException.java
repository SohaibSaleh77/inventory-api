package com.ims.common.exception;

/** 403 - authenticated but not permitted to perform the action. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(403, "FORBIDDEN", message);
    }
}
