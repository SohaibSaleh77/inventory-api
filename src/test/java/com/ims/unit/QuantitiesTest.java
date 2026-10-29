package com.ims.unit;

import com.ims.common.util.Quantities;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Quantity arithmetic incl. moving average cost formula. */
class QuantitiesTest {

    @Test
    void scaleThreeEverywhere() {
        assertEquals(new BigDecimal("1.000"), Quantities.of(BigDecimal.ONE));
        assertEquals(new BigDecimal("0.000"), Quantities.of(null));
    }

    @Test
    void addAndSubtractNormalize() {
        assertEquals(new BigDecimal("2.000"), Quantities.add(BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(new BigDecimal("-1.000"), Quantities.subtract(null, BigDecimal.ONE));
    }

    @Test
    void movingAverageFirstReceipt() {
        // from zero: avg = unit cost
        assertEquals(new BigDecimal("18.5000"), Quantities.movingAverage(
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10"), new BigDecimal("18.50")));
    }

    @Test
    void movingAverageBlendsSecondReceipt() {
        // (10*18.50 + 10*21.50) / 20 = 20.0000
        assertEquals(new BigDecimal("20.0000"), Quantities.movingAverage(
                new BigDecimal("10"), new BigDecimal("18.5000"),
                new BigDecimal("10"), new BigDecimal("21.50")));
    }

    @Test
    void movingAverageIgnoresZeroQuantityIn() {
        assertEquals(new BigDecimal("7.2500"), Quantities.movingAverage(
                new BigDecimal("10"), new BigDecimal("7.2500"),
                BigDecimal.ZERO, new BigDecimal("99.00")));
    }

    @Test
    void compareHandlesNull() {
        assertEquals(-1, Quantities.compare(new BigDecimal("1"), new BigDecimal("2")));
        assertEquals(0, Quantities.compare(null, BigDecimal.ZERO));
        assertEquals(1, Quantities.compare(BigDecimal.ONE, null));
    }

    @Test
    void zeroDetection() {
        assertEquals(true, Quantities.isZero(null));
        assertEquals(true, Quantities.isZero(new BigDecimal("0.000")));
        assertEquals(false, Quantities.isZero(new BigDecimal("0.001")));
    }
}
