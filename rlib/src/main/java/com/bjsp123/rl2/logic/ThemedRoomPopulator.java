package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Resolves a {@link ThemedRoomDefinition}'s {@code mobs} / {@code items} spec
 * lists into actual spawns inside the room rectangle, honouring the room's
 * {@link ThemedRoomDefinition.Placement} token.
 *
 * <p>Mob spawns route through {@link LevelFactoryPopulate#spawnMobAt} so
 * cluster + retainer rules apply automatically (kobold generals pull in their
 * guard, cats pull in kittens). Item spawns route through
 * {@link ItemGenerator}: a spec ref that parses as a {@link
 * ItemGenerator.LootCategory} (e.g. {@code POTIONS}, {@code MAGIC_ITEMS},
 * {@code EQUIPMENT}) rolls a random item from that category at the level's
 * power-level; an unknown ref falls back to a literal item-type lookup so
 * authors can pre-declare a specific drop (e.g. {@code HEALING_POTION*1}).
 */
final class ThemedRoomPopulator {

    private ThemedRoomPopulator() {}

    static void populate(Level level, ThemedRoomDefinition def,
                         int x, int y, int w, int h, int spawnLevel, Random rng) {
        if (def.placement == ThemedRoomDefinition.Placement.OPPOSITE_ENDS) {
            populateOppositeEnds(level, def, x, y, w, h, spawnLevel, rng);
            return;
        }
        boolean allowChasm = def.placement == ThemedRoomDefinition.Placement.CHASM_OK;
        Point seed = new Point(x + w / 2, y + h / 2);

        // Items always need floor. Mobs may also need chasm if the room allows it.
        List<Point> mobSpots  = collect(level, seed, /*budget*/ totalCount(def.mobs)  + 4, allowChasm);
        List<Point> itemSpots = LevelFactoryPopulate.adjacentTilesPermeable(level, seed,
                                                       totalCount(def.items) + 4);

        spawnMobs (level, def.mobs,  mobSpots,  spawnLevel, rng);
        spawnItems(level, def.items, itemSpots, rng);
    }

    /** Split the spec lists in half: first half spawns from a west-edge seed,
     *  second half from an east-edge seed. Used for the ant-war room so the
     *  two anthills sit at opposite ends of the rectangle. */
    private static void populateOppositeEnds(Level level, ThemedRoomDefinition def,
                                             int x, int y, int w, int h,
                                             int spawnLevel, Random rng) {
        Point west = new Point(x + 1, y + h / 2);
        Point east = new Point(x + w - 2, y + h / 2);

        List<CsvTable.SpawnSpec> mobsW = halfOf(def.mobs, true);
        List<CsvTable.SpawnSpec> mobsE = halfOf(def.mobs, false);
        List<CsvTable.SpawnSpec> itsW  = halfOf(def.items, true);
        List<CsvTable.SpawnSpec> itsE  = halfOf(def.items, false);

        spawnMobs (level, mobsW,
                LevelFactoryPopulate.adjacentTilesPermeable(level, west, totalCount(mobsW) + 4),
                spawnLevel, rng);
        spawnMobs (level, mobsE,
                LevelFactoryPopulate.adjacentTilesPermeable(level, east, totalCount(mobsE) + 4),
                spawnLevel, rng);
        spawnItems(level, itsW,
                LevelFactoryPopulate.adjacentTilesPermeable(level, west, totalCount(itsW) + 4),
                rng);
        spawnItems(level, itsE,
                LevelFactoryPopulate.adjacentTilesPermeable(level, east, totalCount(itsE) + 4),
                rng);
    }

    /** First or second half of {@code list}, rounded so a 3-entry list splits 2/1. */
    private static List<CsvTable.SpawnSpec> halfOf(List<CsvTable.SpawnSpec> list, boolean first) {
        int half = (list.size() + 1) / 2;
        return first
                ? new ArrayList<>(list.subList(0, half))
                : new ArrayList<>(list.subList(half, list.size()));
    }

    private static int totalCount(List<CsvTable.SpawnSpec> specs) {
        int total = 0;
        for (CsvTable.SpawnSpec s : specs) total += Math.max(s.min, s.max);
        return Math.max(1, total);
    }

    private static List<Point> collect(Level level, Point seed, int budget, boolean allowChasm) {
        return allowChasm
                ? LevelFactoryPopulate.adjacentTilesAllowChasm(level, seed, budget)
                : LevelFactoryPopulate.adjacentTilesPermeable(level, seed, budget);
    }

    // -- Mob spawning -------------------------------------------------------

    private static void spawnMobs(Level level, List<CsvTable.SpawnSpec> specs,
                                  List<Point> spots, int spawnLevel, Random rng) {
        int idx = 0;
        for (CsvTable.SpawnSpec spec : specs) {
            int count = rollRange(spec, rng);
            for (int i = 0; i < count && idx < spots.size(); i++) {
                Point p = spots.get(idx++);
                LevelFactoryPopulate.spawnMobAt(level, spec.ref, p, spawnLevel, rng,
                        /* withRetainers= */ true);
            }
        }
    }

    // -- Item spawning ------------------------------------------------------

    private static void spawnItems(Level level, List<CsvTable.SpawnSpec> specs,
                                   List<Point> spots, Random rng) {
        double powerLevel = LevelFactoryPopulate.depthFraction(level);
        int idx = 0;
        for (CsvTable.SpawnSpec spec : specs) {
            int count = rollRange(spec, rng);
            for (int i = 0; i < count && idx < spots.size(); i++) {
                Item built = resolveAndBuild(level, spec.ref, powerLevel, rng);
                if (built == null) continue;
                Point p = spots.get(idx++);
                LevelFactoryPopulate.placeItem(level, built, p);
            }
        }
    }

    /** Build an item from a themed-room spec ref. Tries the ref as a {@link
     *  ItemGenerator.LootCategory} name first ({@code POTIONS}, {@code
     *  EQUIPMENT}, etc.); on miss, treats it as a literal item-type and routes
     *  through {@link ItemGenerator#buildItem} so plusses scale to the level's
     *  power-level. Returns null when both routes come up empty. */
    private static Item resolveAndBuild(Level level, String ref, double powerLevel, Random rng) {
        if (ref == null || ref.isEmpty()) return null;
        ItemGenerator.LootCategory cat = ItemGenerator.LootCategory.parse(ref);
        if (cat != null) {
            // Themed rooms are the curated home for restricted-drop items
            // (POWER_ORB), so include them when resolving the room's
            // category specs - that's the whole point of the restriction.
            return ItemGenerator.generateItem(powerLevel, level.theme, cat,
                    /*includeRestricted=*/ true, rng);
        }
        return ItemGenerator.buildItem(ref, powerLevel, rng);
    }

    // -- Helpers ------------------------------------------------------------

    private static int rollRange(CsvTable.SpawnSpec spec, Random rng) {
        int lo = Math.max(0, spec.min);
        int hi = Math.max(lo, spec.max);
        if (hi == lo) return lo;
        return lo + rng.nextInt(hi - lo + 1);
    }
}
