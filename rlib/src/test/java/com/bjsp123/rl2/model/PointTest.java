package com.bjsp123.rl2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@link Point} value type. */
class PointTest {

    // -- happy path ----------------------------------------------------------

    @Test
    void accessorsAndTranslate() {
        Point p = new Point(1.5, 2.5).translate(1.0, -0.5);
        assertEquals(2.5, p.x(), 0.0);
        assertEquals(2.0, p.y(), 0.0);
    }

    @Test
    void distanceTo() {
        assertEquals(5.0, new Point(0, 0).distanceTo(new Point(3, 4)), 1e-9);
        assertEquals(0.0, new Point(2, 2).distanceTo(new Point(2, 2)), 0.0);
    }

    @Test
    void tileCoordsFloor() {
        Point p = new Point(3.9, 4.1);
        assertEquals(3, p.tileX());
        assertEquals(4, p.tileY());
    }

    @Test
    void equalsAndHashCode() {
        assertEquals(new Point(1, 2), new Point(1, 2));
        assertEquals(new Point(1, 2).hashCode(), new Point(1, 2).hashCode());
        assertNotEquals(new Point(1, 2), new Point(2, 1));
        assertNotEquals(new Point(1, 2), "not a point");
    }

    // -- unhappy / edge ------------------------------------------------------

    @Test
    void tileCoordsFloorNegativeTowardNegativeInfinity() {
        // (int) cast truncates toward zero; Point uses Math.floor, so -0.5 -> -1.
        Point p = new Point(-0.5, -2.1);
        assertEquals(-1, p.tileX());
        assertEquals(-3, p.tileY());
    }

    @Test
    void defaultConstructorIsOrigin() {
        Point p = new Point();
        assertEquals(0.0, p.x(), 0.0);
        assertEquals(0.0, p.y(), 0.0);
    }
}
