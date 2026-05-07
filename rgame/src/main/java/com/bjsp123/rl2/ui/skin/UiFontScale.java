package com.bjsp123.rl2.ui.skin;

import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistent multiplier applied to the base UI bitmap font when it is built in
 * {@link StoneUi#newDefaultFont()}. Every {@code Label} and {@code TextButton}
 * derives its on-screen size from this base, so a single setting rescales the
 * entire UI's text. Per-call {@code setFontScale()} multipliers (e.g. titles
 * at 1.6×) compose multiplicatively on top of this.
 *
 * <p>Independent of {@link LogFontScale} — that one applies on top of the
 * already-scaled base, so a player who finds the body text comfortable but
 * wants the message log even larger can do so without bumping everything else.
 */
public class UiFontScale {

    /** Selectable multipliers exposed by Settings → Graphics → UI Font Size. */
    public static final float[] CHOICES = { 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f };

    private static final String KEY     = "rl2-ui-font-scale-v1";
    public  static final float  DEFAULT = 1.5f;

    private static Persistence persistence;
    private static float scale = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = persistence.load(KEY);
        if (raw != null) {
            try {
                float v = Float.parseFloat(raw);
                if (v > 0f) scale = v;
            } catch (NumberFormatException ignored) { /* keep default */ }
        }
    }

    public static float scale() { return scale; }

    public static void set(float s) {
        if (s <= 0f) return;
        scale = s;
        if (persistence != null) persistence.save(KEY, Float.toString(s));
    }
}
