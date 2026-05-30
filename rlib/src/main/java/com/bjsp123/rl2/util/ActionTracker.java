package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.Mob;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * [DEV / DIAGNOSTIC] Lightweight per-fight action counter. Used only by the
 * headless arena rankers; not shipping code.
 *
 * <p>MobSystem / ItemSystem call the {@code bump*} hooks unconditionally;
 * when {@link #active} is false (the default) the bumps are zero-cost so
 * leaving these hooks in production builds has no runtime impact. The arena
 * rankers flip the flag on around each fight, snapshot the counts, then
 * flip it off.
 *
 * <p>Identity-keyed so the tracker survives mobs whose equals/hashCode shift
 * over a fight (e.g. when stats update). Cleared each fight via {@link #reset}.
 */
public final class ActionTracker {

    private static volatile boolean active;
    private static final Map<Mob, int[]> COUNTS = new IdentityHashMap<>();

    private ActionTracker() {}

    /** Index into the per-mob counter array. */
    public static final int MELEE = 0, WAND = 1, BOMB = 2,
            POTION = 3, TOOL = 4, EAT = 5, THROW = 6;
    private static final int N = 7;

    public static void enable() { active = true; }
    public static void disable() { active = false; }
    public static void reset() { COUNTS.clear(); }

    public static void bumpMelee(Mob m)  { bump(m, MELEE);  }
    public static void bumpWand(Mob m)   { bump(m, WAND);   }
    public static void bumpBomb(Mob m)   { bump(m, BOMB);   }
    /** Any DRINK potion (healing or buff). */
    public static void bumpPotion(Mob m) { bump(m, POTION); }
    /** APPLYBUFF tools - jade crab / jade bull / frog. */
    public static void bumpTool(Mob m)   { bump(m, TOOL);   }
    /** Food (EAT use behaviour). */
    public static void bumpEat(Mob m)    { bump(m, EAT);    }
    /** Non-bomb throws (catcherball, throwing knife, thrown potions). */
    public static void bumpThrow(Mob m)  { bump(m, THROW);  }

    private static void bump(Mob m, int idx) {
        if (!active || m == null) return;
        COUNTS.computeIfAbsent(m, k -> new int[N])[idx]++;
    }

    /** Read counts for {@code m}. Returns a fresh zero array when untracked. */
    public static int[] read(Mob m) {
        int[] c = COUNTS.get(m);
        if (c == null) return new int[N];
        int[] out = new int[N];
        System.arraycopy(c, 0, out, 0, N);
        return out;
    }
}
