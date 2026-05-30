package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

/**
 * Travel to and use STAIRS_DOWN. Fires whenever the stairs are reachable - KILL
 * (0.9) preempts when enemies visible, SURVIVE (0.85) when hurt with a potion,
 * EXPLORE (0.7) when reachable unexplored tiles exist. Anything else, descend.
 */
public final class GoalDescend implements Goal {

    public static final GoalDescend INSTANCE = new GoalDescend();

    @Override public String name() { return "DESCEND"; }

    @Override public double score(WorldState s) {
        Point stairs = s.memory.stairsDown != null ? s.memory.stairsDown : s.level.stairsDown;
        if (stairs == null) return 0.0;
        if (s.memory.stairsDown == null) s.memory.stairsDown = stairs;
        if (!s.stairsReachable()) return 0.0;
        if (onStairs(s)) return 0.95;
        return 0.6;
    }

    @Override public boolean isSatisfied(WorldState s) { return false; }

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

    @Override public String intentDetail(WorldState s) {
        Point t = s.memory.stairsDown;
        return t == null ? "DESCEND" : "DESCEND @ " + t.tileX() + "," + t.tileY();
    }
}
