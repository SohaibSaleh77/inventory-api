package com.ims.common.exception;

/** 409 - optimistic locking failure: the row was changed by another transaction. */
public class OptimisticLockException extends ConflictException {

    public OptimisticLockException(String resourceType, Object id) {
        super(resourceType + " " + id + " was modified concurrently; reload and retry");
    }
}
