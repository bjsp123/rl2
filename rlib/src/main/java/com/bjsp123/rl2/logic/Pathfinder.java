package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.logic.MobSystem.Attitude;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.Arrays;

public class Pathfinder {

    private static final int[][] DIRS = {
        { 1, 0}, {-1, 0}, { 0, 1}, { 0,-1},
        { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1}
    };

    /**
     * Returns the next tile to step onto when travelling from {@code from} toward {@code to},
     * or {@code null} if already there or no path exists.
     * INANIMATE mobs are treated as obstacles; other mobs are not.
     */
    public static Point nextStep(Level level, Mob mover, Point to) {
        int sx = mover.position.tileX(), sy = mover.position.tileY();
        int tx = to.tileX(),             ty = to.tileY();
        if (sx == tx && sy == ty) return null;

        int w = level.width;
        int size = w * level.height;
        Workspace ws = WORKSPACE.get();
        ws.ensure(size);

        Arrays.fill(ws.gScore, 0, size, Integer.MAX_VALUE);
        Arrays.fill(ws.parent, 0, size, -1);
        Arrays.fill(ws.closed, 0, size, false);
        ws.heapClear();

        int startIdx = cell(sx, sy, w);
        ws.gScore[startIdx] = 0;
        ws.heapAdd(startIdx, chebyshev(sx, sy, tx, ty));

        com.bjsp123.rl2.model.StatBlock moverStats = mover.effectiveStats();
        boolean moverFlying = moverStats.flying;
        int moverSize = moverStats.size;

        while (!ws.heapEmpty()) {
            int ci = ws.heapPoll();
            if (ws.closed[ci]) continue;
            ws.closed[ci] = true;

            int cx = ci % w, cy = ci / w;
            if (cx == tx && cy == ty) {
                // Trace back to find the first step from start
                int node = ci;
                while (ws.parent[node] != startIdx) node = ws.parent[node];
                return new Point(node % w, node / w);
            }

            int g = ws.gScore[ci];
            for (int[] d : DIRS) {
                int nx = cx + d[0], ny = cy + d[1];
                if (!canEnter(level, mover, moverFlying, moverSize, nx, ny)) continue;
                int ni = cell(nx, ny, w);
                int ng = g + 1;
                if (ng < ws.gScore[ni]) {
                    ws.gScore[ni] = ng;
                    ws.parent[ni] = ci;
                    ws.heapAdd(ni, ng + chebyshev(nx, ny, tx, ty));
                }
            }
        }
        return null;
    }

    private static boolean canEnter(Level level, Mob mover, boolean moverFlying, int moverSize,
                                    int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        if (level.tiles[x][y].blocksMovement()) return false;
        if (level.tiles[x][y] == Tile.CHASM && !moverFlying) return false;
        // Player-only constraint: the player can only path into tiles they've
        // either explored OR that are 8-adjacent to an explored tile, so a
        // tap can step ONE tile into the fog (peeking around a corner) but
        // can't auto-walk a full corridor through territory they've never
        // seen. AI mobs ignore this check - they're omniscient about the
        // geometry.
        if (mover.behavior == Behavior.PLAYER
                && level.explored != null
                && !level.explored[x][y]
                && !hasExploredNeighbour(level, x, y)) {
            return false;
        }
        for (Mob m : level.mobs) {
            if (m.position.tileX() != x || m.position.tileY() != y) continue;
            if (m == mover) continue;
            // Hostile target - pathing through the tile is fine; the movement system
            // resolves arrival as an attack via stepTowardTarget. Inanimate
            // occupants follow the same rule (so anthills + other hostile
            // inanimates can be melee'd by stepping into them); otherwise
            // they remain impassable furniture.
            // PERF: this attitude lookup happens inside A* neighbour expansion;
            // an occupancy/attitude cache would remove a lot of repeated work.
            if (MobSystem.getAttitudeToMob(mover, m) == Attitude.ATTACK) continue;
            if (m.behavior == Behavior.INANIMATE) return false;
            // Player movers keep their old gate: the player can step onto a non-hostile
            // mob's tile (for things like wand-of-dog summon adjacency) - swap-places is
            // a non-player AI behaviour. We only special-case the player->player case
            // here as a no-op since mover != m guarantees it's a different mob.
            if (mover.behavior == Behavior.PLAYER) continue;
            // Non-player mover, occupant is a non-hostile mob - impassable unless the
            // mover is strictly larger, in which case swap-places kicks in (handled in
            // stepTowardTarget on arrival). Equal-size or smaller cannot push past.
            if (moverSize > m.effectiveStats().size) continue;
            return false;
        }
        return true;
    }

    /** True when any of the 8 neighbours of {@code (x, y)} is in-bounds and
     *  flagged explored. Used to relax the player-pathing gate so the
     *  player can step ONE tile into the fog from any explored cell - a
     *  peek around the corner - without being able to auto-travel through
     *  unseen territory. */
    private static boolean hasExploredNeighbour(Level level, int x, int y) {
        if (level.explored == null) return false;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
            if (level.explored[nx][ny]) return true;
        }
        return false;
    }

    private static int chebyshev(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    private static int cell(int x, int y, int w) { return y * w + x; }

    private static final ThreadLocal<Workspace> WORKSPACE =
            ThreadLocal.withInitial(Workspace::new);

    private static final class Workspace {
        int[] gScore = new int[0];
        int[] parent = new int[0];
        boolean[] closed = new boolean[0];
        int[] heapCell = new int[0];
        int[] heapPriority = new int[0];
        int heapSize;

        void ensure(int size) {
            if (gScore.length < size) {
                gScore = new int[size];
                parent = new int[size];
                closed = new boolean[size];
            }
            if (heapCell.length < size) {
                heapCell = new int[size];
                heapPriority = new int[size];
            }
        }

        void heapClear() {
            heapSize = 0;
        }

        boolean heapEmpty() {
            return heapSize == 0;
        }

        void heapAdd(int cell, int priority) {
            if (heapSize >= heapCell.length) {
                int newSize = Math.max(heapSize + 1, heapCell.length * 2 + 1);
                heapCell = Arrays.copyOf(heapCell, newSize);
                heapPriority = Arrays.copyOf(heapPriority, newSize);
            }
            int i = heapSize++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (heapPriority[p] <= priority) break;
                heapCell[i] = heapCell[p];
                heapPriority[i] = heapPriority[p];
                i = p;
            }
            heapCell[i] = cell;
            heapPriority[i] = priority;
        }

        int heapPoll() {
            int result = heapCell[0];
            int cell = heapCell[--heapSize];
            int priority = heapPriority[heapSize];
            int i = 0;
            while (true) {
                int left = i * 2 + 1;
                if (left >= heapSize) break;
                int right = left + 1;
                int child = right < heapSize && heapPriority[right] < heapPriority[left]
                        ? right : left;
                if (heapPriority[child] >= priority) break;
                heapCell[i] = heapCell[child];
                heapPriority[i] = heapPriority[child];
                i = child;
            }
            if (heapSize > 0) {
                heapCell[i] = cell;
                heapPriority[i] = priority;
            }
            return result;
        }
    }
}
