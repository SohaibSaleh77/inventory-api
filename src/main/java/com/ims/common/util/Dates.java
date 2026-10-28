package com.ims.common.util;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Date/time plumbing. All instants are UTC; the clock is injectable for tests. */
public final class Dates {

    private static Clock clock = Clock.systemUTC();

    private Dates() {
    }

    /** Test hook: freeze or control time. */
    public static void useClock(Clock testClock) {
        clock = testClock;
    }

    public static void useSystemClock() {
        clock = Clock.systemUTC();
    }

    public static Instant now() {
        return Instant.now(clock);
    }

    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    public static Timestamp sqlNow() {
        return Timestamp.from(now());
    }

    public static Timestamp sql(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant fromSql(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public static Date sqlDate(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }

    public static LocalDate localDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, ZoneId.of("UTC"));
    }

    public static LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new com.ims.common.exception.ValidationException(fieldName,
                    "must be an ISO-8601 date (yyyy-MM-dd): '" + value + "'");
        }
    }
}
