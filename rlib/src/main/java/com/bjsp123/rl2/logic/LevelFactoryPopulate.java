package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.LevelFlag;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stage-3 population pass: lays surfaces (water/blood pools), vegetation patches, items and
 * mobs onto a level whose tile grid is already finalised. All placement here is post-tile —
 * no method here reshapes terrain. Pool/patch growth is randomized flood-fill bounded by
 * size targets.
 *
 * <p>Mob and item selection is driven by per-row {@code minDepth} / {@code maxDepth} /
 * {@code theme} cells in the CSVs. The populator builds the eligible set once (rows whose
 * depth window includes {@code level.depth} and whose theme is null or matches
 * {@code level.theme}) and then picks uniformly at random from it.
 */
public final class LevelFactoryPopulate {

    private LevelFactoryPopulate() {}

    /** Items that pass the depth + theme gate and are not gems (gems are placed
     *  through {@link #placeGems}). */
    private static List<String> eligibleItems(Level level) {
        List<String> out = new ArrayList<>();
        for (String type : ItemRegistry.knownTypes()) {
            ItemDefinition def = ItemRegistry.get(type);
            if (def == null) continue;
            if (level.depth < def.minDepth || level.depth > def.maxDepth) continue;
            if (def.theme != null && def.theme != level.theme) continue;
            // Gems use their own theme-driven roll.
            if ("GEMS".equals(def.inventoryCategory)) continue;
            out.add(type);
        }
        return out;
    }

    /** Items flagged {@code guaranteedPerLevel} that also pass the depth / theme
     *  gate. One of each is placed on every eligible level. */
    private static List<String> guaranteedItems(Level level) {
        List<String> out = new ArrayList<>();
        for (String type : ItemRegistry.knownTypes()) {
            ItemDefinition def = ItemRegistry.get(type);
            if (def == null || !def.guaranteedPerLevel) continue;
            if (level.depth < def.minDepth || level.depth > def.maxDepth) continue;
            if (def.theme != null && def.theme != level.theme) continue;
            out.add(type);
        }
        return out;
    }

    /** Mobs that pass the depth + theme gate. Player-class rows
     *  ({@code behavior == PLAYER}) are excluded — they're kit templates, not
     *  encounters. */
    private static List<String> eligibleMobs(Level level) {
        List<String> out = new ArrayList<>();
        for (String type : MobRegistry.knownTypes()) {
            MobDefinition def = MobRegistry.get(type);
            if (def == null) continue;
            if (def.behavior == Mob.Behavior.PLAYER) continue;
            if (level.depth < def.minDepth || level.depth > def.maxDepth) continue;
            if (def.theme != null && def.theme != level.theme) continue;
            out.add(type);
        }
        return out;
    }

    // ── Surfaces ────────────────────────────────────────────────────────────

    /** Scatter water pools across floor tiles. {@link LevelFlag#WATER} bumps the count. */
    public static void placeWaterPools(Level level, Random rng) {
        int poolCount = level.flags.contains(LevelFlag.WATER)
                ? 6 + rng.nextInt(6)      // 6..11
                : rng.nextInt(4);         // 0..3
        for (int i = 0; i < poolCount; i++) {
            Point seed = LevelFactoryUtils.randomFloorTile(level, rng);
            if (seed == null) return;
            growSurface(level, seed, 3 + rng.nextInt(10), Surface.WATER, rng);
        }
    }

    /**
     * Randomized flood-fill that paints {@code surface} onto reachable FLOOR tiles starting
     * from {@code seed}, expanding via cardinal neighbours each at {@code SPREAD_CHANCE}.
     * Stops once {@code target} tiles are reached or the frontier is exhausted.
     */
    private static void growSurface(Level level, Point seed, int target, Surface surface,
                                    Random rng) {
        final double spreadChance = 0.6;
        boolean[][] taken = new boolean[level.width][level.height];
        List<Point> frontier = new ArrayList<>();
        taken[seed.tileX()][seed.tileY()] = true;
        frontier.add(seed);
        int count = 1;
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        while (!frontier.isEmpty() && count < target) {
            Point cur = frontier.remove(rng.nextInt(frontier.size()));
            for (int[] d : dirs) {
                if (rng.nextDouble() > spreadChance) continue;
                int nx = cur.tileX() + d[0], ny = cur.tileY() + d[1];
                if (nx <= 0 || ny <= 0 || nx >= level.width - 1 || ny >= level.height - 1) continue;
                if (taken[nx][ny]) continue;
                if (level.tiles[nx][ny] != Tile.FLOOR) continue;
                taken[nx][ny] = true;
                frontier.add(new Point(nx, ny));
                count++;
                if (count >= target) break;
            }
        }
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (taken[x][y] && level.surface[x][y] == null) level.surface[x][y] = surface;
    }

    // ── Vegetation ──────────────────────────────────────────────────────────

