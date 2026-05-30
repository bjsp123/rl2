package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.ai.action.Action;
import com.bjsp123.rl2.ai.goal.Goal;

import java.util.List;

/**
 * Sequence of actions the planner selected for the active {@link Goal}, plus the
 * step index of the next one to execute. Pass 1 uses single-step plans
 * ({@code steps.size() == 1}); the structure is in place for multi-step
 * lookahead later.
 */
public final class Plan {

    public final Goal goal;
    public final List<Action> steps;
    public int cursor;

    public Plan(Goal goal, List<Action> steps) {
        this.goal = goal;
        this.steps = steps;
        this.cursor = 0;
    }

    public boolean isExhausted() { return cursor >= steps.size(); }
    public Action peek() { return cursor < steps.size() ? steps.get(cursor) : null; }
}
