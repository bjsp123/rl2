package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.ai.action.Action;
import com.bjsp123.rl2.ai.action.ActionLibrary;
import com.bjsp123.rl2.ai.goal.Goal;

import java.util.List;

/**
 * Pass 1 planner: single-step argmax. For the active goal, ask {@link ActionLibrary}
 * for the candidate set, filter to applicable, return the highest-utility one wrapped
 * in a one-step {@link Plan}.
 *
 * <p>The interface accepts multi-step plans so this can be replaced with a bounded-depth
 * GOAP A* later without touching SmartAi.
 */
public final class Planner {

    private Planner() {}

    public static Plan plan(WorldState s, Goal goal) {
        List<Action> candidates = ActionLibrary.enumerate(s, goal);
        Action best = null;
        double bestU = -Double.MAX_VALUE;
        for (Action a : candidates) {
            if (!a.isApplicable(s)) continue;
            double u = a.utility(s);
            if (u > bestU) { bestU = u; best = a; }
        }
        if (best == null) best = new com.bjsp123.rl2.ai.action.ActionWait();
        return new Plan(goal, List.of(best));
    }
}
