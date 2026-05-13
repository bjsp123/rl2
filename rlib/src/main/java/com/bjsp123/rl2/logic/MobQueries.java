package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

/** Pure lookup and occupancy helpers for mobs on a level. */
public final class MobQueries {

    private MobQueries() {}

    /** Returns true if a non-INANIMATE mob or wall blocks the given tile. */
    public static boolean blocksMovement(Level level, Mob self, Point p) {
        int x = p.tileX(), y = p.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        if (level.tiles[x][y].blocksMovement()) return true;
        if (level.tiles[x][y] == Tile.CHASM && !self.effectiveStats().flying) return true;
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
}
