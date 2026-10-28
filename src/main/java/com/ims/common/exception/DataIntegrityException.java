package com.ims.common.exception;

/** 409 - the operation would violate a relational constraint (FK, CHECK, etc.). */
public class DataIntegrityException extends ConflictException {

    public DataIntegrityException(String message) {
        super(message);
    }

    public DataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
