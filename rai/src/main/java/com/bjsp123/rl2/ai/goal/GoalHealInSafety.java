package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;

/**
 * Rest to full HP when no threats are in vision. Cheap, low-priority - any
 * combat goal preempts.
 */
public final class GoalHealInSafety implements Goal {

    public static final GoalHealInSafety INSTANCE = new GoalHealInSafety();

    @Override public String name() { return "HEAL"; }

    @Override public double score(WorldState s) {
        if (!s.visibleEnemies.isEmpty()) return 0.0;
        if (s.hpFrac >= 0.95) return 0.0;
        return (1.0 - s.hpFrac) * 0.4;
    }

    @Override public boolean isSatisfied(WorldState s) {
        return s.hpFrac >= 0.95;
    }

    @Override public String intentDetail(WorldState s) {
        return "HEAL " + (int)(s.hpFrac * 100) + "%";
    }
}
