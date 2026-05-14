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
 * mobs onto a level whose tile grid is already finalised. All placement here is post-tile -
 * no method here reshapes terrain. Pool/patch growth is randomized flood-fill bounded by
 * size targets.
 *
 * <p>Mob and item selection is driven by per-row {@code powerLevel} / {@code theme} cells
 * in the CSVs. {@code powerLevel} is a fraction-of-depth range (0 = surface, 1 = bottom)
 * stamped onto each row as {@code powerMin} / {@code powerMax}. A row is eligible on a
 * level whose depth-fraction falls inside that range; weight peaks at the range's midpoint
 * and falls linearly to 0 at the edges, so {@code 0.3_0.7} is most common at mid-dungeon
 * and {@code 0.0_1.0} appears uniformly across every depth. Cluster size + retainer
 * columns drive group / entourage spawns.
 */
public final class LevelFactoryPopulate {

    private LevelFactoryPopulate() {}

    /** Depth-fraction of {@code level.depth} within the configured dungeon size:
     *  depth 1 -> 0.0, depth {@code DUNGEON_DEPTH} -> 1.0. Caps below + above so callers
     *  on side-branch / preview levels with out-of-range depths still get a sane number.
     *  Public so {@link ItemGenerator} callers (loot tables, themed-room drops, etc.)
     *  can convert a level to the {@code powerLevel} input the generator expects. */
    public static double depthFraction(Level level) {
        int total = Math.max(2, GameBalance.DUNGEON_DEPTH);
        double f = (level.depth - 1) / (double) (total - 1);
        if (f < 0) return 0;
        if (f > 1) return 1;
        return f;
    }

    /** Triangle weight over the {@code [powerMin, powerMax]} band: returns 0
     *  outside the band, peaks at the midpoint, and tapers toward each edge.
     *  Floored at {@link #POWER_EDGE_WEIGHT} so even at the very edges a mob
     *  retains a small but non-zero spawn chance - without the floor, mobs
     *  whose band starts at 0 (or ends at 1) would never spawn on the
     *  shallowest (or deepest) levels because their weight there would be
     *  exactly 0 and they'd be dropped from the pool. */
    private static double powerWeight(double powerMin, double powerMax, double levelF) {
        if (levelF < powerMin || levelF > powerMax) return 0.0;
        double half = (powerMax - powerMin) * 0.5;
        if (half <= 0) return 1.0;
        double mid = (powerMin + powerMax) * 0.5;
        double w = 1.0 - Math.abs(mid - levelF) / half;
        return Math.max(POWER_EDGE_WEIGHT, w);
    }

    /** Floor for {@link #powerWeight} inside the band - ensures edge mobs keep
     *  a positive spawn chance. */
    private static final double POWER_EDGE_WEIGHT = 0.05;

    /** Weight multiplier for a definition's theme relative to the level's theme.
     *  Null theme = theme-neutral (1x); matching = twice as likely (2x);
     *  mismatching = half as likely (0.5x). */
    private static double themeMultiplier(Level.VisualTheme defTheme,
                                          Level.VisualTheme levelTheme) {
        if (defTheme == null) return 1.0;
        return defTheme == levelTheme ? 2.0 : 0.5;
    }

