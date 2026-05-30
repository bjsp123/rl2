package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ExplorationEval;
import com.bjsp123.rl2.model.Point;

/** Walk toward the nearest frontier tile so new map is revealed. */
public final class GoalExplore implements Goal {

    public static final GoalExplore INSTANCE = new GoalExplore();

    @Override public String name() { return "EXPLORE"; }

    /** Agent turns past which we EXPLORE even with hostiles in memory. Tuned to the
     *  per-agent-turn counter; see {@link GoalDescend} for the same calibration note. */
    private static final int FATIGUE_EXPLORE_OVERRIDE = 400;

    /** Fires whenever there's a reachable unexplored floor tile, period.
     *  No fatigue ramp, no threat check - this goal only loses to KILL/SURVIVE,
     *  both of which have hard preconditions (visible enemy / hurt + potion). */
    @Override public double score(WorldState s) {
        return frontier(s) != null ? 0.7 : 0.0;
    }

    @Override public boolean isSatisfied(WorldState s) {
        return frontier(s) == null;
    }

    /** Where to walk to satisfy this goal. Caches the target on {@link com.bjsp123.rl2.ai.MobMemory}
     *  so the agent commits to a single frontier rather than oscillating between
     *  adjacent ones each turn (which would burn the entire agent turn budget on
     *  one-step zigzags). The target is reset on arrival, on going stale (50 turns),
     *  or when the cached tile is no longer unknown. */
    public static Point frontier(WorldState s) {
        com.bjsp123.rl2.ai.MobMemory mem = s.memory;
        if (mem == null) return ExplorationEval.nearestFrontier(s.mob, s.level, s.memory);
        com.bjsp123.rl2.model.Point cached = mem.exploreTarget;
        if (cached != null) {
            boolean reached = s.mob.position.equals(cached);
            boolean stale = mem.exploreTargetAge > 50;
            boolean discovered = mem.knownTiles != null
                    && mem.knownTiles[cached.tileX()][cached.tileY()];
            // Also drop the cache if the tile is no longer reachable - e.g. the
            // shortest path went through a tile that's since been blocked, or the
            // initial pick was over a wall the agent can't cross. Without this we
            // burn 50 turns spinning on an unreachable target.
            boolean unreachable = com.bjsp123.rl2.logic.Pathfinder.nextStep(
                    s.level, s.mob, cached) == null;
            if (reached || stale || discovered || unreachable) {
                mem.exploreTarget = null;
                mem.exploreTargetAge = 0;
            }
        }
        if (mem.exploreTarget == null) {
            mem.exploreTarget = pickReachableFrontier(s);
            mem.exploreTargetAge = 0;
        } else {
            mem.exploreTargetAge++;
        }
        return mem.exploreTarget;
    }

    /** Try the nearest-frontier picker; if its result is unreachable, walk through
     *  the rest of the frontier candidates until we find one we can actually path
     *  to. Capped at 8 attempts so we don't spin in pathological maps. */
    private static Point pickReachableFrontier(WorldState s) {
        Point first = ExplorationEval.nearestFrontier(s.mob, s.level, s.memory);
        if (first == null) return null;
        if (com.bjsp123.rl2.logic.Pathfinder.nextStep(s.level, s.mob, first) != null) {
            return first;
        }
        // Iterate alternative candidates that aren't the initial pick.
        return com.bjsp123.rl2.ai.eval.ExplorationEval.nearestReachableFrontier(
                s.mob, s.level, s.memory);
    }

    @Override public String intentDetail(WorldState s) {
        Point f = frontier(s);
        return f == null ? "EXPLORE" : "EXPLORE @ " + f.tileX() + "," + f.tileY();
    }
}
