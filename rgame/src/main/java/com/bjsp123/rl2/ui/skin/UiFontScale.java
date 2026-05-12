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
    public static final float[] CHOICES = { 0.75f, 1.0f, 1.5f, 2.0f };

    /** Persistence key — the {@code -v2} suffix invalidates the prior
     *  {@code -v1} key after CHOICES + DEFAULT were retuned in the V2 UI
     *  rebuild, so a leftover {@code 1.5} value doesn't keep fonts at 150%
     *  on first launch. */
    private static final String KEY     = "rl2-ui-font-scale-v2";
    public  static final float  DEFAULT = 1.0f;

    private static Persistence persistence;
    private static float scale = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = persistence.load(KEY);
        if (raw != null) {
            try {
                float v = Float.parseFloat(raw);
                // Accept only values that match a current choice — defensive
                // against tampered persistence or a CHOICES array narrowed in
                // a future revision. Out-of-list values fall back to default.
                for (float c : CHOICES) {
                    if (Math.abs(v - c) < 0.001f) { scale = v; return; }
                }
                scale = DEFAULT;
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
