package com.ims.common.util;

/** String helpers shared across validation code. */
public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String requireNonBlank(String s, String fieldName) {
        if (s == null || s.isBlank()) {
            throw new com.ims.common.exception.ValidationException(fieldName, "must not be blank");
        }
        return s.trim();
    }

    public static String truncate(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /** Normalize to a canonical code form: uppercase, single-spaced, trimmed. */
    public static String toCode(String s) {
        return s == null ? null : s.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    public static boolean matches(String s, String regex) {
        return s != null && s.matches(regex);
    }
}
