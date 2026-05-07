package com.bjsp123.rl2.util;

import java.util.Locale;

/**
 * Bidirectional encoding between a world seed (long) and a six-letter
 * alphabetic code (e.g. {@code "ABCDEF"}). The encoding is base-26 over
 * uppercase A-Z, so the code space is {@code 26^6 = 308,915,776} distinct
 * worlds — small enough to type, large enough that two random codes are
 * effectively never the same.
 *
 * <p>The encoding is lossy in one direction: encoding a long that exceeds
 * {@link #SPACE} clips to the low bits, so two long seeds outside the code
 * space can collide. But every six-letter code decodes back to the same long
 * it was encoded from, which is the property the player-facing system relies
 * on (typing the code at game start reproduces the seed exactly).
 */
public final class SeedCode {

    private SeedCode() {}

    /** Total number of distinct codes: 26^6. */
    public static final long SPACE = 308_915_776L;
    private static final int LENGTH = 6;

    /** Encode a long seed as a six-letter uppercase code. Negative or
     *  out-of-range seeds are folded into the {@link #SPACE} window first. */
    public static String encode(long seed) {
        long s = Math.floorMod(seed, SPACE);
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = (char) ('A' + (int) (s % 26));
            s /= 26;
        }
        return new String(out);
    }

    /** Decode a six-letter code back to its long seed. Letters are
     *  case-insensitive; non-letters and wrong lengths throw
     *  {@link IllegalArgumentException}. */
    public static long decode(String code) {
        if (code == null) throw new IllegalArgumentException("seed code is null");
        String s = code.trim().toUpperCase(Locale.ROOT);
        if (s.length() != LENGTH) {
            throw new IllegalArgumentException(
                    "seed code must be " + LENGTH + " letters, got " + s.length());
        }
        long out = 0;
        for (int i = 0; i < LENGTH; i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException(
                        "seed code must be A-Z, got '" + c + "'");
            }
            out = out * 26 + (c - 'A');
        }
        return out;
    }

    /** True iff {@code code} is a syntactically valid six-letter seed
     *  (length 6, all A-Z after upper-casing). */
    public static boolean isValid(String code) {
        if (code == null) return false;
        String s = code.trim();
        if (s.length() != LENGTH) return false;
        for (int i = 0; i < LENGTH; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }
}
