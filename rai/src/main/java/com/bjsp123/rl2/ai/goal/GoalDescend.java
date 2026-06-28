package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

/**
 * Stairs-down helpers for the live decision tree. Formerly a scored Goal; now
 * that {@link com.bjsp123.rl2.ai.Decider} drives descent directly, only the
 * static reachability helpers survive (used by Decider, ActionLibrary, and
 * {@link WorldState#stairsReachable()}).
 */
public final class GoalDescend {

    private GoalDescend() {}

    public static boolean onStairs(WorldState s) {
        return s.level.tiles[s.mob.position.tileX()][s.mob.position.tileY()] == Tile.STAIRS_DOWN;
    }

    /** Called once per tick via {@link WorldState#stairsReachable()}. Uses the same
     *  BFS as the EXPLORE / DESCEND action's step source so a "reachable" verdict
     *  guarantees the move action will produce a step (no Pathfinder/BFS mismatch). */
    public static boolean computeStairsReachable(WorldState s) {
        Point stairs = s.memory.stairsDown != null ? s.memory.stairsDown : s.level.stairsDown;
        if (stairs == null) return false;
        if (s.mob.position.equals(stairs)) return true;
        return com.bjsp123.rl2.ai.eval.ExplorationEval.nextStepToTarget(
                s.mob, s.level, s.memory, stairs) != null;
    }
}
