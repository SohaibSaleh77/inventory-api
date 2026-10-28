package com.ims.common.exception;

/** 409 - attempt to create a resource whose natural key already exists. */
public class DuplicateResourceException extends ConflictException {

    public DuplicateResourceException(String resourceType, String key) {
        super(resourceType + " already exists with key '" + key + "'");
    }

    public DuplicateResourceException(String message) {
        super(message);
    }
}
