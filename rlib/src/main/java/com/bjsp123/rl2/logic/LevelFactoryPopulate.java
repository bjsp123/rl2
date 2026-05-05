package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemType;
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
 * size targets. Mob and item counts are independent of layout; they roll based on
 * {@link LevelFlag}s and the number of available floor tiles.
 */
public final class LevelFactoryPopulate {

    private LevelFactoryPopulate() {}

    /** Item types eligible for random scatter on a fresh level. {@link ItemType#GEM}
     *  is excluded — gems have their own placement path via {@link #placeGems}. */
    private static final List<ItemType> ITEM_POOL = List.of(
            ItemType.SWORD, ItemType.DAGGER, ItemType.SHIELD, ItemType.SCALE_MAIL,
            ItemType.AMULET_OF_LIGHT,
            // Food
            ItemType.PEAR, ItemType.FISH, ItemType.PEAR_SCRUMPTIOUS,
            ItemType.PEAR_SILVERY, ItemType.PEAR_CONFERENCE,
            // Bombs
            ItemType.FIRE_BOMB, ItemType.OIL_BOMB, ItemType.BLAST_BOMB, ItemType.FREEZE_BOMB,
            // Potions
            ItemType.HEALING_POTION, ItemType.POTION_SORCERY, ItemType.POTION_GHOSTLINESS,
            ItemType.POTION_INVISIBILITY, ItemType.POTION_POISON,
            // Magical consumables
            ItemType.POWER_ORB
    );

    /** Hostile pool — every mob-type string the depth-scaled mob roller draws from.
     *  Mirrors the legacy method-reference pool; species names match the {@code type}
     *  column of {@code assets/data/mobs.csv}. {@link #pickMob} weights these by
     *  {@link MobFight#threatScore} closeness to a depth-scaled target so deeper
     *  levels favour stronger species. */
    private static final List<String> HOSTILE_POOL = List.of(
            "SPIDER",
            "LOATHESOME_BUG",
            "RAT",
            "BAT",
            "SOLDIER_BUG",
            "BUG_PRODIGY",
            "KOBOLD_FIGHTER",
            "KOBOLD_SPEARMAN",
            "KOBOLD_CLEAVER",
            "KOBOLD_GENERAL",
            "MASK_IMP",
            "LARGE_MASK_IMP",
            "DEVELOPED_MASK_IMP",
            "GHOST"
    );

    /** Non-hostile pool — critters that don't attack the player. Each {@link #pickMob}
     *  call has a flat {@link #NON_HOSTILE_FRACTION} chance to draw from this pool
     *  regardless of dungeon depth. Kittens never appear here — they only spawn as
     *  litters via {@link #placeKittens}. */
    private static final List<String> NON_HOSTILE_POOL = List.of(
            "MOUSE",
            "BLAZING_FIREMOUSE",
            "CAT",
            "DOG"
    );

    /** Probability that {@link #pickMob} returns a non-hostile rather than a hostile.
     *  Flat across depths — the user's spec is "non-hostile mobs have a flat chance to
     *  spawn regardless of the level". */
    private static final double NON_HOSTILE_FRACTION = 0.30;

    /** Cached {@link MobFight#threatScore} per hostile species, keyed by index into
     *  {@link #HOSTILE_POOL}. Lazily computed on first {@link #pickMob} call so we don't
     *  pay the simulation cost when the level factory isn't doing mob spawns. */
    private static double[] hostileThreatScores;

    private static double threatOf(int hostileIdx) {
        if (hostileThreatScores == null) {
            hostileThreatScores = new double[HOSTILE_POOL.size()];
            Point dummy = new Point(0, 0);
            for (int i = 0; i < HOSTILE_POOL.size(); i++) {
                Mob sample = MobFactory.spawn(HOSTILE_POOL.get(i), dummy);
                hostileThreatScores[i] = sample == null ? 0.0 : MobFight.threatScore(sample);
            }
        }
        return hostileThreatScores[hostileIdx];
    }

