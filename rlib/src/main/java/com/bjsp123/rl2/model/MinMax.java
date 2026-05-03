package com.bjsp123.rl2.model;

/**
 * A small integer range — minimum and maximum inclusive — used wherever a stat carries
 * a roll range (damage / armour / AP damage / magic resist / etc.). Replaces the
 * historical {@code int[]{min, max}} convention so call sites read as
 * {@code range.min()} / {@code range.max()} / {@code range.average()} instead of
 * positionally indexing a 2-element array.
 *
 * <p>All factories enforce {@code min <= max}: a constructor that would have produced
 * a malformed range is corrected by raising {@code max} to match {@code min}, never
 * the other way round, since a range with negative span isn't meaningful in any of
 * the game systems that consume these. Negative {@code min} is allowed — some stat
 * sources (e.g. cursed items, future debuffs) might emit it.
 *
 * <p>Plain class rather than a {@code record} for the same reason as {@link Point}:
 * GWT 2.11 (the web backend) doesn't support records, and libGDX {@code Json} can't
 * deserialize records reflectively because the JVM forbids {@code Field.set} on
 * record components.
 */
public final class MinMax {

    /** Canonical zero range — used as a neutral element for {@link #plus}. */
    public static final MinMax ZERO = new MinMax(0, 0);

    private final int min;
    private final int max;

    public MinMax() {
        this(0, 0);
    }

    public MinMax(int min, int max) {
        this.min = min;
        this.max = Math.max(min, max);
    }

    public int min() { return min; }
    public int max() { return max; }

    /** Single-value range — useful when a stat has no spread (e.g. base mob armour). */
    public static MinMax of(int v) { return new MinMax(v, v); }

    /** {@code [min, max]} range; convenience for callers that want explicit bounds. */
    public static MinMax of(int min, int max) { return new MinMax(min, max); }

    /** Component-wise sum: {@code (this.min + other.min, this.max + other.max)}.
     *  Treats {@code null} as {@link #ZERO} so callers can chain conditional sources. */
    public MinMax plus(MinMax other) {
        if (other == null) return this;
        return new MinMax(min + other.min, max + other.max);
    }

    /** Mid-point of the range. Useful for expected-damage maths in headless sims. */
    public double average() { return 0.5 * (min + max); }

    /** True iff the range describes "no contribution" — both bounds zero. */
    public boolean isZero() { return min == 0 && max == 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MinMax)) return false;
        MinMax other = (MinMax) o;
        return min == other.min && max == other.max;
    }

    @Override
    public int hashCode() {
        return 31 * min + max;
    }

    @Override
    public String toString() {
        return "MinMax(" + min + ", " + max + ")";
    }
}
