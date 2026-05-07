package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Level.Surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Runtime mutations to a {@link Level}'s surface layer (water / blood / oil). Level-generation
 * code in {@link LevelFactory} writes {@link Level#surface} directly — those placements are
 * deliberate and don't need spreading.
 *
 * <p>The single public call is {@link #addSurface(Level, int, int, Surface)}: place a surface
 * at (x, y), but if that cell already holds the target surface, spread outward through the
 * saturated region and place the surface at a random boundary tile instead. "Boundary" is the
 * nearest ring of cells that can hold a surface and don't yet have this one.
 */
public final class SurfaceSystem {

    private static final Random RANDOM = new Random();
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private SurfaceSystem() {}

    /**
     * Try to place {@code surface} at {@code p}. If the target cell already holds that same
     * surface, BFS outward through the saturated patch and place the surface on a random
     * tile in the nearest ring of floor-like cells that don't yet have it. No-op if
     * {@code p} is out of bounds or can't hold a surface, or if the whole reachable region
     * is already saturated.
     */
    public static void addSurface(Level level, Point p, Surface surface) {
        if (p == null) return;
        int x = p.tileX(), y = p.tileY();
        if (!canHoldSurface(level, x, y)) return;

        // Simple case: target cell is empty (or has a different surface) — write it and done.
        if (level.surface[x][y] != surface) {
            level.surface[x][y] = surface;
            emitSurfaceChanged(level, x, y, surface);
            return;
        }

        // Saturated at the source. Walk outward through cardinally-adjacent cells that already
        // have this surface, collecting ring-by-ring candidates — floor-like neighbours whose
        // surface differs. Place on a random candidate from the first non-empty ring.
        int w = level.width, h = level.height;
        boolean[][] visited = new boolean[w][h];
        ArrayDeque<int[]> frontier = new ArrayDeque<>();
        frontier.addLast(new int[]{x, y});
        visited[x][y] = true;
        List<int[]> candidates = new ArrayList<>();

        while (!frontier.isEmpty()) {
            int ringSize = frontier.size();
            candidates.clear();
            for (int i = 0; i < ringSize; i++) {
                int[] cur = frontier.pollFirst();
                for (int[] d : DIRS) {
                    int nx = cur[0] + d[0], ny = cur[1] + d[1];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    if (visited[nx][ny]) continue;
                    visited[nx][ny] = true;
                    if (!canHoldSurface(level, nx, ny)) continue;
                    if (level.surface[nx][ny] == surface) {
                        // Already saturated — extend the search through this cell.
                        frontier.addLast(new int[]{nx, ny});
                    } else {
                        candidates.add(new int[]{nx, ny});
                    }
                }
            }
            if (!candidates.isEmpty()) {
                int[] chosen = candidates.get(RANDOM.nextInt(candidates.size()));
                level.surface[chosen[0]][chosen[1]] = surface;
                emitSurfaceChanged(level, chosen[0], chosen[1], surface);
                return;
            }
        }
        // Entire reachable region already holds the surface — nothing we can do.
    }

    private static boolean canHoldSurface(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.tiles[x][y].isFloorLike();
    }

    /** Post a {@link com.bjsp123.rl2.event.GameEvent.SurfaceChanged} event for
     *  the renderer to emit a coloured fountain at the changed tile. Skipped
     *  silently when the level has no event sink (level-gen). */
    private static void emitSurfaceChanged(Level level, int x, int y, Surface surface) {
        if (level.events == null) return;
        level.events.add(new com.bjsp123.rl2.event.GameEvent.SurfaceChanged(
                new Point(x, y), surface));
    }
}
