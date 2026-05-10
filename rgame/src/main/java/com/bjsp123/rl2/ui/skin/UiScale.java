package com.bjsp123.rl2.ui.skin;
import com.bjsp123.rl2.persistence.Persistence;

/** Persistent UI scale factor — affects font size and HUD/menu element sizing. */
public class UiScale {
    /** Persistence key — the {@code -v3} suffix invalidates older keys after
     *  the V2 UI rebuild redefined sensible scale ranges. Without the bump,
     *  a leftover {@code 2.0} value from a previous default would keep the
     *  world at half size on first launch, making everything read as twice
     *  too big. */
    private static final String KEY = "rl2-ui-scale-v3";

    public static final float   DEFAULT = 1.0f;
    public static final float[] CHOICES = { 0.75f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f };

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
            } catch (NumberFormatException e) { scale = DEFAULT; }
        }
    }

    public static float scale() { return scale; }

    public static void set(float s) {
        scale = s;
        if (persistence != null) persistence.save(KEY, Float.toString(s));
    }
}
