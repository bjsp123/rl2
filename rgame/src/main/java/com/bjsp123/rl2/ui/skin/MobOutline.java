package com.bjsp123.rl2.ui.skin;

import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistent settings for the black silhouette outline drawn around every mob in
 * the in-world view. Three knobs:
 * <ul>
 *   <li>{@link #width()} — outline thickness in world pixels. Capped at
 *       {@link #MAX_WIDTH} so the variable-tap-count outline algorithm doesn't
 *       balloon at extreme widths. Float so the renderer can do sub-pixel taps;
 *       at the typical zoom (~0.35) one world pixel covers ~3 screen pixels.</li>
 *   <li>{@link #darkness()} — alpha applied to each outline tap.</li>
 *   <li>{@link #smooth()} — when true, outline taps draw with bilinear filtering
 *       so the silhouette boundary reads as a sub-texel curve. False keeps the
 *       Nearest-filtered pixel-aligned look.</li>
 * </ul>
 */
public final class MobOutline {

    private static final String KEY_W = "rl2-mob-outline-width";
    private static final String KEY_A = "rl2-mob-outline-darkness";
    private static final String KEY_S = "rl2-mob-outline-smooth";

    /** Default outline width in world pixels — slightly thinner than 1 px so the
     *  outline reads as a fine rim against high-contrast backgrounds. */
    public static final float DEFAULT_WIDTH    = 0.6f;
    /** Default outline alpha — lighter than full black so the outline frames the
     *  mob without dominating its silhouette. */
    public static final float DEFAULT_DARKNESS = 0.55f;
    /** Default smoothing — on, since the bilinear taps read better than the
     *  pixel-staircase look at every supported width. */
    public static final boolean DEFAULT_SMOOTH = true;

    /** Hard cap on outline width. The variable-tap-count algorithm scales taps
     *  with circumference, so unbounded widths would blow up the per-frame draw
     *  count. 2 wp ≈ 6 screen px at the default zoom — already plenty thick. */
    public static final float MAX_WIDTH = 2.0f;

    /** Discrete width choices the settings UI exposes (in world pixels). */
    public static final float[] WIDTH_CHOICES    = { 0.0f, 0.3f, 0.6f, 1.0f, 1.5f, 2.0f };
    /** Discrete darkness choices the settings UI exposes. {@code 0} is excluded —
     *  it's redundant with width=0 and produced an invisible-outline state that
     *  could confuse users into thinking the setting was broken. */
    public static final float[] DARKNESS_CHOICES = { 0.3f, 0.55f, 0.75f, 1.0f };

    private static Persistence persistence;
    private static float   width    = DEFAULT_WIDTH;
    private static float   darkness = DEFAULT_DARKNESS;
    private static boolean smooth   = DEFAULT_SMOOTH;

    private MobOutline() {}

    public static void init(Persistence p) {
        persistence = p;
        width    = clampWidth(loadFloat(KEY_W, DEFAULT_WIDTH));
        darkness = loadFloat(KEY_A, DEFAULT_DARKNESS);
        smooth   = loadBool (KEY_S, DEFAULT_SMOOTH);
    }

    public static float   width()    { return width; }
    public static float   darkness() { return darkness; }
    public static boolean smooth()   { return smooth; }

    public static void setWidth(float w) {
        width = clampWidth(w);
        if (persistence != null) persistence.save(KEY_W, Float.toString(width));
    }

    public static void setDarkness(float d) {
        darkness = Math.max(0f, Math.min(1f, d));
        if (persistence != null) persistence.save(KEY_A, Float.toString(darkness));
    }

    public static void setSmooth(boolean s) {
        smooth = s;
        if (persistence != null) persistence.save(KEY_S, Boolean.toString(smooth));
    }

    private static float clampWidth(float w) {
        return Math.max(0f, Math.min(MAX_WIDTH, w));
    }

    private static float loadFloat(String key, float fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        try { return Float.parseFloat(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean loadBool(String key, boolean fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        return Boolean.parseBoolean(raw);
    }
}
