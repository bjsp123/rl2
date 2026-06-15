package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/** Visibility, projectile-line, and redacted-name helpers for mobs. */
public final class MobVisibility {
    private MobVisibility() {}

    public static Point firstMobBlocking(Level level, Point from, Point to, Mob shooter) {
        if (level == null || from == null || to == null) return to;
        int x0 = from.tileX(), y0 = from.tileY();
        int x1 = to.tileX(),   y1 = to.tileY();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            if (!(x == x0 && y == y0)) {
                for (Mob m : level.mobs) {
                    if (m == shooter || m.position == null) continue;
                    if (m.position.tileX() == x && m.position.tileY() == y) {
                        return m.position;
                    }
                }
                if (blocksProjectile(level, x, y)) {
                    return new Point(x, y);
                }
            }
            if (x == x1 && y == y1) return to;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
    }

    public static boolean projectileLineReaches(Level level, Point from, Point to, Mob shooter) {
        Point impact = firstMobBlocking(level, from, to, shooter);
        return impact != null && to != null
                && impact.tileX() == to.tileX()
                && impact.tileY() == to.tileY();
    }

    /** Returns true if no wall or closed door lies strictly between {@code from}
     *  and {@code to}. Mobs, lamps, statues, altars, and thrones are ignored —
     *  a jump arcs over furniture and creatures but cannot pass through solid
     *  terrain or closed doors. */
    public static boolean jumpPathClear(Level level, Point from, Point to) {
        if (level == null || from == null || to == null) return true;
        int x0 = from.tileX(), y0 = from.tileY();
        int x1 = to.tileX(),   y1 = to.tileY();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            if (!(x == x0 && y == y0) && blocksJump(level, x, y)) return false;
            if (x == x1 && y == y1) return true;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
    }

    private static boolean blocksJump(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        com.bjsp123.rl2.model.Tile t = level.tiles[x][y];
        return t == null || t == com.bjsp123.rl2.model.Tile.WALL || t.isClosedDoor();
    }

    public static boolean trajectoryTouchesVisible(Level level, Point from, Point to) {
        if (level == null || level.visible == null) return false;
        if (from == null && to == null) return false;
        if (from == null) return tileVisible(level, to.tileX(), to.tileY());
        if (to == null)   return tileVisible(level, from.tileX(), from.tileY());
        int x0 = from.tileX(), y0 = from.tileY();
        int x1 = to.tileX(),   y1 = to.tileY();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            if (tileVisible(level, x, y)) return true;
            if (x == x1 && y == y1) return false;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
    }

    public static boolean isVisibleToPlayer(Level level, Mob mob) {
        if (level == null || mob == null || mob.position == null) return false;
        if (mob.isPlayer) return true;
        if (level.visible == null) return false;
        int x = mob.position.tileX(), y = mob.position.tileY();
        return tileVisible(level, x, y);
    }

    public static String nameForLog(Level level, Mob mob) {
        if (mob == null) return "something";
        if (mob.isPlayer) {
            return mob.name != null ? mob.name : "?";
        }
        if (!isVisibleToPlayer(level, mob)) return "something";
        return mob.name != null ? mob.name : "?";
    }

    private static boolean tileVisible(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.visible[x][y];
    }

    private static boolean blocksProjectile(Level level, int x, int y) {
        return com.bjsp123.rl2.model.TileQuery.blocksProjectileAt(level, x, y);
    }
}
