package com.bjsp123.rl2.world.anim;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.world.render.Effect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Deferred game-state callbacks tied to completing projectile effects. */
final class PendingImpactQueue {
    private static final class PendingImpact {
        final Effect effect;
        final Runnable onComplete;
        PendingImpact(Effect effect, Runnable onComplete) {
            this.effect = effect;
            this.onComplete = onComplete;
        }
    }

    private final List<PendingImpact> pending = new ArrayList<>();

    void add(Effect effect, Runnable onComplete) {
        pending.add(new PendingImpact(effect, onComplete));
    }

    /** Attach an extra deferred callback to the MOST RECENTLY ADDED pending
     *  impact's arc. Used by {@link Animator#consume} to slide damage popup
     *  / death fade / blast burst event dispatches behind the current
     *  in-flight projectile so the on-screen sequence still reads "arc
     *  flies -> impact -> target reacts" even though rlib already mutated
     *  state synchronously at throw time. Returns false if there's no
     *  active pending impact (caller falls back to immediate dispatch). */
    boolean addToLatestArc(Runnable onComplete) {
        if (pending.isEmpty() || onComplete == null) return false;
        PendingImpact head = pending.get(pending.size() - 1);
        Runnable prior = head.onComplete;
        // Chain: existing visual-flourish callback first, then this new
        // deferred event dispatch. Order matters - the impact flash should
        // play before any subsequent damage popup.
        Runnable chained = () -> { prior.run(); onComplete.run(); };
        pending.set(pending.size() - 1, new PendingImpact(head.effect, chained));
        return true;
    }

    boolean isEmpty() { return pending.isEmpty(); }

    boolean fireCompleting(Level level, Animator animator) {
        // Collect and remove all ready impacts FIRST, then run callbacks.
        // The previous in-place iterate+remove+run pattern threw a
        // ConcurrentModificationException whenever a callback queued a new
        // pending impact (e.g. a mirror-player mage firing a wand whose
        // missile arc adds itself to the queue, or any cascade where the
        // impact resolution triggers a follow-up projectile). Draining
        // first decouples mutation from iteration; newly-added impacts
        // sit in {@code pending} and are processed on a later tick.
        List<PendingImpact> ready = null;
        for (Iterator<PendingImpact> it = pending.iterator(); it.hasNext(); ) {
            PendingImpact pi = it.next();
            int trigger = pi.effect.impactFrame > 0 ? pi.effect.impactFrame : pi.effect.totalFrames() - 1;
            if (pi.effect.frame < trigger) continue;
            if (ready == null) ready = new ArrayList<>();
            ready.add(pi);
            it.remove();
        }
        if (ready == null) return false;
        for (PendingImpact pi : ready) {
            pi.onComplete.run();
        }
        if (level != null) animator.consume(level);
        return true;
    }
}
