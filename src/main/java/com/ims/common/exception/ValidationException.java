package com.ims.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 422 - semantically invalid payload, with optional per-field error detail. */
public class ValidationException extends ApiException {

    private final Map<String, String> fieldErrors;

    public ValidationException(String message) {
        this(message, Map.of());
    }

    public ValidationException(String field, String error) {
        super(422, "VALIDATION_FAILED", "Validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(field, error);
        this.fieldErrors = Collections.unmodifiableMap(errors);
    }

    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(422, "VALIDATION_FAILED", message);
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
