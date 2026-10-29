package com.ims.unit;

import com.ims.common.util.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money arithmetic: scale, rounding, line amounts, tax. */
class MoneyTest {

    @Test
    void normalizesScaleToTwoDecimals() {
        assertEquals(0, BigDecimal.valueOf(10).compareTo(Money.of(new BigDecimal("10"))));
        assertEquals(new BigDecimal("10.00"), Money.of(new BigDecimal("10")));
        assertEquals(new BigDecimal("10.00"), Money.of(new BigDecimal("10.000")));
    }

    @Test
    void nullBecomesZero() {
        assertEquals(new BigDecimal("0.00"), Money.of(null));
        assertEquals(new BigDecimal("0.00"), Money.add(null, null));
    }

    @Test
    void roundsHalfUp() {
        assertEquals(new BigDecimal("10.13"),
                Money.of(new BigDecimal("10.125")));
        assertEquals(new BigDecimal("10.12"),
                Money.of(new BigDecimal("10.124")));
    }

    @Test
    void lineAmountAppliesQuantity() {
        assertEquals(new BigDecimal("1234.50"),
                Money.lineAmount(new BigDecimal("3"), new BigDecimal("411.50"), null));
    }

    @Test
    void lineAmountAppliesDiscount() {
        // 10 * 100 with 5% discount = 950.00
        assertEquals(new BigDecimal("950.00"),
                Money.lineAmount(BigDecimal.TEN, new BigDecimal("100.00"),
                        new BigDecimal("5")));
    }

    @Test
    void taxIsAppliedOnAmount() {
        assertEquals(new BigDecimal("142.50"),
                Money.tax(new BigDecimal("950.00"), new BigDecimal("15")));
    }

    @Test
    void zeroTaxIsFree() {
        assertEquals(new BigDecimal("0.00"), Money.tax(new BigDecimal("100.00"), null));
        assertEquals(new BigDecimal("0.00"), Money.tax(new BigDecimal("100.00"),
                BigDecimal.ZERO));
    }

    @Test
    void signHelpers() {
        assertTrue(Money.isNegative(new BigDecimal("-0.01")));
        assertTrue(Money.isPositive(BigDecimal.ONE));
        assertFalse(Money.isPositive(null));
        assertTrue(Money.isNegative(new BigDecimal("-1")));
    }

    @Test
    void grossPlusTaxMatchesManualMath() {
        // 30 * 912.50 = 27375 + 15% = 31481.25
        BigDecimal gross = Money.multiply(new BigDecimal("30"), new BigDecimal("912.50"));
        BigDecimal withTax = Money.add(gross, Money.tax(gross, new BigDecimal("15")));
        assertEquals(new BigDecimal("31481.25"), withTax);
    }
}
