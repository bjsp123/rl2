package com.bjsp123.rl2.ui.skin;
import com.bjsp123.rl2.persistence.Persistence;

/** Persistent UI scale factor — affects font size and HUD/menu element sizing. */
public class UiScale {
    private static final String KEY = "rl2-ui-scale";

    public static final float   DEFAULT = 2.0f;
    public static final float[] CHOICES = { 1.0f, 1.5f, 2.0f, 2.5f, 3.0f };

    private static Persistence persistence;
    private static float scale = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = persistence.load(KEY);
        if (raw != null) {
            try { scale = Float.parseFloat(raw); }
            catch (NumberFormatException e) { scale = DEFAULT; }
        }
    }

    public static float scale() { return scale; }

    public static void set(float s) {
        scale = s;
        if (persistence != null) persistence.save(KEY, Float.toString(s));
    }
}
