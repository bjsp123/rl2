package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.TileQuery;

/** Pure lookup and occupancy helpers for mobs on a level. */
public final class MobQueries {

    private MobQueries() {}

    /** Returns true if a non-INANIMATE mob or wall blocks the given tile. */
    public static boolean blocksMovement(Level level, Mob self, Point p) {
        int x = p.tileX(), y = p.tileY();
        if (TileQuery.blocksMovementAt(level, x, y, self)) return true;
        for (Mob m : level.mobs) {
            if (m == self) continue;
            if (m.position.tileX() == x && m.position.tileY() == y) return true;
        }
        return false;
    }

    public static Mob mobAt(Level level, Point p) {
        int x = p.tileX(), y = p.tileY();
        for (Mob m : level.mobs) {
            if (m.position.tileX() == x && m.position.tileY() == y) return m;
        }
        return null;
    }

    /** True iff the level can accept a new effect-driven mob. */
    public static boolean levelHasRoomForSpawn(Level level) {
        return level != null && level.mobs != null
                && level.mobs.size() < GameBalance.MAX_MOBS_ON_LEVEL;
    }

    /** Count live mobs of {@code type} currently on the level. O(N) over level.mobs. */
    public static int countMobsOfType(Level level, String type) {
        if (level == null || level.mobs == null || type == null) return 0;
        int n = 0;
        for (Mob m : level.mobs) if (type.equals(m.mobType)) n++;
        return n;
    }

    /** Count living hostiles on the level: excludes the player, its pets
     *  (owned), and inanimate scenery. Drives the RL-54 renewing-enemy cap. */
    public static int countLivingHostiles(Level level) {
        if (level == null || level.mobs == null) return 0;
        int n = 0;
        for (Mob m : level.mobs) {
            if (m == null || m.hp <= 0) continue;
            if (m.isPlayer || m.owner != null) continue;
            if (m.behavior == Mob.Behavior.INANIMATE) continue;
            n++;
        }
        return n;
    }

    /**
     * Nearest currently-visible hostile mob to {@code around}, by Chebyshev distance.
     * Used by AI and by UI/controller targeting helpers. Skips allies, INANIMATE mobs,
     * and anything outside the visible set. Returns {@code null} when none qualify.
     */
    public static Mob nearestHostile(Mob around, Level level) {
        if (around == null) return null;
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        int ax = around.position.tileX(), ay = around.position.tileY();
        for (Mob m : level.mobs) {
            if (m == around || MobSystem.isAlly(m, around)) continue;
            if (m.behavior == Mob.Behavior.INANIMATE) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            int d = Math.max(Math.abs(mx - ax), Math.abs(my - ay));
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }
}
