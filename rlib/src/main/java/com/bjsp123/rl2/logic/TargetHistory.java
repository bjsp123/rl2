package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/**
 * Shared target-selection memory for the player's cursor-picking modes - look mode and
 * wand / ranged-weapon targeting. Keeps two independent hints so repeat-use of a wand, or
 * pass-through looking, naturally snaps back to the thing the player last cared about.
 *
 * <p>Priority used by {@link #pickInitial} when suggesting a starting cell:
 * <ol>
 *   <li>{@link #lastMob} if it's still on this level.</li>
 *   <li>{@link #lastFloor} if it's in bounds on this level.</li>
 *   <li>The nearest visible hostile (via {@link MobQueries#nearestHostile}).</li>
 *   <li>The player's own tile.</li>
 * </ol>
 *
 * <p>The two slots are updated independently by {@link #record}: a cell with a mob on it
 * updates {@code lastMob}; an empty cell updates {@code lastFloor}. Moving the cursor off
 * a mob onto empty ground does NOT erase the last-mob memory, so a zap -> examine -> zap
 * sequence still snaps back to the enemy on the final activation.
 */
public final class TargetHistory {

    public Mob   lastMob;
    public Point lastFloor;

    /**
     * Record what the cursor is on right now. Mob cells update {@link #lastMob}; empty /
     * terrain-only cells update {@link #lastFloor}. Safe to call every frame in hover-style
     * flows (look mode) - the two slots never overwrite each other.
     */
    public void record(Level level, Point cell) {
        if (level == null || cell == null) return;
        Mob onTile = mobAt(level, cell);
        if (onTile != null) {
            lastMob = onTile;
        } else {
            lastFloor = cell;
        }
    }

    /** Starting cursor for a new activation. See class docs for priority. */
    public Point pickInitial(Level level, Mob player) {
        if (player == null || level == null) return null;
        if (isMobStillValid(lastMob, level) && LevelUtilities.getLineOfSight(level, player, lastMob.position)) 
            return lastMob.position;
        if (isInBounds(lastFloor, level) && LevelUtilities.getLineOfSight(level, player, lastFloor) ) 
            return lastFloor;
        Mob hostile = MobQueries.nearestHostile(player, level);              
        if (hostile != null) return hostile.position;
        return player.position;
    }

    private static Mob mobAt(Level level, Point p) {
        if (level.mobs == null) return null;
        int px = p.tileX(), py = p.tileY();
        for (Mob m : level.mobs) {
            if (m.position == null) continue;
            if (m.position.tileX() == px && m.position.tileY() == py) return m;
        }
        return null;
    }

    private static boolean isMobStillValid(Mob m, Level level) {
        if (m == null || m.position == null || level == null || level.mobs == null) return false;
        return level.mobs.contains(m);
    }

    private static boolean isInBounds(Point p, Level level) {
        if (p == null || level == null) return false;
        int x = p.tileX(), y = p.tileY();
        return x >= 0 && y >= 0 && x < level.width && y < level.height;
    }
}
