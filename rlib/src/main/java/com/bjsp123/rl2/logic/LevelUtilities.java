package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Hub for one-shot queries about a {@link Level} — pathfinding, neighbor sampling, visibility,
 * attitude. None of these methods mutate state; they just answer questions callers have about
 * the current world. AI code, effect code, and the renderer all go through here rather than
 * re-implementing the same primitives in five places.
 */
public final class LevelUtilities {

    /**
     * Options bundle for {@link #getRandomNeighbor}. Public mutable fields — GWT-compatible
     * and cheaper than a builder. Construct, tweak, pass.
     *
     * <p>Defaults: 4-way neighbors, no cascade, no surface filter.
     */
    public static final class NeighborOptions {

        /** How to filter candidates by the {@link Level#surface} grid. */
        public enum SurfaceFilter {
            /** Ignore surface state — any cell qualifies. */
            IGNORE,
            /** Only pick cells whose surface matches {@link LevelUtilities.NeighborOptions#surfaceType}. */
            REQUIRE,
            /** Only pick cells whose surface does <em>not</em> match {@link LevelUtilities.NeighborOptions#surfaceType}. */
            FORBID
        }

        /** True = cardinal + diagonal (8 dirs). False = cardinal only (4 dirs). */
        public boolean use8Directions = false;

        /** When no direct neighbor matches, expand the search ring-by-ring and pick a
         *  candidate from the nearest non-empty ring. Returns null only if the entire level
         *  holds no match. */
        public boolean cascade = false;

        /** What to do with the {@link Level#surface} grid when filtering candidate cells. */
        public SurfaceFilter surfaceFilter = SurfaceFilter.IGNORE;

        /** Paired with {@link #surfaceFilter}. {@code null} is treated as "no surface at all". */
        public Surface surfaceType;
    }

    private static final Random RANDOM = new Random();
    private static final int[][] DIRS_4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIRS_8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                                           {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int MAX_REACHABLE_ATTEMPTS = 24;

    private LevelUtilities() {}

    // ── neighbor sampling ───────────────────────────────────────────────────

    /**
     * Random floor-like tile adjacent to {@code origin} matching {@code options}. Cardinal
     * (4-way) by default; set {@link LevelUtilities.NeighborOptions#use8Directions} to include diagonals.
     * Surface filtering follows {@link LevelUtilities.NeighborOptions#surfaceFilter}. If no direct neighbor
     * matches and {@link LevelUtilities.NeighborOptions#cascade} is true, search widens ring-by-ring (BFS
     * through floor-like cells) until a matching cell is found; otherwise returns null.
     */
    public static Point getRandomNeighbor(Level level, Point origin, NeighborOptions options) {
        if (level == null || origin == null) return null;
        if (options == null) options = new NeighborOptions();
        int w = level.width, h = level.height;
        int[][] dirs = options.use8Directions ? DIRS_8 : DIRS_4;

        int ox = origin.tileX(), oy = origin.tileY();

        if (!options.cascade) {
            List<int[]> candidates = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = ox + d[0], ny = oy + d[1];
                if (!inBounds(level, nx, ny)) continue;
                if (!level.tiles[nx][ny].isFloorLike()) continue;
                if (!matchesSurfaceFilter(level, nx, ny, options)) continue;
                candidates.add(new int[]{nx, ny});
            }
            if (candidates.isEmpty()) return null;
            int[] pick = candidates.get(RANDOM.nextInt(candidates.size()));
            return new Point(pick[0], pick[1]);
        }

        // Cascading: walk outward ring-by-ring through floor-like cells. At each ring the
        // candidate set = cells in this ring that pass the surface filter. First non-empty
        // ring wins.
        boolean[][] visited = new boolean[w][h];
        ArrayDeque<int[]> frontier = new ArrayDeque<>();
        frontier.addLast(new int[]{ox, oy});
        visited[ox][oy] = true;
        List<int[]> candidates = new ArrayList<>();

        while (!frontier.isEmpty()) {
            int ringSize = frontier.size();
            candidates.clear();
            for (int i = 0; i < ringSize; i++) {
                int[] cur = frontier.pollFirst();
                for (int[] d : dirs) {
                    int nx = cur[0] + d[0], ny = cur[1] + d[1];
                    if (!inBounds(level, nx, ny)) continue;
                    if (visited[nx][ny]) continue;
                    visited[nx][ny] = true;
                    if (!level.tiles[nx][ny].isFloorLike()) continue;
                    boolean pass = matchesSurfaceFilter(level, nx, ny, options);
                    if (pass) {
                        candidates.add(new int[]{nx, ny});
                    } else {
                        // Traverse through non-matching cells so cascade can escape a
                        // saturated patch — same idea as SurfaceSystem's BFS.
                        frontier.addLast(new int[]{nx, ny});
                    }
                }
            }
            if (!candidates.isEmpty()) {
                int[] pick = candidates.get(RANDOM.nextInt(candidates.size()));
                return new Point(pick[0], pick[1]);
            }
        }
        return null;
    }

    // ── random reachable point ──────────────────────────────────────────────

