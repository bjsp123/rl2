package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.CombatEval;

/**
 * Kill visible hostiles. Score is the aggregate threat of all visible enemies -
 * a swarm of weak goblins is treated as roughly as urgent as one large brute.
 */
public final class GoalKill implements Goal {

    public static final GoalKill INSTANCE = new GoalKill();

    @Override public String name() { return "KILL"; }

    /** Top priority - any visible enemy preempts all other goals. */
    @Override public double score(WorldState s) {
        return s.visibleEnemies.isEmpty() ? 0.0 : 0.9;
    }

    @Override public boolean isSatisfied(WorldState s) {
        return s.visibleEnemies.isEmpty();
    }

    @Override public String intentDetail(WorldState s) {
        return s.nearestEnemy == null
                ? "KILL"
                : "KILL " + (s.nearestEnemy.name != null ? s.nearestEnemy.name : s.nearestEnemy.mobType);
    }
}
