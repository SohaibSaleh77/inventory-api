package com.ims.common.exception;

/**
 * 409 - the resource is referenced by other records and cannot be deleted
 * (FK with ON DELETE RESTRICT).
 */
public class ResourceInUseException extends ConflictException {

    public ResourceInUseException(String resourceType, Object id, String referencedBy) {
        super(resourceType + " " + id + " is referenced by " + referencedBy
                + " and cannot be deleted");
    }
}
