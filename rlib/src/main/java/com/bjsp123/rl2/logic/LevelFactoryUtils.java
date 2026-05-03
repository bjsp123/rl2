package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generation-time geometry and topology helpers used by {@link LevelFactory}, the room
 * painters, and the populate stage. Stateless — every method takes the {@link Level},
 * {@link Random}, and any inputs explicitly. Three families of helpers live here:
 *
 * <ul>
 *   <li><b>Corridor routing.</b> {@link #carveCorridorL}, {@link #carveCorridorDiagonal},
 *       {@link #carveCorridorRough} — turn a "from → to" pair into floor tiles.</li>
 *   <li><b>Room-centre distributions.</b> {@link #poissonDiskPoints},
 *       {@link #bspPartition} — produce sets of well-spaced rectangles or points within a
 *       bounding rect.</li>
 *   <li><b>Open-space generators.</b> {@link #cellularCave}, {@link #diffusionCave} —
 *       blob-shaped natural caves.</li>
 * </ul>
 *
 * Plus a few cleanup primitives ({@link #wallInFloors}, {@link #pruneOrphanDoors},
 * {@link #carveRect}) shared across layouts.
 */
public final class LevelFactoryUtils {

    private LevelFactoryUtils() {}

    // ── Tile primitives ─────────────────────────────────────────────────────

    /** Carve a rectangle of FLOOR. Bounds-checked. */
    public static void carveRect(Level level, int x, int y, int w, int h) {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int tx = x + i, ty = y + j;
                if (inBounds(level, tx, ty)) level.tiles[tx][ty] = Tile.FLOOR;
            }
        }
    }

    /** Set a single tile if in bounds. */
    public static void setTile(Level level, int x, int y, Tile t) {
        if (inBounds(level, x, y)) level.tiles[x][y] = t;
    }

    public static boolean inBounds(Level level, int x, int y) {
        return x > 0 && y > 0 && x < level.width - 1 && y < level.height - 1;
    }

    /** True iff any of the 4 cardinal neighbours of {@code (x, y)} is a door (open or
     *  closed). Used by stair / lamp / statue placement to keep "ornament" tiles from
     *  jamming up against a doorway, where they read poorly and can block traversal. */
    public static boolean adjacentToDoor(Level level, int x, int y) {
        return isDoor(level, x, y - 1)
            || isDoor(level, x, y + 1)
            || isDoor(level, x - 1, y)
            || isDoor(level, x + 1, y);
    }

    private static boolean isDoor(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        return t == Tile.DOOR || t == Tile.DOOR_OPEN;
    }

    // ── Corridor routing ────────────────────────────────────────────────────

    /**
     * L-shape corridor. Carves a 1-wide path of {@code tile} from {@code from} to {@code to}
     * that turns once: either horizontal-then-vertical or vertical-then-horizontal (50/50
     * chance). Both endpoints inclusive. Idempotent — overlapping carves are harmless. */
    public static void carveCorridorL(Level level, Point from, Point to, Tile tile, Random rng) {
        int x1 = from.tileX(), y1 = from.tileY();
        int x2 = to.tileX(),   y2 = to.tileY();
        if (rng.nextBoolean()) {
            carveHLine(level, x1, x2, y1, tile);
            carveVLine(level, y1, y2, x2, tile);
        } else {
            carveVLine(level, y1, y2, x1, tile);
            carveHLine(level, x1, x2, y2, tile);
        }
    }

    /** Diagonal Bresenham corridor — chunky 1-wide line of {@code tile} from A to B with
     *  staircase-style steps. Produces shorter total distance than an L corridor. */
    public static void carveCorridorDiagonal(Level level, Point from, Point to, Tile tile) {
        int x0 = from.tileX(), y0 = from.tileY();
        int x1 = to.tileX(),   y1 = to.tileY();
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            setTile(level, x, y, tile);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    /** Rough corridor — random walk of {@code tile} that drifts toward the goal. Each step
     *  picks the axis with the larger remaining distance and steps along it 80% of the
     *  time, otherwise steps perpendicular. Produces a wandering path that looks natural
     *  rather than surgical. Capped at {@code w*h} steps as a safety. */
    public static void carveCorridorRough(Level level, Point from, Point to, Tile tile, Random rng) {
        int x = from.tileX(), y = from.tileY();
        int gx = to.tileX(),  gy = to.tileY();
        int budget = level.width * level.height;
        while ((x != gx || y != gy) && budget-- > 0) {
            setTile(level, x, y, tile);
            int rx = gx - x, ry = gy - y;
            boolean moveX;
            if (rx == 0)      moveX = false;
            else if (ry == 0) moveX = true;
            else              moveX = (Math.abs(rx) >= Math.abs(ry)) ^ (rng.nextDouble() < 0.2);
            if (moveX) x += Integer.signum(rx);
            else       y += Integer.signum(ry);
        }
        setTile(level, gx, gy, tile);
    }

    private static void carveHLine(Level level, int x1, int x2, int y, Tile tile) {
        int lo = Math.min(x1, x2), hi = Math.max(x1, x2);
        for (int x = lo; x <= hi; x++) setTile(level, x, y, tile);
    }

    private static void carveVLine(Level level, int y1, int y2, int x, Tile tile) {
        int lo = Math.min(y1, y2), hi = Math.max(y1, y2);
        for (int y = lo; y <= hi; y++) setTile(level, x, y, tile);
    }

    // ── Room-centre distributions ──────────────────────────────────────────

    /**
     * Poisson-disc sample of points inside the rect (1, 1, w-2, h-2), enforcing a minimum
     * Chebyshev distance between any two picks. Bridson-style: each accepted point seeds
     * up to {@code k} candidates in an annulus around it; the first one farther than
     * {@code minDist} from every existing point is accepted. Returns the accepted set.
     * Good for "scatter rooms across the level" with predictable spacing.
     */
    public static List<Point> poissonDiskPoints(int w, int h, double minDist, Random rng) {
        final int K = 30;                           // candidate samples per active point
        List<Point> out = new ArrayList<>();
        ArrayList<Point> active = new ArrayList<>();
        Point seed = new Point(1 + rng.nextInt(Math.max(1, w - 2)),
                               1 + rng.nextInt(Math.max(1, h - 2)));
        out.add(seed);
        active.add(seed);
        while (!active.isEmpty()) {
            int idx = rng.nextInt(active.size());
            Point centre = active.get(idx);
            boolean accepted = false;
            for (int i = 0; i < K; i++) {
                double angle = rng.nextDouble() * 2 * Math.PI;
                double radius = minDist + rng.nextDouble() * minDist;
                int px = (int) Math.round(centre.tileX() + Math.cos(angle) * radius);
                int py = (int) Math.round(centre.tileY() + Math.sin(angle) * radius);
                if (px < 1 || py < 1 || px > w - 2 || py > h - 2) continue;
                boolean tooClose = false;
                for (Point p : out) {
                    int dx = p.tileX() - px, dy = p.tileY() - py;
                    if (dx * dx + dy * dy < minDist * minDist) { tooClose = true; break; }
                }
                if (!tooClose) {
                    Point np = new Point(px, py);
                    out.add(np);
                    active.add(np);
                    accepted = true;
                    break;
                }
            }
            if (!accepted) active.remove(idx);
        }
        return out;
    }

    /**
     * Recursive binary space partition of {@code (x, y, w, h)}. Each leaf rect satisfies
     * {@code w >= minSize && h >= minSize}; intermediate splits stop when neither child
     * would meet the minimum. Returns the leaf rectangles as {@code int[]{x, y, w, h}}.
     * Splits are 30%–70% along the longer axis, with random axis-flip when w ≈ h.
     */
    public static List<int[]> bspPartition(int x, int y, int w, int h, int minSize, Random rng) {
        List<int[]> leaves = new ArrayList<>();
        bspSplit(leaves, x, y, w, h, minSize, rng, 0);
        return leaves;
    }

    private static void bspSplit(List<int[]> out, int x, int y, int w, int h,
                                 int minSize, Random rng, int depth) {
        boolean canSplitH = w >= minSize * 2 + 1;
        boolean canSplitV = h >= minSize * 2 + 1;
        // Stop when neither dimension can fit two children, or when we're getting too deep.
        if (!canSplitH && !canSplitV || depth > 6) {
            out.add(new int[]{x, y, w, h});
            return;
        }
        boolean splitH;
        if (!canSplitH)      splitH = false;
        else if (!canSplitV) splitH = true;
        else                 splitH = (w > h)
                                   || (w == h && rng.nextBoolean());
        if (splitH) {
            int min = minSize, max = w - minSize - 1;
            int sx = min + rng.nextInt(max - min + 1);
            bspSplit(out, x,        y, sx,         h, minSize, rng, depth + 1);
            bspSplit(out, x + sx + 1, y, w - sx - 1, h, minSize, rng, depth + 1);
        } else {
            int min = minSize, max = h - minSize - 1;
            int sy = min + rng.nextInt(max - min + 1);
            bspSplit(out, x, y,        w, sy,         minSize, rng, depth + 1);
            bspSplit(out, x, y + sy + 1, w, h - sy - 1, minSize, rng, depth + 1);
        }
    }

    // ── Open-space (cave) generators ────────────────────────────────────────

    /**
     * Cellular-automata cave generator. Seeds an {@code (w, h)} grid with random cells
     * filled at {@code initFill} probability, then runs {@code iterations} smoothing
     * passes (B5678/S45678 — a cell becomes solid if it has ≥5 solid neighbours, stays
     * solid if it has ≥4). Returns a boolean grid where {@code true} = floor.
     */
    public static boolean[][] cellularCave(int w, int h, double initFill, int iterations,
                                           Random rng) {
        boolean[][] cur = new boolean[w][h];
        boolean[][] nxt = new boolean[w][h];
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                cur[x][y] = (x > 0 && y > 0 && x < w - 1 && y < h - 1)
                            && rng.nextDouble() < initFill;
        for (int it = 0; it < iterations; it++) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int n = countNeighbours(cur, x, y, w, h);
                    boolean solidNow = !cur[x][y];
                    boolean solidNext;
                    if (solidNow) solidNext = n >= 4;
                    else          solidNext = n >= 5;
                    nxt[x][y] = !solidNext;
                }
            }
            boolean[][] tmp = cur; cur = nxt; nxt = tmp;
        }
        return cur;
    }

    private static int countNeighbours(boolean[][] g, int x, int y, int w, int h) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) { n++; continue; }
                if (!g[nx][ny]) n++;
            }
        }
        return n;
    }

    /**
     * Diffusion-limited random walker. Starts at the centre, takes {@code steps} random
     * 8-direction steps, marking each visited cell as floor. Cheap and produces blobby,
     * organic shapes — good for small caves or "rough room" interiors.
     */
    public static boolean[][] diffusionCave(int w, int h, int steps, Random rng) {
        boolean[][] g = new boolean[w][h];
        int x = w / 2, y = h / 2;
        for (int i = 0; i < steps; i++) {
            g[x][y] = true;
            int dx = rng.nextInt(3) - 1, dy = rng.nextInt(3) - 1;
            int nx = x + dx, ny = y + dy;
            if (nx > 0 && ny > 0 && nx < w - 1 && ny < h - 1) { x = nx; y = ny; }
        }
        return g;
    }

    // ── Cleanup passes ──────────────────────────────────────────────────────

    /** Wall in any CHASM tile that touches a FLOOR cell. Run this after carving rooms and
     *  corridors so the boundary of every FLOOR area becomes a sealed wall. FLOOR_WOOD is
     *  intentionally NOT a trigger — that's how {@link com.bjsp123.rl2.model.Level.LevelFlag#WALKWAY_LEVEL}
     *  corridors stay as plank bridges with chasm to either side. */
    public static void wallInFloors(Level level) {
        int w = level.width, h = level.height;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (level.tiles[x][y] != Tile.CHASM) continue;
                if (touchesFloor(level, new Point(x, y))) level.tiles[x][y] = Tile.WALL;
            }
        }
    }

    /** {@link #wallInFloors} helper: 8-neighbour FLOOR check. Not used elsewhere. */
    private static boolean touchesFloor(Level level, Point p) {
        int x = p.tileX(), y = p.tileY();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny] == Tile.FLOOR) return true;
            }
        }
        return false;
    }

    /** Final-pass cleanup: a door that doesn't have walls flanking it on a single axis is
     *  not a real door — convert to floor. Specifically the door must have walls both N
     *  and S, OR walls both E and W. Otherwise it's an opening on a corner / open area
     *  and reads better as floor. */
    public static void pruneOrphanDoors(Level level) {
        Tile[][] t = level.tiles;
        int w = level.width, h = level.height;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (t[x][y] != Tile.DOOR) continue;
                boolean nsWalled = y + 1 < h && y - 1 >= 0
                        && t[x][y + 1] == Tile.WALL && t[x][y - 1] == Tile.WALL;
                boolean ewWalled = x + 1 < w && x - 1 >= 0
                        && t[x + 1][y] == Tile.WALL && t[x - 1][y] == Tile.WALL;
                if (!nsWalled && !ewWalled) t[x][y] = Tile.FLOOR;
            }
        }
    }

    /** Pick a random FLOOR tile uniformly. Returns null if there are none. */
    public static Point randomFloorTile(Level level, Random rng) {
        List<Point> candidates = new ArrayList<>();
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (level.tiles[x][y] == Tile.FLOOR) candidates.add(new Point(x, y));
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    /** Pick a random FLOOR tile, weighted toward enclosed interiors (small rooms,
     *  village buildings, subrooms). Samples {@code k} uniform-random floor candidates
     *  and returns the one with the most walls in its 5×5 neighbourhood — a cheap
     *  heuristic that biases against open corridors and large open green spaces. */
    public static Point randomInnerFloorTile(Level level, Random rng, int k) {
        Point best = null;
        int bestScore = -1;
        for (int i = 0; i < Math.max(1, k); i++) {
            Point p = randomFloorTile(level, rng);
            if (p == null) return null;
            int score = wallsNear(level, p.tileX(), p.tileY(), 2);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private static int wallsNear(Level level, int cx, int cy, int radius) {
        int n = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                Tile t = level.tiles[x][y];
                if (t == Tile.WALL || t == Tile.DOOR || t == Tile.DOOR_OPEN) n++;
            }
        }
        return n;
    }

    /** Chebyshev (8-direction) distance between two tiles. */
    public static int chebyshev(Point a, Point b) {
        return Math.max(Math.abs(a.tileX() - b.tileX()), Math.abs(a.tileY() - b.tileY()));
    }

    /** Squared Euclidean distance. */
    public static int distSq(Point a, Point b) {
        int dx = a.tileX() - b.tileX();
        int dy = a.tileY() - b.tileY();
        return dx * dx + dy * dy;
    }
}
