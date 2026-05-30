package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.CombatEval;

/**
 * Flee an unwinnable encounter: high SURVIVE pressure AND the worst visible
 * enemy out-threats us enough that engaging would lose. Distinct from SURVIVE
 * (which can be "drink healing potion to win") - FLEE actively retreats.
 */
public final class GoalFlee implements Goal {

    public static final GoalFlee INSTANCE = new GoalFlee();

    @Override public String name() { return "FLEE"; }

    @Override public double score(WorldState s) {
        if (s.nearestEnemy == null) return 0.0;
        double worst = 0.0;
        for (com.bjsp123.rl2.model.Mob e : s.visibleEnemies) {
            double r = CombatEval.threatRating(s.mob, e);
            if (r > worst) worst = r;
        }
        if (worst < 0.7) return 0.0;
        double survive = GoalSurvive.INSTANCE.score(s);
        return Math.min(0.95, survive * worst);
    }

    @Override public boolean isSatisfied(WorldState s) {
        return s.visibleEnemies.isEmpty() || s.hpFrac > 0.85;
    }

    @Override public String intentDetail(WorldState s) {
        return "FLEE";
    }
}
