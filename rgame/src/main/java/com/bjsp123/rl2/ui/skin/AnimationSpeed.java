package com.bjsp123.rl2.ui.skin;

import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistent "animation speed" setting. The value is a frames-per-render multiplier:
 * each render frame the {@link com.bjsp123.rl2.world.anim.Animator} advances its
 * frame counters this many times and scales its real-time {@code dtMs} drain
 * proportionally — so 2 cuts every animation duration in half, 4 quarters them.
 *
 * <p>Affects everything driven by render-frame counters: mob step interpolation,
 * lunge / flinch, death flicker + fade, every {@code Effect} frame count, and the
 * {@code AnimQueue} freeze gate (so faster animations also let the game tick again
 * sooner). Real-time ambient cadences (fire particles, sleep-Z) speed up too.
 *
 * <p>Does NOT scale game-simulation timing — turn cost, fire spread tick rate, light-
 * mote spawning. Only visible animations get faster.
 */
public final class AnimationSpeed {

    private static final String KEY = "rl2-animation-speed";

    /** Default — animations play at their authored frame counts. */
    public static final int DEFAULT_FRAMES_PER_RENDER = 1;

    /** Discrete choices the settings UI exposes. 1 = normal, 2 = 2× faster
     *  (1/2 duration), 4 = 4× faster (1/4 duration). */
    public static final int[] CHOICES = { 1, 2, 4 };

    private static Persistence persistence;
    private static int framesPerRender = DEFAULT_FRAMES_PER_RENDER;
    /** Non-persistent override that wins over the stored {@link #framesPerRender}
     *  when {@code > 0}. Used by Arena mode to force 4× playback regardless of
     *  the user's configured setting; cleared when the arena exits. */
    private static int transientOverride = 0;

    private AnimationSpeed() {}

    public static void init(Persistence p) {
        persistence = p;
        framesPerRender = clamp(loadInt(KEY, DEFAULT_FRAMES_PER_RENDER));
    }

    /** Number of internal animation frames to advance per render frame. Always
     *  {@code >= 1}. The transient override (Arena mode) wins over the stored
     *  user preference when active. */
    public static int framesPerRender() {
        return transientOverride > 0 ? transientOverride : framesPerRender;
    }

    /** Force a non-persistent animation-speed override. Pass 0 to clear and
     *  fall back to the stored user setting. Used by Arena mode to lock
     *  playback at 4× without touching prefs. */
    public static void setTransientOverride(int n) {
        transientOverride = Math.max(0, n);
    }

    public static void setFramesPerRender(int n) {
        framesPerRender = clamp(n);
        if (persistence != null) persistence.save(KEY, Integer.toString(framesPerRender));
    }

    private static int clamp(int n) {
        // Snap to the nearest choice so settings file edits can't push us into
        // unintended values (e.g. negative or huge).
        int best = DEFAULT_FRAMES_PER_RENDER;
        int bestDist = Integer.MAX_VALUE;
        for (int c : CHOICES) {
            int d = Math.abs(c - n);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    private static int loadInt(String key, int fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
