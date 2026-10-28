package com.ims.common.exception;

/** 404 - requested resource does not exist. */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(404, "NOT_FOUND", message);
    }

    public NotFoundException(String resourceType, Object id) {
        super(404, "NOT_FOUND", resourceType + " not found: " + id);
    }
}
