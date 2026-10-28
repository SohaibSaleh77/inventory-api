package com.ims.common.exception;

/**
 * 409 - requested lifecycle transition is not legal from the current state
 * (e.g. approving an already-cancelled purchase order).
 */
public class IllegalStateTransitionException extends ConflictException {

    private final String fromState;
    private final String action;

    public IllegalStateTransitionException(String resourceType, Object id, String fromState, String action) {
        super("Cannot " + action + " " + resourceType + " " + id + " while in status " + fromState);
        this.fromState = fromState;
        this.action = action;
    }

    public String fromState() {
        return fromState;
    }

    public String action() {
        return action;
    }
}
