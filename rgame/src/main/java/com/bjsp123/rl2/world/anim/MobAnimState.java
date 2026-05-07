package com.bjsp123.rl2.world.anim;
/**
 * Per-mob visual / animation state owned by {@code rgame}'s {@link Animator}. Replaces
 * the engine-side {@code MobRenderState} — {@code rlib} has no presentation state.
 *
 * <p>All values are render-frame counters or pixel offsets; nothing here is
 * persisted (the {@code Animator}'s map is rebuilt from level state on load).
 */
public final class MobAnimState {

    /** Frames to wait before the queued anim begins playing — set from the
     *  {@link AnimQueue} return value at queue time. */
    public int delayFrames;

    // ── Step interpolation ──────────────────────────────────────────────────
    /** Tile-units offset toward the mob's previous position, advanced toward zero
     *  as {@link #stepFrame} ramps to {@link #stepTotal}. */
    public float stepFromDx, stepFromDy;
    public int   stepFrame;
    public int   stepTotal;

    // ── Visual lunge / flinch ───────────────────────────────────────────────
    public float animPeakX, animPeakY;
    public int   animFrame;
    public int   animPeakFrame;
    public int   animEndFrame;

    // ── Teleport fade (real-time ms) ────────────────────────────────────────
    public int teleportFadeMs;
    public int teleportFromX, teleportFromY;

    // ── Real-time particle countdowns (ms) ──────────────────────────────────
    public int fireParticleCountdownMs;
    public int sleepZCountdownMs;

    // ── Spawn-grow ─────────────────────────────────────────────────────────
    /** Frames elapsed in the spawn-grow animation. {@code 0} when no spawn anim
     *  is active. The renderer scales the mob's sprite from {@code 0} to {@code 1}
     *  over {@link #spawnTotalFrames}, anchored to the tile's bottom edge so the
     *  mob reads as "rising up out of nothing". Set by the Animator on a
     *  {@code MobSpawned} event; advanced by the per-frame tick loop. */
    public int spawnFrame;
    public int spawnTotalFrames;
    /** Total spawn-grow duration in frames. ~half a second at the default
     *  framesPerRender=1 cadence. */
    public static final int SPAWN_GROW_FRAMES = 30;

    /** Death-animation timing — flicker twice (4 phases × 6 frames = 24 frames),
     *  then linear fade over 30 frames (~half a second at 60fps). */
    public static final int   DEATH_FLICKER_HALF_FRAMES = 6;
    public static final int   DEATH_FLICKER_FRAMES      = DEATH_FLICKER_HALF_FRAMES * 4;
    public static final int   DEATH_FADE_FRAMES         = 30;
    public static final int   DEATH_TOTAL_FRAMES        = DEATH_FLICKER_FRAMES + DEATH_FADE_FRAMES;
    public static final float DEATH_FLICKER_LOW_ALPHA   = 0.4f;

    public float animOffsetX() { return animOffsetAlong(animPeakX); }
    public float animOffsetY() { return animOffsetAlong(animPeakY); }

    private float animOffsetAlong(float peak) {
        if (animEndFrame <= 0 || animPeakFrame <= 0) return 0f;
        if (animFrame <= animPeakFrame) {
            return peak * (animFrame / (float) animPeakFrame);
        }
        float span = animEndFrame - animPeakFrame;
        if (span <= 0f) return 0f;
        float t = (animFrame - animPeakFrame) / span;
        return peak * (1f - t);
    }
}
