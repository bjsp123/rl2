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

    boolean isEmpty() { return pending.isEmpty(); }

    boolean fireCompleting(Level level, Animator animator) {
        boolean fired = false;
        for (Iterator<PendingImpact> it = pending.iterator(); it.hasNext(); ) {
            PendingImpact pi = it.next();
            int trigger = pi.effect.impactFrame > 0 ? pi.effect.impactFrame : pi.effect.totalFrames() - 1;
            if (pi.effect.frame < trigger) continue;
            try {
                pi.onComplete.run();
                fired = true;
            } finally {
                it.remove();
            }
        }
        if (fired && level != null) {
            animator.consume(level);
        }
        return fired;
    }
}
