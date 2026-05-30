package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;

import java.util.List;

/**
 * Each tick, score every registered goal under the current state and pick the
 * highest. Applies a 0.1 hysteresis margin against {@link com.bjsp123.rl2.ai.MobMemory#lastGoal}
 * to prevent flip-flopping (e.g. SURVIVE vs. KILL on one HP tick).
 *
 * <p>This is the "fuzzy" layer the user asked for: rules-based scoring across
 * many candidate objectives, picking the most pressing.
 */
public final class GoalSelector {

    /** Hysteresis: a new goal must beat the previous one by this margin to switch. */
    public static final double HYSTERESIS = 0.1;

    /** Ordered list of every goal in play. Order is the tie-break when scores match. */
    public static final List<Goal> ALL = List.of(
            GoalSurvive.INSTANCE,
            GoalFlee.INSTANCE,
            GoalKill.INSTANCE,
            GoalEat.INSTANCE,
            GoalHealInSafety.INSTANCE,
            GoalEquipBetter.INSTANCE,
            GoalPickupKnown.INSTANCE,
            GoalActivateBeacon.INSTANCE,
            GoalExplore.INSTANCE,
            GoalDescend.INSTANCE
    );

    private GoalSelector() {}

    /**
     * Pick the highest-scoring un-satisfied goal, with sticky hysteresis on the
     * previous tick's choice. {@code lastGoal} / {@code lastScore} live on the
     * mob's {@link com.bjsp123.rl2.ai.MobMemory}.
     */
    public static GoalChoice select(WorldState s, Goal lastGoal, double lastScore) {
        Goal best = null;
        double bestScore = -1.0;
        for (Goal g : ALL) {
            if (g.isSatisfied(s)) continue;
            double score = g.score(s);
            if (score > bestScore) { bestScore = score; best = g; }
        }
        // Sticky: keep last goal if its current score is within the margin of the new winner.
        // Skip hysteresis when lastNow == 0 - a goal that no longer scores anything is
        // not "competitive"; without this gate, PICKUP gets locked in forever after the
        // nearby item is picked up and lastNow drops to 0.
        if (lastGoal != null && !lastGoal.isSatisfied(s)) {
            double lastNow = lastGoal.score(s);
            if (lastNow > 0 && best != lastGoal && bestScore - lastNow < HYSTERESIS) {
                return new GoalChoice(lastGoal, lastNow);
            }
        }
        return new GoalChoice(best, bestScore);
    }

    public record GoalChoice(Goal goal, double score) {}
}
