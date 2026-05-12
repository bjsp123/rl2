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
    public float freezeFrames;
    /** Start delay of the most recently queued sequential slot — for ride-along callers. */
    public float currentSlotStart;
    /** Length of the most recently queued sequential slot — for ride-along callers. */
    public float currentSlotLength;
    /** Set whenever {@link #sequential} or an extending {@link #rideLastSlot}
     *  fires. The AI-catch-up loop in PlayController consults this via
     *  {@link #consumeSequentialFlag} so that ONLY a sequential anim — not
     *  a pile of concurrent mob slides — breaks the loop, letting many
     *  mobs move simultaneously with the player on a single render frame. */
    private boolean sawSequential;
    /** Count of sequential slots pushed during the current drain pass.
     *  Reset by {@link #resetSequentialCount()} at the start of each
     *  {@code Animator.consume()} call; incremented by {@link #sequential()}.
     *  Read by {@link com.bjsp123.rl2.world.anim.Animator#scaleFrames} to
     *  compress later animations when the queue grows deep. */
    private int sequentialCount;

    /** Drain one render frame. Call once per render frame from PlayScreen. */
    public void tick() {
        tick(1);
    }

    /** Drain {@code n} frames at once. Used by PlayScreen to honour the "animation
     *  speed" setting — when the {@link com.bjsp123.rl2.world.anim.Animator} is
     *  advancing N animation frames per render frame, the freeze gate has to drain
     *  at the same pace or the game-tick gate stays closed too long. */
    public void tick(float n) {
        if (n <= 0 || freezeFrames <= 0) return;
        freezeFrames = Math.max(0f, freezeFrames - n);
    }

    public float concurrent(float frames) {
        if (frames <= 0) return 0;
        if (frames > freezeFrames) freezeFrames = frames;
        return 0;
    }

    public int sequentialCount() { return sequentialCount; }

    /** Reset the sequential counter — call once at the start of each drain pass. */
    public void resetSequentialCount() { sequentialCount = 0; }

    public float sequential(float frames) {
        if (frames <= 0) return 0;
        sequentialCount++;
        float delay = freezeFrames;
        currentSlotStart  = delay;
        currentSlotLength = frames;
        freezeFrames      = delay + frames;
        sawSequential     = true;
        return delay;
    }

    public float rideLastSlot(float frames) {
        if (frames <= 0) return 0;
        if (currentSlotLength <= 0) return sequential(frames);
        float extra = frames - currentSlotLength;
        if (extra > 0) {
            freezeFrames      += extra;
            currentSlotLength += extra;
            sawSequential     = true;
        }
        return currentSlotStart;
    }

    /** Returns whether any sequential animation has been queued since the
     *  last call, then clears the flag. Used by PlayController's AI
     *  catch-up loop: a pile of concurrent mob slides should NOT block
     *  further ticks (we want simultaneous mob+player movement), but a
     *  sequential anim (lunge, knockback slide, death-fade chained after
     *  a slide) needs to play out before the next mob acts so its state
     *  isn't clobbered. */
    public boolean consumeSequentialFlag() {
        boolean v = sawSequential;
        sawSequential = false;
        return v;
    }
}
