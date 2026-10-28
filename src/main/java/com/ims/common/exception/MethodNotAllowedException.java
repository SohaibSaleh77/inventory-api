package com.ims.common.exception;

import java.util.Set;

/**
 * The path exists but does not accept the request method. Carries the
 * set of allowed methods so the router can emit a proper Allow header.
 */
public final class MethodNotAllowedException extends ApiException {

    private final Set<String> allowedMethods;

    public MethodNotAllowedException(String message, Set<String> allowedMethods) {
        super(405, "METHOD_NOT_ALLOWED", message);
        this.allowedMethods = Set.copyOf(allowedMethods);
    }

    /** Methods the target path accepts, uppercase. */
    public Set<String> allowedMethods() {
        return allowedMethods;
    }
}
