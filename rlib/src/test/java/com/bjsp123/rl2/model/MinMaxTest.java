package com.bjsp123.rl2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@link MinMax} value type. */
class MinMaxTest {

    // -- happy path ----------------------------------------------------------

    @Test
    void accessorsReturnBounds() {
        MinMax m = new MinMax(2, 7);
        assertEquals(2, m.min());
        assertEquals(7, m.max());
        assertEquals(4.5, m.average(), 0.0);
    }

    @Test
    void ofSingleValueGivesZeroSpan() {
        MinMax m = MinMax.of(5);
        assertEquals(5, m.min());
        assertEquals(5, m.max());
        assertEquals(5.0, m.average(), 0.0);
    }

    @Test
    void plusIsComponentWise() {
        MinMax sum = new MinMax(1, 3).plus(new MinMax(4, 5));
        assertEquals(5, sum.min());
        assertEquals(8, sum.max());
    }

    @Test
    void zeroConstantAndIsZero() {
        assertTrue(MinMax.ZERO.isZero());
        assertTrue(new MinMax().isZero());
        assertFalse(new MinMax(0, 1).isZero());
    }

    @Test
    void equalsAndHashCode() {
        assertEquals(new MinMax(2, 4), MinMax.of(2, 4));
        assertEquals(new MinMax(2, 4).hashCode(), MinMax.of(2, 4).hashCode());
        assertNotEquals(new MinMax(2, 4), new MinMax(2, 5));
        assertNotEquals(new MinMax(2, 4), "not a minmax");
    }

    // -- unhappy / edge ------------------------------------------------------

    @Test
    void malformedRangeRaisesMaxToMin() {
        // Documented contract: max < min is corrected by raising max, never
        // lowering min.
        MinMax m = new MinMax(7, 2);
        assertEquals(7, m.min());
        assertEquals(7, m.max());
        assertEquals(0, m.max() - m.min());
    }

    @Test
    void negativeMinIsAllowed() {
        MinMax m = new MinMax(-3, 2);
        assertEquals(-3, m.min());
        assertEquals(2, m.max());
    }

    @Test
    void plusTreatsNullAsZero() {
        MinMax m = new MinMax(3, 9);
        assertEquals(m, m.plus(null));
    }
}
