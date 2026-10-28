package com.ims.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Stock quantities: DECIMAL(12,3) in the database, scale 3, HALF_UP everywhere. */
public final class Quantities {

    public static final int SCALE = 3;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Quantities() {
    }

    public static BigDecimal of(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(int value) {
        return BigDecimal.valueOf(value).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return of((a == null ? ZERO : a).add(b == null ? ZERO : b));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return of((a == null ? ZERO : a).subtract(b == null ? ZERO : b));
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    public static int compare(BigDecimal a, BigDecimal b) {
        return (a == null ? ZERO : a).compareTo(b == null ? ZERO : b);
    }

    /**
     * Moving average cost calculation:
     * newAvg = (onHand * oldAvg + qtyIn * unitCost) / (onHand + qtyIn)
     */
    public static BigDecimal movingAverage(BigDecimal onHand, BigDecimal oldAvg,
                                           BigDecimal qtyIn, BigDecimal unitCost) {
        BigDecimal existingValue = (onHand == null ? ZERO : onHand)
                .multiply(oldAvg == null ? BigDecimal.ZERO : oldAvg);
        BigDecimal incomingValue = (qtyIn == null ? ZERO : qtyIn)
                .multiply(unitCost == null ? BigDecimal.ZERO : unitCost);
        BigDecimal newQty = (onHand == null ? ZERO : onHand).add(qtyIn == null ? ZERO : qtyIn);
        if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
            return oldAvg == null ? BigDecimal.ZERO.setScale(4) : oldAvg.setScale(4);
        }
        return existingValue.add(incomingValue)
                .divide(newQty, 4, RoundingMode.HALF_UP);
    }
}
