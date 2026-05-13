package com.bjsp123.rl2.ui.skin;
import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistent boolean preference: when true, the HUD portrait sidebar, look-mode info
 * panel, and the floating "buff applied" effect render the buff as a small sprite from
 * {@code sprites/buffs16.png} instead of the buff's display name. When false, every
 * buff surface falls back to plain text.
 *
 * <p>Defaults to {@link #DEFAULT} (ON) per the user's spec - the icons are the intended
 * presentation. Toggleable via the in-game settings screen so a colour-blind user (or
 * anyone who simply prefers reading the words) can flip back to text.
 *
 * <p>Following the {@link UiPixelScale} / {@link UiScale} pattern: a single static
 * boolean read from {@link Persistence} on init and written back on {@link #set}.
 */
public class UseBuffIcons {
    private static final String  KEY     = "rl2-use-buff-icons";
    public  static final boolean DEFAULT = true;

    private static Persistence persistence;
    private static boolean enabled = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = persistence.load(KEY);
        if (raw != null) enabled = Boolean.parseBoolean(raw);
    }

    public static boolean enabled() { return enabled; }

    public static void set(boolean v) {
        enabled = v;
        if (persistence != null) persistence.save(KEY, Boolean.toString(v));
    }
}
