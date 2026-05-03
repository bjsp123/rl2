package com.bjsp123.rl2.model;

/**
 * Immutable 2-D point in floating-tile coordinates. Plain class rather than a {@code record}
 * because GWT 2.11 (the web backend) doesn't support record classes.
 */
public final class Point {
    private final double x;
    private final double y;

    public Point() {
        this(0, 0);
    }

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double x() { return x; }
    public double y() { return y; }

    public Point translate(double dx, double dy) {
        return new Point(x + dx, y + dy);
    }

    public double distanceTo(Point other) {
        double dx = other.x - x;
        double dy = other.y - y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public int tileX() { return (int) Math.floor(x); }
    public int tileY() { return (int) Math.floor(y); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return Double.compare(p.x, x) == 0 && Double.compare(p.y, y) == 0;
    }

    @Override
    public int hashCode() {
        long xb = Double.doubleToLongBits(x);
        long yb = Double.doubleToLongBits(y);
        int h = (int) (xb ^ (xb >>> 32));
        h = 31 * h + (int) (yb ^ (yb >>> 32));
        return h;
    }

    @Override
    public String toString() {
        return "Point(" + x + ", " + y + ")";
    }
}
