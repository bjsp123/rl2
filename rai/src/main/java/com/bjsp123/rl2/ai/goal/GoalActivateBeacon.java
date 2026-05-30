package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.model.Point;

/**
 * Walk adjacent to a known-inactive beacon to flip it on. Beacons auto-activate
 * when any mob steps into their 8-neighbourhood, so this only needs an
 * {@link com.bjsp123.rl2.ai.action.ActionMoveToward} to an adjacent tile -
 * no dedicated action class.
 */
public final class GoalActivateBeacon implements Goal {

    public static final GoalActivateBeacon INSTANCE = new GoalActivateBeacon();

    @Override public String name() { return "BEACON"; }

    @Override public double score(WorldState s) {
        if (s.memory.knownInactiveBeacons.isEmpty()) return 0.0;
        Point nearest = nearest(s);
        if (nearest == null) return 0.0;
        int d = WorldState.chebyshev(s.mob.position, nearest);
        double killScore = GoalKill.INSTANCE.score(s);
        return Math.min(0.55, 0.35 / (1.0 + d * 0.25) * (1.0 - killScore));
    }

    @Override public boolean isSatisfied(WorldState s) {
        return s.memory.knownInactiveBeacons.isEmpty();
    }

    /** Tile adjacent to the nearest inactive beacon - that's the move destination. */
    public static Point adjacentTo(WorldState s) {
        Point b = nearest(s);
        if (b == null) return null;
        // Any walkable adjacent tile triggers activation; pick a cell next to b
        // close to the mob.
        int bx = b.tileX(), by = b.tileY();
        Point me = s.mob.position;
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = bx + dx, y = by + dy;
                if (x < 0 || y < 0 || x >= s.level.width || y >= s.level.height) continue;
                if (s.level.tiles[x][y].blocksMovement()) continue;
                int d = WorldState.chebyshev(me, new Point(x, y));
                if (d < bestD) { bestD = d; best = new Point(x, y); }
            }
        }
        return best;
    }

    private static Point nearest(WorldState s) {
        Point me = s.mob.position;
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (Point p : s.memory.knownInactiveBeacons) {
            int d = WorldState.chebyshev(me, p);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    @Override public String intentDetail(WorldState s) {
        Point b = nearest(s);
        return b == null ? "BEACON" : "BEACON @ " + b.tileX() + "," + b.tileY();
    }
}