    /**
     * Pick a mob-type string appropriate for {@code level}. Each call rolls
     * {@link #NON_HOSTILE_FRACTION} for non-hostile-vs-hostile; non-hostile draws are
     * uniform across {@link #NON_HOSTILE_POOL}; hostile draws are weighted by closeness
     * of the candidate's {@link MobFight#threatScore} to a depth-scaled target so deeper
     * levels favour more dangerous species. Caller invokes
     * {@code MobFactory.spawn(picked, pos)} to materialize.
     *
     * <p>The depth-target ramp is {@code 4 * depth} — depth 1 targets threat ~4
     * (NEGLIGIBLE/MINOR), depth 5 targets ~20 (MODERATE+). The weight is
     * {@code 1 / (1 + ((score - target) / 5)²)} so candidates within ±5 of the target
     * dominate the roll, but every species retains a non-zero chance.
     */
    public static String pickMob(Level level, Random rng) {
        if (NON_HOSTILE_POOL.isEmpty() && HOSTILE_POOL.isEmpty()) {
            return "MOUSE";
        }
        boolean nonHostile = !NON_HOSTILE_POOL.isEmpty()
                && (HOSTILE_POOL.isEmpty() || rng.nextDouble() < NON_HOSTILE_FRACTION);
        if (nonHostile) {
            return NON_HOSTILE_POOL.get(rng.nextInt(NON_HOSTILE_POOL.size()));
        }
        int depth = level == null ? 1 : Math.max(1, level.depth);
        double targetThreat = 4.0 * depth;
        // Build cumulative weights, then bisect by uniform sample.
        double[] cum = new double[HOSTILE_POOL.size()];
        double total = 0.0;
        for (int i = 0; i < HOSTILE_POOL.size(); i++) {
            double dist = (threatOf(i) - targetThreat) / 5.0;
            double w = 1.0 / (1.0 + dist * dist);
            total += w;
            cum[i] = total;
        }
        if (total <= 0) {
            return HOSTILE_POOL.get(rng.nextInt(HOSTILE_POOL.size()));
        }
        double r = rng.nextDouble() * total;
        for (int i = 0; i < cum.length; i++) {
            if (r <= cum[i]) return HOSTILE_POOL.get(i);
        }
        return HOSTILE_POOL.get(HOSTILE_POOL.size() - 1);
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

    /** One pear (always) plus 1–3 random items from {@link #ITEM_POOL}, plus 0–2 clusters
     *  of fire/oil bombs (1–3 per cluster). Items prefer enclosed interiors (village
     *  buildings, subrooms, small rooms) over open corridors / open green — picked via
     *  {@link LevelFactoryUtils#randomInnerFloorTile}'s wall-density heuristic. */
    public static void placeItems(Level level, Random rng) {
        Point pearSpot = LevelFactoryUtils.randomInnerFloorTile(level, rng, INNER_TILE_SAMPLES);
        if (pearSpot != null) {
            Item pear = ItemFactory.build(ItemType.PEAR);
            pear.location = pearSpot;
            // Food is always level 0 — leave alone.
            level.items.add(pear);
        }
        int count = 1 + rng.nextInt(3);
        for (int i = 0; i < count; i++) {
            Point p = LevelFactoryUtils.randomInnerFloorTile(level, rng, INNER_TILE_SAMPLES);
            if (p == null) return;
            Item it = ItemFactory.build(ITEM_POOL.get(rng.nextInt(ITEM_POOL.size())));
            it.location = p;
            assignItemLevel(it, level.depth, rng);
            level.items.add(it);
        }
        placeBombClusters(level, rng);
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
     *  via {@link GemSystem#rollSpeciesForTheme}; non-gem-bearing themes (CLASSIC, URBAN,
     *  ORGANIC, NEUTRAL) skip this entirely. */
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

    /**
     * Bombs scatter in small same-type stockpiles — finding three fire bombs together is
     * more interesting than one of each in opposite corners. {@code 0..2} clusters per
     * level, each with {@code 1..3} bombs of a single kind, dropped on adjacent floor tiles
     * via cardinal-step BFS from a randomly-picked seed. A cluster falls back to "drop
     * what's left at the seed" if neighbours are blocked.
     */
    private static void placeBombClusters(Level level, Random rng) {
        int clusters = rng.nextInt(3);   // 0..2
        for (int c = 0; c < clusters; c++) {
            Point seed = LevelFactoryUtils.randomFloorTile(level, rng);
            if (seed == null) return;
            int count = 1 + rng.nextInt(3);
            boolean fire = rng.nextBoolean();
            // BFS-grow a small footprint from seed across cardinal floor neighbours.
            List<Point> taken = new ArrayList<>();
            taken.add(seed);
            int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
            outer:
            while (taken.size() < count) {
                for (Point cur : new ArrayList<>(taken)) {
                    for (int[] d : dirs) {
                        int nx = cur.tileX() + d[0], ny = cur.tileY() + d[1];
                        if (nx <= 0 || ny <= 0 || nx >= level.width - 1 || ny >= level.height - 1) continue;
                        if (level.tiles[nx][ny] != com.bjsp123.rl2.model.Tile.FLOOR) continue;
                        Point np = new Point(nx, ny);
                        boolean already = false;
                        for (Point t : taken) if (t.equals(np)) { already = true; break; }
                        if (already) continue;
                        taken.add(np);
                        if (taken.size() >= count) break outer;
                    }
                }
                break; // no growth possible — keep what we have
            }
            for (Point p : taken) {
                Item bomb = ItemFactory.build(fire ? ItemType.FIRE_BOMB : ItemType.OIL_BOMB);
                bomb.location = p;
                level.items.add(bomb);
            }
        }
    }

    // ── Mobs ────────────────────────────────────────────────────────────────

    /** 2–5 random mobs picked via {@link #pickMob} (depth-aware hostile + flat non-hostile
     *  chance) on unoccupied floor tiles, plus the independent special-mob rolls below. */
    public static void placeMobs(Level level, Random rng) {
        int count = 2 + rng.nextInt(4);
        int spawnLevel = 1 + level.depth;
        for (int i = 0; i < count; i++) {
            Point p = LevelFactoryUtils.randomFloorTile(level, rng);
            if (p == null) return;
            if (mobAt(level, p.tileX(), p.tileY())) continue;
            Mob m = MobFactory.spawn(pickMob(level, rng), p);
            if (m == null) continue;
            // Every dungeon-spawned mob starts at character-level 1 + depth so combat
            // stats scale alongside the player's progression.
            MobProgression.setSpawnLevel(m, spawnLevel);
            level.mobs.add(m);
        }
        placeSpecialMob(level, rng, "HORROR");
        placeSpecialMob(level, rng, "HORRIBLE_MASK_IMP");
        placeSpecialMob(level, rng, "KISSYBLOB");
        placeSpecialMob(level, rng, "BLOB");
        placeSpecialMob(level, rng, "BARBARIAN_PRINCESS");
        // Ant hills — independent rolls per colour, same 50%-per-special cadence as the
        // other set-pieces. The hill itself will start spitting out ants on a 20%/turn
        // roll once the level loads (see TurnSystem.tickStandardTurn).
        placeSpecialMob(level, rng, "BLACK_ANT_HILL");
        placeSpecialMob(level, rng, "RED_ANT_HILL");
        placeKittens(level, rng);
    }

    /**
     * Per cat, roll a 1/3 chance of giving the cat a litter of 1–2 kittens. Successful
     * rolls drop the kittens onto random unoccupied floor tiles inside the cat's room
     * (BFS bounded by walls + doors so we don't cross into the next chamber); each
     * kitten's {@link Mob#owner} is wired to the cat that triggered the spawn so its
     * FOLLOWING-state AI shadows the right leader.
     *
     * <p>Kittens never spawn solo — the {@link #MOB_POOL} excludes them and the
     * {@link #placeSpecialMob} calls in {@link #placeMobs} don't reference them, so this
     * is the only path that places a kitten. A cat without a successful litter roll is
     * just an adult cat, no kittens, no fuss.
     */
    private static void placeKittens(Level level, Random rng) {
        // Iterate every mob whose CSV row carries a {@code kittenType} — today that's
        // only the cat, but the loop is data-driven so any future "spawns young
        // alongside itself" species drops in by adding a column value.
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

    /** 50%-chance per call: pick a random floor tile and spawn a mob of
     *  {@code mobType}. Skipped if the chosen tile is already occupied — keeps the spawn
     *  decision independent across species so two specials can both land. */
    private static void placeSpecialMob(Level level, Random rng, String mobType) {
        if (rng.nextInt(2) != 0) return;
        Point p = LevelFactoryUtils.randomFloorTile(level, rng);
        if (p == null) return;
        if (mobAt(level, p.tileX(), p.tileY())) return;
        Mob m = MobFactory.spawn(mobType, p);
        if (m == null) return;
        MobProgression.setSpawnLevel(m, 1 + level.depth);
        level.mobs.add(m);
    }

    private static boolean mobAt(Level level, int x, int y) {
        for (Mob m : level.mobs)
            if (m.position.tileX() == x && m.position.tileY() == y) return true;
        return false;
    }
}
