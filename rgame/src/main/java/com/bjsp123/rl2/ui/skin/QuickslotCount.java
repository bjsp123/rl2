package com.bjsp123.rl2.ui.skin;
import com.bjsp123.rl2.persistence.Persistence;

/** Persistent quickslot-count preference — how many action slots appear on
 *  the HUD and are bindable in the inventory detail view. */
public class QuickslotCount {
    private static final String KEY = "rl2-quickslot-count";

    public static final int   DEFAULT = 8;
    public static final int[] CHOICES = { 4, 6, 8, 9 };

    private static Persistence persistence;
    private static int count = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = p.load(KEY);
        if (raw == null) { count = DEFAULT; return; }
        try {
            int v = Integer.parseInt(raw);
            for (int c : CHOICES) { if (c == v) { count = v; return; } }
        } catch (NumberFormatException ignored) {}
        count = DEFAULT;
    }

    public static int count() { return count; }

    public static void set(int n) {
        count = n;
        if (persistence != null) persistence.save(KEY, Integer.toString(n));
    }
}