    /** Grass + mushroom patches on dry floor. {@link LevelFlag#PLANTS} bumps the count. */
    public static void placeVegetation(Level level, Random rng) {
        int patches = level.flags.contains(LevelFlag.PLANTS)
                ? 5 + rng.nextInt(6)      // 5..10
                : 1 + rng.nextInt(3);     // 1..3
        for (int i = 0; i < patches; i++) {
            Point seed = LevelFactoryUtils.randomFloorTile(level, rng);
            if (seed == null) return;
            Vegetation kind = rng.nextBoolean() ? Vegetation.GRASS : Vegetation.MUSHROOMS;
            growVegetation(level, seed, 2 + rng.nextInt(6), kind, rng);
        }
    }

    private static void growVegetation(Level level, Point seed, int target, Vegetation kind,
                                       Random rng) {
        final double spreadChance = 0.45;
        boolean[][] taken = new boolean[level.width][level.height];
        List<Point> frontier = new ArrayList<>();
        taken[seed.tileX()][seed.tileY()] = true;
        frontier.add(seed);
        int count = 1;
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        while (!frontier.isEmpty() && count < target) {
            Point cur = frontier.remove(rng.nextInt(frontier.size()));
            for (int[] d : dirs) {
                if (rng.nextDouble() > spreadChance) continue;
                int nx = cur.tileX() + d[0], ny = cur.tileY() + d[1];
                if (nx <= 0 || ny <= 0 || nx >= level.width - 1 || ny >= level.height - 1) continue;
                if (taken[nx][ny]) continue;
                if (level.tiles[nx][ny] != Tile.FLOOR) continue;
                if (level.surface[nx][ny] != null) continue; // dry tiles only
                taken[nx][ny] = true;
                frontier.add(new Point(nx, ny));
                count++;
                if (count >= target) break;
            }
        }
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (taken[x][y] && level.vegetation[x][y] == null) level.vegetation[x][y] = kind;
    }

    // ── Items ───────────────────────────────────────────────────────────────

    /** One copy of every {@code guaranteedPerLevel} eligible item plus 1–3 random
     *  picks from the depth/theme-filtered pool. Items prefer enclosed interiors
     *  via {@link LevelFactoryUtils#randomInnerFloorTile}. */
    public static void placeItems(Level level, Random rng) {
        for (String type : guaranteedItems(level)) {
            Point spot = LevelFactoryUtils.randomInnerFloorTile(level, rng, INNER_TILE_SAMPLES);
            if (spot == null) break;
            Item it = ItemFactory.build(type);
            it.location = spot;
            assignItemLevel(it, level.depth, rng);
            level.items.add(it);
        }
        List<String> pool = eligibleItems(level);
        if (pool.isEmpty()) return;
        int count = 1 + rng.nextInt(3);
        for (int i = 0; i < count; i++) {
            Point p = LevelFactoryUtils.randomInnerFloorTile(level, rng, INNER_TILE_SAMPLES);
            if (p == null) return;
            Item it = ItemFactory.build(pool.get(rng.nextInt(pool.size())));
            it.location = p;
            assignItemLevel(it, level.depth, rng);
            level.items.add(it);
        }
    }

    /** Number of candidates {@link LevelFactoryUtils#randomInnerFloorTile} samples per
     *  item placement. Higher = stronger interior bias; 8 reliably picks an enclosed
     *  cell when one exists without making placement deterministic. */
    private static final int INNER_TILE_SAMPLES = 8;

    /** Assign a random spawn level to a dungeon-dropped item: {@code [0, depth]} for
     *  most items, but food is always level 0 (the food field gates on the
     *  {@code foodValue}-bearing item types per the user spec). Gems carry their own
     *  size on {@code gemSize}; the depth-scaling level field doesn't apply. */
    private static void assignItemLevel(Item it, int depth, Random rng) {
        if (it == null) return;
        if (it.isGem())       { it.level = 0; return; }
        if (it.foodValue > 0) { it.level = 0; return; }
        it.level = rng.nextInt(Math.max(1, depth + 1));
    }

    /** Scatter 4–6 size-1 ("tiny") gems on themed levels. Each level rolls species per gem
     *  via {@link GemSystem#rollSpeciesForTheme}; themes with no gem species defined
     *  produce no gems. */
    public static void placeGems(Level level, Random rng) {
        com.bjsp123.rl2.model.GemSpecies probe =
                GemSystem.rollSpeciesForTheme(level.theme, rng);
        if (probe == null) return;
        int count = GemSystem.MIN_GEMS_PER_LEVEL
                + rng.nextInt(GemSystem.MAX_GEMS_PER_LEVEL - GemSystem.MIN_GEMS_PER_LEVEL + 1);
        for (int i = 0; i < count; i++) {
            Point p = LevelFactoryUtils.randomFloorTile(level, rng);
            if (p == null) return;
            com.bjsp123.rl2.model.GemSpecies sp =
                    GemSystem.rollSpeciesForTheme(level.theme, rng);
            if (sp == null) return;
            Item gem = GemSystem.createGem(sp);
            gem.location = p;
            level.items.add(gem);
        }
    }

