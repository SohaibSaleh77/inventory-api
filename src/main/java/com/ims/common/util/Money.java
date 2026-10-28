package com.ims.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * All money arithmetic goes through this class:
 * DECIMAL(14,2) in the database, scale 2, HALF_UP rounding - everywhere.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    /** Normalize to scale 2 (never null-safe input: null becomes 0.00). */
    public static BigDecimal of(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(long majorUnits) {
        return BigDecimal.valueOf(majorUnits).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return of((a == null ? ZERO : a).add(b == null ? ZERO : b));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return of((a == null ? ZERO : a).subtract(b == null ? ZERO : b));
    }

    public static BigDecimal multiply(BigDecimal qty, BigDecimal unitAmount) {
        BigDecimal q = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal u = unitAmount == null ? ZERO : unitAmount;
        return of(q.multiply(u));
    }

    /** qty * unitAmount with optional percentage discount, normalized to scale 2. */
    public static BigDecimal lineAmount(BigDecimal qty, BigDecimal unitAmount,
                                        BigDecimal discountPct) {
        BigDecimal gross = multiply(qty, unitAmount);
        if (discountPct != null && discountPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    discountPct.divide(BigDecimal.valueOf(100), 6, ROUNDING));
            gross = gross.multiply(factor);
        }
        return of(gross);
    }

    /** Apply a percentage tax on an amount. */
    public static BigDecimal tax(BigDecimal amount, BigDecimal taxPct) {
        if (taxPct == null || taxPct.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        return of(amount.multiply(taxPct).divide(BigDecimal.valueOf(100), 6, ROUNDING));
    }

    /** Negate (for credit notes / returns). */
    public static BigDecimal negate(BigDecimal value) {
        return of(value == null ? ZERO : value.negate());
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
