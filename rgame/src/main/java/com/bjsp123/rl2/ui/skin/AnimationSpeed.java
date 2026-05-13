package com.bjsp123.rl2.ui.skin;

import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistent "animation speed" setting. The value is a frames-per-render multiplier:
 * each render frame the {@link com.bjsp123.rl2.world.anim.Animator} advances its
 * frame counters this many times and scales its real-time {@code dtMs} drain
 * proportionally - so 2 cuts every animation duration in half, 4 quarters them.
 *
 * <p>Affects everything driven by render-frame counters: mob step interpolation,
 * lunge / flinch, death flicker + fade, every {@code Effect} frame count, and the
 * {@code AnimQueue} freeze gate (so faster animations also let the game tick again
 * sooner). Real-time ambient cadences (fire particles, sleep-Z) speed up too.
 *
 * <p>Does NOT scale game-simulation timing - turn cost, fire spread tick rate, light-
 * mote spawning. Only visible animations get faster.
 */
public final class AnimationSpeed {

    private static final String KEY             = "rl2-animation-speed";
    private static final String KEY_QUEUE_ACCEL = "rl2-anim-queue-accel";

    /** Default - animations play at their authored frame counts. */
    public static final float DEFAULT_FRAMES_PER_RENDER = 1;

    /** Discrete choices the settings UI exposes. 1 = normal, 2 = 2x faster
     *  (1/2 duration), 4 = 4x faster (1/4 duration). */
    public static final float[] CHOICES = { 0.5f, 1f, 2f, 4f };

    private static Persistence persistence;
    private static float framesPerRender = DEFAULT_FRAMES_PER_RENDER;
    /** Non-persistent override that wins over the stored {@link #framesPerRender}
     *  when {@code > 0}. Used by Arena mode to force 4x playback regardless of
     *  the user's configured setting; cleared when the arena exits. */
    private static float transientOverride = 0;
    /** When true, animations are compressed as the sequential queue depth grows
     *  (25 % at 2, 33 % at 3-4, 50 % at 5-6, 60 % at 7+). Default on. */
    private static boolean queueAccelEnabled = true;

    private AnimationSpeed() {}

    public static void init(Persistence p) {
        persistence = p;
        framesPerRender   = loadFloat(KEY, DEFAULT_FRAMES_PER_RENDER);
        queueAccelEnabled = loadBoolean(KEY_QUEUE_ACCEL, true);
    }

    /** Number of internal animation frames to advance per render frame. Always
     *  {@code >= 1}. The transient override (Arena mode) wins over the stored
     *  user preference when active. */
    public static float framesPerRender() {
        return transientOverride > 0 ? transientOverride : framesPerRender;
    }

    /** Force a non-persistent animation-speed override. Pass 0 to clear and
     *  fall back to the stored user setting. Used by Arena mode to lock
     *  playback at 4x without touching prefs. */
    public static void setTransientOverride(float  n) {
        transientOverride = Math.max(0f, n);
    }

    public static void setFramesPerRender(float n) {
        framesPerRender = n;
        if (persistence != null) persistence.save(KEY, Float.toString(framesPerRender));
    }

    public static boolean queueAccelEnabled() { return queueAccelEnabled; }

    public static void setQueueAccelEnabled(boolean v) {
        queueAccelEnabled = v;
        if (persistence != null) persistence.save(KEY_QUEUE_ACCEL, Boolean.toString(v));
    }

    private static float loadFloat(String key, float fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        try { return Float.parseFloat(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean loadBoolean(String key, boolean fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        return Boolean.parseBoolean(raw);
    }
}
