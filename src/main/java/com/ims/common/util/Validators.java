package com.ims.common.util;

import com.ims.common.exception.ValidationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Field-level validation helpers. Every method throws {@link ValidationException}
 * with the offending field name so API responses can pinpoint problems.
 */
public final class Validators {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    private static final Pattern ALNUM_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]*$");

    private Validators() {
    }

    public static String requireText(String value, String field, int maxLength) {
        String v = Strings.requireNonBlank(value, field);
        if (v.length() > maxLength) {
            throw new ValidationException(field, "length must not exceed " + maxLength);
        }
        return v;
    }

    public static String optionalText(String value, String field, int maxLength) {
        if (Strings.isBlank(value)) {
            return null;
        }
        if (value.trim().length() > maxLength) {
            throw new ValidationException(field, "length must not exceed " + maxLength);
        }
        return value.trim();
    }

    public static String requireCode(String value, String field, int maxLength) {
        String v = Strings.requireNonBlank(value, field).toUpperCase();
        if (v.length() > maxLength || !CODE.matcher(v).matches()) {
            throw new ValidationException(field,
                    "must be 1-" + maxLength + " chars: letters, digits, dot, dash, underscore");
        }
        return v;
    }

    public static String optionalEmail(String value, String field) {
        if (Strings.isBlank(value)) {
            return null;
        }
        String v = value.trim();
        if (v.length() > 150 || !EMAIL.matcher(v).matches()) {
            throw new ValidationException(field, "must be a valid email address");
        }
        return v;
    }

    public static String requireEmail(String value, String field) {
        String v = Strings.requireNonBlank(value, field);
        return optionalEmail(v, field);
    }

    public static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null) {
            throw new ValidationException(field, "is required");
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException(field, "must be greater than zero");
        }
        return value;
    }

    public static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new ValidationException(field, "is required");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException(field, "must not be negative");
        }
        return value;
    }

    /** Optional numeric: absent (null) becomes zero; negative still rejected. */
    public static BigDecimal optionalNonNegative(BigDecimal value, String field) {
        return value == null ? BigDecimal.ZERO : requireNonNegative(value, field);
    }

    /**
     * Login-style handle: letters, digits, dot, dash, underscore - case
     * preserved (unlike {@link #requireCode}, which canonicalizes to upper).
     */
    public static String requireHandle(String value, String field, int maxLength) {
        String v = Strings.requireNonBlank(value, field);
        if (v.length() > maxLength || !CODE.matcher(v).matches()) {
            throw new ValidationException(field,
                    "must be 1-" + maxLength + " chars: letters, digits, dot, dash, underscore");
        }
        return v;
    }

    public static Integer requireIntInRange(Integer value, String field, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new ValidationException(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    public static Long requirePositiveId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new ValidationException(field, "must be a positive id");
        }
        return value;
    }

    public static <T> T requireOneOf(T value, String field, Set<T> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw new ValidationException(field, "must be one of " + allowed);
        }
        return value;
    }

    public static <T> List<T> requireNonEmpty(List<T> list, String field) {
        if (list == null || list.isEmpty()) {
            throw new ValidationException(field, "must contain at least one entry");
        }
        return list;
    }

    public static <K, V> void putIfNotNull(Map<K, V> map, K key, V value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
