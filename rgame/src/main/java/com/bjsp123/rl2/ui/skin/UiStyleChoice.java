package com.bjsp123.rl2.ui.skin;
import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistent choice of UI art style. Drives which 9-patch / icon set {@link StoneUi} loads —
 * the sandy "stonebase" look or the terser "minimalist" gold-on-dark look.
 *
 * <p>Static field + persistence. Callers query {@link #mode()} when creating UI skins.
 */
public class UiStyleChoice {

    public enum Mode {
        SHATTERED   ("Shattered"),
        STONEBASE   ("Stonebase"),
        MINIMALIST  ("Minimalist");

        public final String displayName;
        Mode(String displayName) { this.displayName = displayName; }
    }

    private static final String KEY     = "rl2-ui-style-v2";
    public  static final Mode   DEFAULT = Mode.SHATTERED;

    private static Persistence persistence;
    private static Mode mode = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = persistence.load(KEY);
        if (raw != null) {
            try { mode = Mode.valueOf(raw); }
            catch (IllegalArgumentException e) { mode = DEFAULT; }
        }
    }

    public static Mode mode() { return mode; }

    public static void set(Mode m) {
        mode = m;
        if (persistence != null) persistence.save(KEY, m.name());
    }
}