    /** Pick a key from {@code keys} weighted by the parallel {@code weights} array.
     *  All-zero weights -> null. */
    static String pickWeighted(List<String> keys, double[] weights, Random rng) {
        double total = 0;
        for (double w : weights) total += w;
        if (total <= 0 || keys.isEmpty()) return null;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r <= 0) return keys.get(i);
        }
        return keys.get(keys.size() - 1);
    }

    /** Items whose {@code guaranteedPerLevel} count is positive and that
     *  also pass the powerLevel + theme gate. The output list contains
     *  {@code count} copies of each eligible type so the caller can
     *  iterate it directly to place every guaranteed copy. */
    private static List<String> guaranteedItems(Level level) {
        List<String> out = new ArrayList<>();
        double f = depthFraction(level);
        for (String type : Registries.itemTypes()) {
            ItemDefinition def = Registries.item(type);
            if (def == null || def.guaranteedPerLevel <= 0) continue;
            if (def.theme != null && def.theme != level.theme) continue;
            if (powerWeight(def.powerMin, def.powerMax, f) <= 0) continue;
            for (int i = 0; i < def.guaranteedPerLevel; i++) out.add(type);
        }
        return out;
    }

    /** Mob types eligible for random spawn on {@code level}, with parallel weights.
     *  Player-class rows + unique-flagged species are excluded - players are kit
     *  templates, and unique mobs go through their own one-shot pass below.
     *  Rows whose {@code powerLevel} band is {@code 0_0} are also excluded - the
     *  zero-zero band is the convention for "summon / retainer only" mobs (kobold
     *  spearmen, kittens, etc.) that should never appear via random scatter. */
    private static List<String> eligibleMobs(Level level, double[][] weightOut) {
        List<String> keys = new ArrayList<>();
        List<Double>  ws  = new ArrayList<>();
        double f = depthFraction(level);
        for (String type : Registries.mobTypes()) {
            MobDefinition def = Registries.mob(type);
            if (def == null) continue;
            if (def.behavior == Mob.Behavior.PLAYER) continue;
            if (def.unique) continue;
            if (def.powerMin == 0 && def.powerMax == 0) continue;
            double w = powerWeight(def.powerMin, def.powerMax, f);
            if (w <= 0) continue;
            w *= themeMultiplier(def.theme, level.theme);
            keys.add(type);
            ws.add(w);
        }
        weightOut[0] = new double[ws.size()];
        for (int i = 0; i < ws.size(); i++) weightOut[0][i] = ws.get(i);
        return keys;
    }

    /** Unique mob types whose powerLevel band covers {@code level}'s depth-fraction
     *  and that haven't already spawned somewhere in this run. The unique pass
     *  rolls each candidate independently rather than weighted-picking from a pool. */
    private static List<String> eligibleUniqueMobs(Level level,
                                                   com.bjsp123.rl2.model.UniqueTracker unique) {
        List<String> out = new ArrayList<>();
        if (unique == null) return out;
        double f = depthFraction(level);
        for (String type : Registries.mobTypes()) {
            MobDefinition def = Registries.mob(type);
            if (def == null || !def.unique) continue;
            if (def.behavior == Mob.Behavior.PLAYER) continue;
            if (unique.mobs.contains(type)) continue;
            if (def.powerMin == 0 && def.powerMax == 0) continue;
            if (powerWeight(def.powerMin, def.powerMax, f) <= 0) continue;
            out.add(type);
        }
        return out;
    }

    // -- Surfaces ------------------------------------------------------------

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

    // -- Vegetation ----------------------------------------------------------

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

    // -- Items ---------------------------------------------------------------

    /** One copy of every {@code guaranteedPerLevel} eligible item plus 1-3 random
     *  cluster placements drawn via {@link ItemGenerator}. Each pick rolls
     *  the item's {@code clusterSize} for how many copies sit on adjacent floor
     *  tiles. Items prefer enclosed interiors via
     *  {@link LevelFactoryUtils#randomInnerFloorTile}. */
    public static void placeItems(Level level, Random rng) {
        double powerLevel = depthFraction(level);
        for (String type : guaranteedItems(level)) {
            Point spot = nonReservedInnerTile(level, rng);
            if (spot == null) break;
            placeItem(level, ItemGenerator.buildItem(type, powerLevel), spot);
        }
        int clusters = GameBalance.RANDOM_ITEMS_PER_LEVEL + rng.nextInt(3);
        for (int i = 0; i < clusters; i++) {
            Item template = ItemGenerator.generateItem(powerLevel, level.theme,
                    ItemGenerator.LootCategory.NON_GEM, rng);
            if (template == null) break;
            Point seed = nonReservedInnerTile(level, rng);
            if (seed == null) return;
            ItemDefinition def = Registries.item(template.type);
            int n = rollMinMax(def == null ? null : def.clusterSize, rng);
            List<Point> spots = adjacentFloorTiles(level, seed, n);
            // First placement uses the freshly-rolled template; subsequent
            // cluster copies are independent objects of the same type at the
            // same plusses (so picking one up doesn't pick up the cluster).
            boolean placedTemplate = false;
            for (Point p : spots) {
                if (level.isReserved(p.tileX(), p.tileY())) continue;
                Item it;
                if (!placedTemplate) {
                    it = template;
                    placedTemplate = true;
                } else {
                    it = ItemGenerator.buildItem(template.type, powerLevel, rng);
                }
                placeItem(level, it, p);
            }
        }
    }

    /** Sample an enclosed floor tile via {@link LevelFactoryUtils#randomInnerFloorTile},
     *  retrying until we land on a non-reserved tile (or give up after 8 tries). Reserved
     *  tiles are inside themed-room rectangles and shouldn't host random scatter. */
    private static Point nonReservedInnerTile(Level level, Random rng) {
        for (int tries = 0; tries < 8; tries++) {
            Point p = LevelFactoryUtils.randomInnerFloorTile(level, rng, INNER_TILE_SAMPLES);
            if (p == null) return null;
            if (!level.isReserved(p.tileX(), p.tileY())) return p;
        }
        return null;
    }

    /** Drop an already-built item onto a tile and append to {@code level.items}.
     *  Item construction (type pick, plusses) is the {@link ItemGenerator}'s
     *  job; this method is the placement-only side of the pipeline. */
    static void placeItem(Level level, Item item, Point spot) {
        if (item == null || spot == null || level == null) return;
        item.location = spot;
        level.items.add(item);
    }

    /** Number of candidates {@link LevelFactoryUtils#randomInnerFloorTile} samples per
     *  item placement. Higher = stronger interior bias; 8 reliably picks an enclosed
     *  cell when one exists without making placement deterministic. */
    private static final int INNER_TILE_SAMPLES = 8;

    /** Scatter 4-6 size-1 ("tiny") gems on themed levels. Each gem is rolled via
     *  {@link ItemGenerator} with {@code GEM} category; themes with no gem
     *  species defined produce no gems (the generator returns {@code null} and
     *  the loop bails). */
    public static void placeGems(Level level, Random rng) {
        double powerLevel = depthFraction(level);
        // First gem also acts as the "is this theme gem-eligible?" probe - a
        // null return means the theme has no gem table so no gems on this level.
        Item firstGem = ItemGenerator.generateItem(powerLevel, level.theme,
                ItemGenerator.LootCategory.GEM, rng);
        if (firstGem == null) return;
        int count = GemSystem.MIN_GEMS_PER_LEVEL
                + rng.nextInt(GemSystem.MAX_GEMS_PER_LEVEL - GemSystem.MIN_GEMS_PER_LEVEL + 1);
        for (int i = 0; i < count; i++) {
            Point p = LevelFactoryUtils.randomFloorTile(level, rng);
            if (p == null) return;
            Item gem = (i == 0) ? firstGem
                                : ItemGenerator.generateItem(powerLevel, level.theme,
                                        ItemGenerator.LootCategory.GEM, rng);
            if (gem == null) return;
            placeItem(level, gem, p);
        }
    }

    // -- Mobs ----------------------------------------------------------------

    /** Random encounters drawn from the powerLevel-weighted pool until the level
     *  carries 7-10 mobs hostile to the player. Each encounter rolls its species'
     *  {@code clusterSize} (extra copies of the same species on adjacent floor tiles)
     *  and {@code numRetainers} (entourage of other species, e.g. cats with kittens
     *  or kobold generals with their guard); retainers are spawned with
     *  {@link Mob#owner} pointed at the parent so their FOLLOWING-state AI tracks
     *  them. Non-hostile spawns (cats, kittens, mice, neutral critters) don't count
     *  toward the target - they're flavor population, not encounter pressure. */
    public static void placeMobs(Level level, Random rng,
                                 com.bjsp123.rl2.model.UniqueTracker unique) {
        double[][] weightHolder = new double[1][];
        List<String> pool = eligibleMobs(level, weightHolder);
        int spawnLevel = 1 + level.depth;

        // -- Regular weighted scatter --------------------------------------
        if (!pool.isEmpty()) {
            int hostileTarget = GameBalance.STARTING_MOBS_PER_LEVEL + rng.nextInt(4);
            int hostileCount  = 0;
            // Bound the loop in case the eligible pool is all non-hostile flavor
            // species (deep mouse-only levels, etc.) - without the cap a level whose
            // pool can't hit the target would scatter mobs forever.
            int safety = 60;
            while (hostileCount < hostileTarget && safety-- > 0) {
                Point seed = LevelFactoryUtils.randomFloorTile(level, rng);
                if (seed == null) break;
                if (mobAt(level, seed.tileX(), seed.tileY())) continue;
                if (level.isReserved(seed.tileX(), seed.tileY())) continue;
                if (inStairsUpRoom(level, seed.tileX(), seed.tileY())) continue;
                String type = pickWeighted(pool, weightHolder[0], rng);
                if (type == null) break;
                MobDefinition def = Registries.mob(type);
                int n = Math.max(1, rollMinMax(def == null ? null : def.clusterSize, rng));
                List<Point> spots = adjacentFloorTiles(level, seed, n);
                int before = level.mobs.size();
                Mob first = null;
                for (Point p : spots) {
                    if (level.isReserved(p.tileX(), p.tileY())) continue;
                    Mob m = spawnMobAt(level, type, p, spawnLevel, rng,
                                       /* withRetainers= */ first == null);
                    if (m == null) continue;
                    if (first == null) first = m;
                }
                // Tally newly-added mobs (cluster + retainers) that are hostile to
                // the player. Retainers come in via spawnMobAt -> placeRetainers, so
                // counting via the level.mobs delta is the simplest catch-all.
                for (int j = before; j < level.mobs.size(); j++) {
                    if (isHostileToPlayer(level.mobs.get(j))) hostileCount++;
                }
            }
        }

        // -- Unique pass: independent roll per eligible candidate ---------
        // Base chance is 50%; multiplied by theme factor (so matching theme
        // raises it to 100%, mismatching lowers it to 25%).
        for (String type : eligibleUniqueMobs(level, unique)) {
            MobDefinition ud = Registries.mob(type);
            double chance = Math.min(1.0, 0.5 * themeMultiplier(
                    ud == null ? null : ud.theme, level.theme));
            if (rng.nextDouble() >= chance) continue;
            Point seed = LevelFactoryUtils.randomFloorTile(level, rng);
            if (seed == null) break;
            if (mobAt(level, seed.tileX(), seed.tileY())) continue;
            if (level.isReserved(seed.tileX(), seed.tileY())) continue;
            if (inStairsUpRoom(level, seed.tileX(), seed.tileY())) continue;
            Mob m = spawnMobAt(level, type, seed, spawnLevel, rng,
                               /* withRetainers= */ true);
            if (m == null) continue;
            unique.mobs.add(type);
        }
    }

    /** Canonical spawn chain: build the mob, scale to {@code spawnLevel}, append to
     *  {@code level.mobs}, and (when {@code withRetainers} is true) generate the
     *  CSV-declared retainer entourage on adjacent floor tiles. Returns the spawned
     *  mob, or {@code null} when {@code type} isn't in the registry / position is bad.
     *  Used by {@link #placeMobs} and by themed-room population - same code path so
     *  both honour cluster + retainer rules consistently. */
    static Mob spawnMobAt(Level level, String type, Point pos, int spawnLevel,
                          Random rng, boolean withRetainers) {
        Mob m = MobFactory.spawn(type, pos);
        if (m == null) return null;
        MobProgression.setSpawnLevel(m, spawnLevel);
        brandStartingInventory(m, rng);
        // Pre-roll the mob's drops into its bag at spawn time. Death-time
        // {@link LootSystem#dropLootOnDeath} just dumps the bag - making the
        // dungeon's loot deterministic for a given world seed.
        LootSystem.rollAndStashLoot(level, m, rng);
        level.mobs.add(m);
        if (withRetainers) placeRetainers(level, m, Registries.mob(type), rng, spawnLevel);
        return m;
    }

    /** Spawn this mob's CSV-declared retainers on adjacent floor tiles, owned by the
     *  parent. Count is rolled from {@code def.numRetainers}; each slot draws an
     *  independent type from {@code def.retainerTypes}. Package-private so themed-room
     *  population can call it directly when seeding a unique-room boss. */
    static void placeRetainers(Level level, Mob parent, MobDefinition def,
                               Random rng, int spawnLevel) {
        if (def == null || def.retainerTypes == null || def.retainerTypes.isEmpty()) return;
        int n = rollMinMax(def.numRetainers, rng);
        if (n <= 0) return;
        List<Point> spots = adjacentFloorTiles(level, parent.position, n + 1);
        // First slot in adjacentFloorTiles is the parent's own tile - skip it.
        int placed = 0;
        for (int i = 1; i < spots.size() && placed < n; i++) {
            Point p = spots.get(i);
            if (mobAt(level, p.tileX(), p.tileY())) continue;
            String t = def.retainerTypes.get(rng.nextInt(def.retainerTypes.size()));
            Mob r = MobFactory.spawn(t, p);
            if (r == null) continue;
            brandStartingInventory(r, rng);
            r.owner = parent;
            MobProgression.setSpawnLevel(r, spawnLevel);
            LootSystem.rollAndStashLoot(level, r, rng);
            level.mobs.add(r);
            placed++;
        }
    }

    /** Apply random brands to a non-player mob's starting inventory items.
     *  Player starting kits are left unbranded for a consistent game start. */
    private static void brandStartingInventory(Mob m, Random rng) {
        if (m.behavior == Mob.Behavior.PLAYER || m.inventory == null) return;
        for (Item it : m.inventory.bag) BrandSystem.applyRandomBrand(it, rng);
        for (Item it : m.inventory.allEquipped()) BrandSystem.applyRandomBrand(it, rng);
    }

    /** Inclusive sample from a {@link com.bjsp123.rl2.model.MinMax} range. Null /
     *  zero range -> {@code 0}. Package-private so themed-room code can roll counts
     *  ({@code 2_3} -> 2, 3, or somewhere in between) the same way. */
    static int rollMinMax(com.bjsp123.rl2.model.MinMax mm, Random rng) {
        if (mm == null) return 0;
        int lo = mm.min(), hi = mm.max();
        if (hi <= lo) return Math.max(0, lo);
        return lo + rng.nextInt(hi - lo + 1);
    }

    /** BFS outward from {@code seed}, returning up to {@code count} reachable
     *  FLOOR / FLOOR_WOOD tiles in BFS order (the seed sits at index 0 if it's a
     *  floor cell, so growth radiates evenly). Expansion only continues through
     *  floor - see {@link #adjacentTilesPermeable} when callers need to find floor
     *  surrounding a chasm-centred decoration. Mobs already standing on a tile are
     *  NOT excluded here - callers handle the occupancy check. Package-private. */
    static List<Point> adjacentFloorTiles(Level level, Point seed, int count) {
        return collectByBfs(level, seed, count,
                /* permeable= */ false, /* allowChasm= */ false);
    }

    /** Like {@link #adjacentFloorTiles} but the BFS expands through any tile (walls,
     *  chasm, etc.); only floor tiles are collected. Used by themed-room {@code CENTER}
     *  placement so a kobold-fortress with a chasm centre can still find the surrounding
     *  floor cells. Package-private. */
    static List<Point> adjacentTilesPermeable(Level level, Point seed, int count) {
        return collectByBfs(level, seed, count,
                /* permeable= */ true, /* allowChasm= */ false);
    }

    /** Like {@link #adjacentTilesPermeable} but also collects {@code CHASM} tiles -
     *  used by the BELFRY's flying-mob spawns. Package-private. */
    static List<Point> adjacentTilesAllowChasm(Level level, Point seed, int count) {
        return collectByBfs(level, seed, count,
                /* permeable= */ true, /* allowChasm= */ true);
    }

    private static List<Point> collectByBfs(Level level, Point seed, int count,
                                            boolean permeable, boolean allowChasm) {
        List<Point> out = new ArrayList<>();
        if (seed == null || count <= 0) return out;
        boolean[][] seen = new boolean[level.width][level.height];
        java.util.Deque<Point> q = new java.util.ArrayDeque<>();
        q.add(seed);
        seen[seed.tileX()][seed.tileY()] = true;
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        while (!q.isEmpty() && out.size() < count) {
            Point p = q.poll();
            if (collectible(level.tiles[p.tileX()][p.tileY()], allowChasm)) out.add(p);
            for (int[] d : dirs) {
                int nx = p.tileX() + d[0], ny = p.tileY() + d[1];
                if (nx <= 0 || ny <= 0 || nx >= level.width - 1 || ny >= level.height - 1) continue;
                if (seen[nx][ny]) continue;
                Tile nt = level.tiles[nx][ny];
                if (!permeable && nt != Tile.FLOOR && nt != Tile.FLOOR_WOOD) continue;
                seen[nx][ny] = true;
                q.add(new Point(nx, ny));
            }
        }
        return out;
    }

    private static boolean collectible(Tile t, boolean allowChasm) {
        if (t == Tile.FLOOR || t == Tile.FLOOR_WOOD) return true;
        return allowChasm && t == Tile.CHASM;
    }

    /** Spawn-time predicate for "would this mob attack the player on sight?". Reads
     *  the species' CSV-declared {@code enemyFactions} for the {@code "PLAYER"}
     *  faction tag - every aggressive monster row carries it; flavor critters
     *  (mice, kittens, cats) leave it blank. Used by {@link #placeMobs} so the
     *  encounter target counts only fightable threats. */
    private static boolean isHostileToPlayer(Mob mob) {
        if (mob == null || mob.enemyFactions == null) return false;
        return mob.enemyFactions.contains(PLAYER_FACTION);
    }

    /** Faction string used by every {@code PLAYER_*} kit row in {@code mobs.csv};
     *  hostility is faction-driven, so this is the key spawn-time scatter checks
     *  against. Kept as a constant rather than scattered string literals. */
    private static final String PLAYER_FACTION = "PLAYER";

    private static boolean mobAt(Level level, int x, int y) {
        for (Mob m : level.mobs)
            if (m.position.tileX() == x && m.position.tileY() == y) return true;
        return false;
    }

    /** True if (x, y) falls inside the room that contains the stairs-up tile.
     *  Rooms with stairs up are kept mob-free so the player spawns into a safe area. */
    private static boolean inStairsUpRoom(Level level, int x, int y) {
        if (level.stairsUp == null || level.rooms == null) return false;
        int sx = level.stairsUp.tileX();
        int sy = level.stairsUp.tileY();
        for (Level.RoomSnapshot r : level.rooms) {
            if (sx >= r.x && sx < r.x + r.w && sy >= r.y && sy < r.y + r.h) {
                return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
            }
        }
        return false;
    }
}
