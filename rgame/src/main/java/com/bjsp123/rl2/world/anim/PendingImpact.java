package com.bjsp123.rl2.world.anim;
import com.bjsp123.rl2.world.render.Effect;

/**
 * A deferred game-state mutation tied to a visible projectile's completion. The
 * {@link com.bjsp123.rl2.world.anim.Animator} holds these alongside the in-flight effects;
 * when the effect's frame counter reaches its lifetime the {@link #onComplete} runnable
 * fires, calling back into {@code rlib} to apply damage, banish a ghost, etc.
 *
 * <p>Replaces the legacy {@code PlayScreen.resolveCompletingMissiles} loop that walked
 * {@code level.effects} and dispatched on the Effect's logic-payload fields. Carrying
 * the callback in rgame keeps {@code rlib.Effect} free of any logic state.
 */
public final class PendingImpact {
    public final Effect effect;
    public final Runnable onComplete;

    public PendingImpact(Effect effect, Runnable onComplete) {
        this.effect = effect;
        this.onComplete = onComplete;
    }
}
