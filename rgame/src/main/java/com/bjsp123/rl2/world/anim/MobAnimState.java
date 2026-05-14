package com.bjsp123.rl2.world.anim;
/**
 * Per-mob visual / animation state owned by {@code rgame}'s {@link Animator}. Replaces
 * the engine-side {@code MobRenderState} - {@code rlib} has no presentation state.
 *
 * <p>All values are render-frame counters or pixel offsets; nothing here is
 * persisted (the {@code Animator}'s map is rebuilt from level state on load).
 */
public final class MobAnimState {

    /** Frames to wait before the queued anim begins playing - set from the
     *  {@link AnimQueue} return value at queue time. */
    public float delayFrames;

    // -- Step interpolation --------------------------------------------------
    /** Tile-units offset toward the mob's previous position, advanced toward zero
     *  as {@link #stepFrame} ramps to {@link #stepTotal}. */
    public float stepFromDx, stepFromDy;
    public int   stepFrame;
    public int   stepTotal;

    // -- Visual lunge / flinch -----------------------------------------------
    public float animPeakX, animPeakY;
    public int   animFrame;
    public int   animPeakFrame;
    public int   animEndFrame;

    // -- Teleport fade (real-time ms) ----------------------------------------
    public int teleportFadeMs;
    public int teleportFromX, teleportFromY;

    // -- Real-time particle countdowns (ms) ----------------------------------
    public int fireParticleCountdownMs;
    public int sleepZCountdownMs;
    /** Wall-clock countdown until the next levitating-mob foot-puff. Zeroed
     *  out when the mob loses the LEVITATING buff. */
    public int levitatePuffCountdownMs;

    // -- Powerup pickup border flash -----------------------------------------
    /** Remaining frames for the white-border flash on powerup pickup.
     *  Decremented each frame by {@link Animator}; renderer outlines the
     *  player sprite while {@code > 0}. */
    public int borderFlashFrames;
    // -- Spawn-grow ---------------------------------------------------------
    /** Frames elapsed in the spawn-grow animation. {@code 0} when no spawn anim
     *  is active. The renderer scales the mob's sprite from {@code 0} to {@code 1}
     *  over {@link #spawnTotalFrames}, anchored to the tile's bottom edge so the
     *  mob reads as "rising up out of nothing". Set by the Animator on a
     *  {@code MobSpawned} event; advanced by the per-frame tick loop. */
    public int spawnFrame;
    public int spawnTotalFrames;
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
