package com.bjsp123.rl2.ai.eval;

import com.bjsp123.rl2.ai.MobMemory;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.TileQuery;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Frontier finding via a BFS from the agent's tile through any cell the agent
 * can actually step on. The first unknown cell we reach is the nearest
 * reachable frontier - guarantees we don't miss any reachable unexplored area.
 */
public final class ExplorationEval {

    private ExplorationEval() {}

    /** Fraction of FLOOR-class tiles the mob has NOT yet seen. */
    public static double unexploredFraction(Level level, MobMemory memory) {
        if (memory.knownTiles == null) return 1.0;
        int total = 0, known = 0;
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (!isFloor(level.tiles[x][y])) continue;
                total++;
                if (memory.knownTiles[x][y]) known++;
            }
        }
        return total == 0 ? 0.0 : 1.0 - (known / (double) total);
    }

    /** First unknown cell encountered by a BFS from the agent's position over
     *  tiles the agent can actually traverse. Returns {@code null} only when
     *  the entire reachable component is already known. */
    public static Point nearestFrontier(Mob mob, Level level, MobMemory memory) {
        return bfsFrontier(mob, level, memory);
    }

    /** Backwards-compat alias - the BFS itself only returns reachable tiles. */
    public static Point nearestReachableFrontier(Mob mob, Level level, MobMemory memory) {
        return bfsFrontier(mob, level, memory);
    }

    private static Point bfsFrontier(Mob mob, Level level, MobMemory memory) {
        if (memory.knownTiles == null || mob.position == null) return null;
        int w = level.width, h = level.height;
        boolean[][] visited = new boolean[w][h];
        Deque<long[]> queue = new ArrayDeque<>();
        int sx = mob.position.tileX(), sy = mob.position.tileY();
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) return null;
        visited[sx][sy] = true;
        queue.add(new long[]{sx, sy});
        // Pass 1: prefer door-frontiers so the agent explores by opening rooms,
        // not by walking around them. Track the first such hit and fall back to
        // the closest non-door unknown if no door hits exist.
        Point firstDoor = null;
        Point firstAny = null;
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            int cx = (int) cur[0], cy = (int) cur[1];
            // Skip the agent's own cell when checking for "unknown" - it's
            // always known by virtue of standing there.
            if (!(cx == sx && cy == sy)) {
                if (!memory.knownTiles[cx][cy]) {
                    Tile t = level.tiles[cx][cy];
                    boolean isDoor = t == Tile.DOOR || t == Tile.DOOR_OPEN
                            || t == Tile.CRYSTAL_DOOR || t == Tile.CRYSTAL_DOOR_OPEN;
                    if (firstAny == null) firstAny = new Point(cx, cy);
                    if (isDoor && firstDoor == null) firstDoor = new Point(cx, cy);
                    // We could return immediately, but we keep going one BFS
                    // layer so door-priority can override - cap at first known
                    // any-hit + first door scan. Simplest: return first hit.
                    continue;
                }
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue;
                    if (visited[nx][ny]) continue;
                    // BFS expansion: only through tiles the agent can stand on.
                    // We DO expand through known-but-not-yet-passable tiles like
                    // closed DOOR (they open on contact); blocksMovementAt
                    // handles the per-mob rules (CHASM/CRYSTAL_DOOR/ONETIME_DOOR)
                    // using the mob's actual faction / flying status.
                    if (TileQuery.blocksMovementAt(level, nx, ny, mob)) continue;
                    visited[nx][ny] = true;
                    queue.add(new long[]{nx, ny});
                }
            }
        }
        return firstAny;
    }

    private static boolean isFloor(Tile t) {
        return t == Tile.FLOOR || t == Tile.FLOOR_WOOD || t == Tile.FLOOR_SPECIAL;
    }

    /** BFS from the agent through walkable cells; return the FIRST cell whose
     *  8-neighbourhood contains an unknown FLOOR-class tile (i.e. the nearest
     *  cell from which a single step would reveal new floor). Returns null when
     *  the agent's reachable component has no unknown-floor neighbours - that's
     *  the "fully explored" signal.
     *
     *  <p>Cleaner than {@link #nearestFrontier} which returns the first unknown
     *  cell itself: that target may itself be unwalkable (e.g. an unknown CHASM
     *  tile beyond a known FLOOR), so the move action's BFS step lands somewhere
     *  else. This picker returns a walkable target the agent can definitely
     *  stand on, and stepping onto it grows FOV. */
    public static Point nearestExploreTarget(Mob mob, Level level, MobMemory memory) {
        if (mob == null || mob.position == null || memory == null
                || memory.knownTiles == null) return null;
        int w = level.width, h = level.height;
        int sx = mob.position.tileX(), sy = mob.position.tileY();
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) return null;
        boolean[][] visited = new boolean[w][h];
        Deque<long[]> queue = new ArrayDeque<>();
        visited[sx][sy] = true;
        queue.add(new long[]{sx, sy});
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            int cx = (int) cur[0], cy = (int) cur[1];
            // Skip own cell as the answer - it has no value as a destination.
            if (!(cx == sx && cy == sy) && hasUnknownFloorNeighbour(level, memory, cx, cy)) {
                return new Point(cx, cy);
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue;
                    if (visited[nx][ny]) continue;
                    if (TileQuery.blocksMovementAt(level, nx, ny, mob)) continue;
                    visited[nx][ny] = true;
                    queue.add(new long[]{nx, ny});
                }
            }
        }
        return null;
    }

    private static boolean hasUnknownFloorNeighbour(Level level, MobMemory mem, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (mem.knownTiles[nx][ny]) continue;
                Tile t = level.tiles[nx][ny];
                if (t == Tile.FLOOR || t == Tile.FLOOR_WOOD || t == Tile.FLOOR_SPECIAL) {
                    return true;
                }
            }
        }
        return false;
    }

    /** First step from the agent toward {@code target} using the same BFS expansion
     *  rules as {@link #bfsFrontier} (TileQuery.blocksMovementAt only - no fog gate,
     *  no occupied-cell gate, no ONETIME_DOOR safety bail). Returns null only if
     *  target is genuinely unreachable under those rules. Used by EXPLORE / DESCEND
     *  actions to guarantee that if BFS frontier-finder said "reachable", the move
     *  action can take a step toward it. */
    public static Point nextStepToTarget(Mob mob, Level level, MobMemory memory, Point target) {
        if (mob == null || mob.position == null || target == null) return null;
        int w = level.width, h = level.height;
        int sx = mob.position.tileX(), sy = mob.position.tileY();
        int tx = target.tileX(), ty = target.tileY();
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) return null;
        if (tx < 0 || ty < 0 || tx >= w || ty >= h) return null;
        if (sx == tx && sy == ty) return null;
        int[][] parent = new int[w][h];
        for (int x = 0; x < w; x++) java.util.Arrays.fill(parent[x], -1);
        boolean[][] visited = new boolean[w][h];
        Deque<long[]> queue = new ArrayDeque<>();
        visited[sx][sy] = true;
        parent[sx][sy] = sx * h + sy;
        queue.add(new long[]{sx, sy});
        boolean found = false;
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            int cx = (int) cur[0], cy = (int) cur[1];
            if (cx == tx && cy == ty) { found = true; break; }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue;
                    if (visited[nx][ny]) continue;
                    // The target tile itself is always allowed - we want a path TO it
                    // even when it's something like STAIRS_DOWN (passable) or a door.
                    if (!(nx == tx && ny == ty)
                            && TileQuery.blocksMovementAt(level, nx, ny, mob)) continue;
                    visited[nx][ny] = true;
                    parent[nx][ny] = cx * h + cy;
                    queue.add(new long[]{nx, ny});
                }
            }
        }
        if (!found) return null;
        int px = tx, py = ty;
        while (true) {
            int p = parent[px][py];
            int qx = p / h, qy = p % h;
            if (qx == sx && qy == sy) return new Point(px, py);
            px = qx; py = qy;
        }
    }
}