    // ── Mobs ────────────────────────────────────────────────────────────────

    /** 2–5 random mobs picked uniformly from the depth/theme-filtered pool, plus
     *  any kittens triggered by adult cats (see {@link #placeKittens}). */
    public static void placeMobs(Level level, Random rng) {
        List<String> pool = eligibleMobs(level);
        int spawnLevel = 1 + level.depth;
        if (!pool.isEmpty()) {
            int count = 2 + rng.nextInt(4);
            for (int i = 0; i < count; i++) {
                Point p = LevelFactoryUtils.randomFloorTile(level, rng);
                if (p == null) break;
                if (mobAt(level, p.tileX(), p.tileY())) continue;
                Mob m = MobFactory.spawn(pool.get(rng.nextInt(pool.size())), p);
                if (m == null) continue;
                // Every dungeon-spawned mob starts at character-level 1 + depth so combat
                // stats scale alongside the player's progression.
                MobProgression.setSpawnLevel(m, spawnLevel);
                level.mobs.add(m);
            }
        }
        placeKittens(level, rng);
    }

    /**
     * For every mob already placed whose CSV row carries a non-null {@code kittenType},
     * roll a 1/3 chance of giving it a litter of 1–2 kittens of that type. Successful
     * rolls drop the kittens onto random unoccupied floor tiles inside the parent's
     * room (BFS bounded by walls + doors so we don't cross into the next chamber);
     * each kitten's {@link Mob#owner} is wired to the parent so its FOLLOWING-state AI
     * shadows the right leader.
     */
    private static void placeKittens(Level level, Random rng) {
        List<Mob> parents = new ArrayList<>();
        for (Mob m : level.mobs) {
            if (m.kittenType != null) parents.add(m);
        }
        for (Mob parent : parents) {
            if (rng.nextDouble() >= 1.0 / 3.0) continue;   // most cats are childless
            List<Point> roomFloors = sameRoomFloorTiles(level, parent.position);
            if (roomFloors.isEmpty()) continue;
            int count = 1 + rng.nextInt(2);
            for (int i = 0; i < count; i++) {
                // Re-roll a free tile each kitten to avoid collisions with siblings.
                Point spot = pickFreeTile(level, roomFloors, rng);
                if (spot == null) break;
                Mob kitten = MobFactory.spawn(parent.kittenType, spot);
                if (kitten == null) break;
                kitten.owner = parent;
                level.mobs.add(kitten);
            }
        }
    }

    /** BFS the floor-only region containing {@code origin}, stopping at walls, doors,
     *  and chasms — i.e. the room {@code origin} sits in. Returns every FLOOR /
     *  FLOOR_WOOD cell reached. */
    private static List<Point> sameRoomFloorTiles(Level level, Point origin) {
        List<Point> out = new ArrayList<>();
        int w = level.width, h = level.height;
        boolean[][] seen = new boolean[w][h];
        java.util.Deque<Point> q = new java.util.ArrayDeque<>();
        q.add(origin);
        seen[origin.tileX()][origin.tileY()] = true;
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        while (!q.isEmpty()) {
            Point p = q.poll();
            Tile t = level.tiles[p.tileX()][p.tileY()];
            if (t == Tile.FLOOR || t == com.bjsp123.rl2.model.Tile.FLOOR_WOOD) out.add(p);
            for (int[] d : dirs) {
                int nx = p.tileX() + d[0], ny = p.tileY() + d[1];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                if (seen[nx][ny]) continue;
                Tile nt = level.tiles[nx][ny];
                if (nt == Tile.WALL || nt == com.bjsp123.rl2.model.Tile.DOOR
                        || nt == com.bjsp123.rl2.model.Tile.DOOR_OPEN
                        || nt == Tile.CHASM) continue;
                seen[nx][ny] = true;
                q.add(new Point(nx, ny));
            }
        }
        return out;
    }

    /** Random unoccupied tile from {@code candidates}, or null if every option already
     *  has a mob standing on it. Up to 8 sample tries before giving up — cheap and avoids
     *  scanning the entire candidate list when the room is dense. */
    private static Point pickFreeTile(Level level, List<Point> candidates, Random rng) {
        if (candidates.isEmpty()) return null;
        for (int tries = 0; tries < 8; tries++) {
            Point p = candidates.get(rng.nextInt(candidates.size()));
            if (!mobAt(level, p.tileX(), p.tileY())) return p;
        }
        return null;
    }

    private static boolean mobAt(Level level, int x, int y) {
        for (Mob m : level.mobs)
            if (m.position.tileX() == x && m.position.tileY() == y) return true;
        return false;
    }
}
