package com.ims.common.exception;

/** 401 - missing, invalid, expired or revoked credentials. */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String message) {
        super(401, "UNAUTHENTICATED", message);
    }
}