    /**
     * Random floor-like tile that {@code mob} can reach via pathfinding. Sampling is best-
     * effort: up to {@value #MAX_REACHABLE_ATTEMPTS} random picks, each verified with a
     * single {@link Pathfinder#nextStep}. Returns null if no pick succeeded — rare, only
     * happens when the mob is trapped on a small disconnected island.
     */
    public static Point getRandomReachablePoint(Level level, Mob mob) {
        if (level == null || mob == null) return null;
        List<int[]> floors = new ArrayList<>();
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                if (level.tiles[x][y].isFloorLike()) floors.add(new int[]{x, y});
            }
        }
        if (floors.isEmpty()) return null;

        int mx = mob.position.tileX(), my = mob.position.tileY();
        for (int attempt = 0; attempt < MAX_REACHABLE_ATTEMPTS; attempt++) {
            int[] pick = floors.get(RANDOM.nextInt(floors.size()));
            if (pick[0] == mx && pick[1] == my) continue;
            Point p = new Point(pick[0], pick[1]);
            if (Pathfinder.nextStep(level, mob, p) != null) return p;
        }
        return null;
    }

    // ── line of sight ───────────────────────────────────────────────────────

    /**
     * True when {@code mob} can see {@code target} from its current position, limited by its
     * {@link Mob#visionRadius}. Uses the same shadowcaster and blocking-bitmap rules as the
     * player's FOV, so doors, walls, and mobs standing on closed doors match the visual
     * model. Cheap enough to call per-mob-per-turn; one full FOV scan per call.
     */
    public static boolean getLineOfSight(Level level, Mob mob, Point target) {
        if (level == null || mob == null || target == null) return false;
        int w = level.width, h = level.height;
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= w || ty >= h) return false;
        int cx = mob.position.tileX(), cy = mob.position.tileY();
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return false;

        boolean[] blocking = LevelSystem.buildBlocking(level, /*forLight=*/ false);
        boolean[] fov = new boolean[w * h];
        int radius = (int) Math.ceil(mob.effectiveStats().visionRadius);
        ShadowCaster.castShadow(cx, cy, w, fov, blocking, radius);
        return fov[ty * w + tx];
    }

    // ── adjacency predicates ────────────────────────────────────────────────

    /**
     * True if any cardinally-adjacent cell of {@code origin} carries the surface {@code s}.
     * Does <em>not</em> include {@code origin} itself — callers that also care about the
     * source tile should test it separately. Pass {@code null} to check for "no surface".
     */
    public static boolean getAdjacentTo(Level level, Point origin, Surface s) {
        if (level == null || origin == null) return false;
        int ox = origin.tileX(), oy = origin.tileY();
        for (int[] d : DIRS_4) {
            int nx = ox + d[0], ny = oy + d[1];
            if (!inBounds(level, nx, ny)) continue;
            if (level.surface[nx][ny] == s) return true;
        }
        return false;
    }

    /**
     * True if any cardinally-adjacent cell of {@code origin} carries the vegetation
     * {@code v}. Does not include {@code origin} itself. Pass {@code null} to check for
     * "no vegetation".
     */
    public static boolean getAdjacentTo(Level level, Point origin, Vegetation v) {
        if (level == null || origin == null) return false;
        int ox = origin.tileX(), oy = origin.tileY();
        for (int[] d : DIRS_4) {
            int nx = ox + d[0], ny = oy + d[1];
            if (!inBounds(level, nx, ny)) continue;
            if (level.vegetation[nx][ny] == v) return true;
        }
        return false;
    }

    /**
     * True if any cardinally-adjacent cell of {@code origin} is of tile type {@code t}.
     * Does not include {@code origin} itself.
     */
    public static boolean getAdjacentTo(Level level, Point origin, Tile t) {
        if (level == null || origin == null || t == null) return false;
        int ox = origin.tileX(), oy = origin.tileY();
        for (int[] d : DIRS_4) {
            int nx = ox + d[0], ny = oy + d[1];
            if (!inBounds(level, nx, ny)) continue;
            if (level.tiles[nx][ny] == t) return true;
        }
        return false;
    }

    // ── tile predicates ─────────────────────────────────────────────────────

    /**
     * Whether {@code mob} can physically enter a cell of tile type {@code tile}. This is
     * the tile-type check only — it does not consider other mobs occupying the cell or
     * doors held open by an occupant. Callers that need the full "can enter this cell"
     * check (mobs, doors, etc.) should use {@link MobSystem#blocksMovement}.
     *
     * <p>Rules: walls and lamps block everything; chasms block non-flying mobs; all other
     * tile types are passable.
     */
    public static boolean isTilePassable(Mob mob, Tile tile) {
        if (tile == null) return false;
        if (tile.blocksMovement()) return false;
        if (tile == Tile.CHASM) return mob != null && mob.effectiveStats().flying;
        return true;
    }

    /**
     * Whether light / line-of-sight can pass through a cell of tile type {@code tile}. This
     * is the tile-type check only — a closed door with a mob standing on it is actually
     * transparent in-game (handled in {@link LevelSystem#buildBlocking}), so callers that
     * need the full LOS answer should use {@link #getLineOfSight}.
     */
    public static boolean isTileTransparent(Mob mob, Tile tile) {
        if (tile == null) return false;
        // The mob parameter is kept for future per-species overrides (e.g. a ghost that
        // sees through walls). Today the rule is uniform: blocksSight tiles are opaque.
        return !tile.blocksSight();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean inBounds(Level level, int x, int y) {
        return x >= 0 && y >= 0 && x < level.width && y < level.height;
    }

    private static boolean matchesSurfaceFilter(Level level, int x, int y, NeighborOptions opts) {
        switch (opts.surfaceFilter) {
            case IGNORE:
                return true;
            case REQUIRE:
                return level.surface[x][y] == opts.surfaceType;
            case FORBID:
                return level.surface[x][y] != opts.surfaceType;
            default:
                return true;
        }
    }
}
