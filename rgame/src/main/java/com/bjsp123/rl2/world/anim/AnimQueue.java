package com.bjsp123.rl2.world.anim;
/**
 * Render-frame scheduling for visible game-action animations. PlayScreen consults
 * {@link #freezeFrames} as the single tick gate — while &gt; 0 the world holds.
 *
 * <p>Three modes match the engine's animation semantics:
 * <ul>
 *   <li>{@link #concurrent}: this animation runs alongside whatever else is playing;
 *       the freeze stretches only if it would outlast the current contents. Used by
 *       step interpolation, projectile flights, visible deaths, and burst effects.</li>
 *   <li>{@link #sequential}: this animation starts after everything else finishes,
 *       claiming a fresh slot at the queue tail. Used by visible attack lunges so
 *       multiple attacks play one at a time.</li>
 *   <li>{@link #rideLastSlot}: this animation overlaps the most recently scheduled
 *       sequential slot (so a hit flinch plays simultaneously with its attacker's
 *       lunge). Extends the slot only if this animation outlasts what's there.</li>
 * </ul>
 *
 * <p>Each call returns the start delay (frames) the caller should park its own
 * per-mob animation at; {@link #freezeFrames} is decremented once per render frame
 * by {@link #tick()}.
 *
 * <p>Owned exclusively by {@code rgame}. {@code rlib} has no concept of render frames.
 */
public final class AnimQueue {

    /** Frames remaining until every queued visible animation finishes. */
    public int freezeFrames;
    /** Start delay of the most recently queued sequential slot — for ride-along callers. */
    public int currentSlotStart;
    /** Length of the most recently queued sequential slot — for ride-along callers. */
    public int currentSlotLength;

    /** Drain one render frame. Call once per render frame from PlayScreen. */
    public void tick() {
        if (freezeFrames > 0) freezeFrames--;
    }

    public int concurrent(int frames) {
        if (frames <= 0) return 0;
        if (frames > freezeFrames) freezeFrames = frames;
        return 0;
    }

    public int sequential(int frames) {
        if (frames <= 0) return 0;
        int delay = freezeFrames;
        currentSlotStart  = delay;
        currentSlotLength = frames;
        freezeFrames      = delay + frames;
        return delay;
    }

    public int rideLastSlot(int frames) {
        if (frames <= 0) return 0;
        if (currentSlotLength <= 0) return sequential(frames);
        int extra = frames - currentSlotLength;
        if (extra > 0) {
            freezeFrames      += extra;
            currentSlotLength += extra;
        }
        return currentSlotStart;
    }
}
