package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.TileQuery;

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
        return nextStepAvoiding(level, mover, to, null);
    }

    /**
     * Same as {@link #nextStep} but skips any tile in {@code avoid}. Used by the SMART AI
     * to first attempt a route that excludes hazards (e.g. consumed-on-step
     * {@code ONETIME_DOOR} tiles); the caller falls back to the unrestricted
     * {@link #nextStep} when this returns {@code null} and the hazard is unavoidable.
     */
    public static Point nextStepAvoiding(Level level, Mob mover, Point to,
                                         java.util.Set<Point> avoid) {
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

        // Precompute mob occupancy once (O(M)) so canEnter is O(1) per cell
        Arrays.fill(ws.occupied, 0, size, false);
        Arrays.fill(ws.mobSize,  0, size, (byte) 0);
        for (Mob m : level.mobs) {
            if (m == mover) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= w || my >= level.height) continue;
            int mi = cell(mx, my, w);
            ws.occupied[mi] = true;
            ws.mobSize[mi]  = (byte) Math.min(127, m.effectiveStats().size);
        }

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
                if (!canEnter(level, mover, moverFlying, moverSize, nx, ny, tx, ty, ws, avoid)) continue;
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
                                    int x, int y, int tx, int ty, Workspace ws,
                                    java.util.Set<Point> avoid) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        if (TileQuery.blocksMovementAt(level, x, y, mover)) return false;
        if (avoid != null && !(x == tx && y == ty) && avoid.contains(new Point(x, y))) return false;
        if (mover.behavior == Behavior.PLAYER
                && level.explored != null
                && !level.explored[x][y]
                && !hasExploredNeighbour(level, x, y)) {
            return false;
        }
        // Target tile is always traversable; movement system resolves attack/interact on arrival
        if (x == tx && y == ty) return true;
        // O(1) occupancy check from precomputed map
        int idx = cell(x, y, level.width);
        if (!ws.occupied[idx]) return true;
        // Player and SMART AI agents can always plan onto a mob's tile - the move
        // system resolves the contact (attack hostile / swap places with ally) on
        // arrival. Default-AI mobs (MOB/HUNTER/...) still need strict-larger-size
        // to push past, so swarms of equal-size mobs don't auto-trample each other.
        if (mover.isPlayer) return true;
        // Non-player: can push past only a strictly smaller mob (swap-places on arrival)
        return moverSize > (ws.mobSize[idx] & 0xFF);
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
        boolean[] occupied = new boolean[0];
        byte[]    mobSize  = new byte[0];
        int[] heapCell = new int[0];
        int[] heapPriority = new int[0];
        int heapSize;

        void ensure(int size) {
            if (gScore.length < size) {
                gScore    = new int[size];
                parent    = new int[size];
                closed    = new boolean[size];
                occupied  = new boolean[size];
                mobSize   = new byte[size];
            }
            if (heapCell.length < size) {
                heapCell     = new int[size];
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
