package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Headless loot-density sampler. Not shipping code.
 *
 * <p>Generates a batch of worlds (same pipeline {@code Rl2Game.create} uses,
 * minus libGDX) and reports the average number of POTION / WEAPON / ARMOR /
 * AMULET / BOMB items present per level. Counts every item a player could
 * acquire on a level: those resting on the floor ({@link Level#items}) plus
 * those carried by the level's mobs (bag + equipped), since both end up as
 * floor loot once the mob dies.
 *
 * <p>Run via the Gradle task:
 * <pre>
 *   ./gradlew :rlib:lootStats                 # default 40 worlds
 *   ./gradlew :rlib:lootStats --args="200"    # 200 worlds
 * </pre>
 */
public final class LootStatsMain {

    private LootStatsMain() {}

    /** Categories the report breaks out, in display order. */
    private static final Item.InventoryCategory[] REPORTED = {
            Item.InventoryCategory.POTION,
            Item.InventoryCategory.WEAPON,
            Item.InventoryCategory.ARMOR,
            Item.InventoryCategory.AMULET,
            Item.InventoryCategory.BOMB,
    };

    public static void main(String[] args) throws IOException {
        int worlds = args.length > 0 ? Integer.parseInt(args[0].trim()) : 40;
        Path assets = ArenaHarness.locateAssetsDir();
        ArenaHarness.loadData(assets);

        int width = 48, height = 48;
        // Fixed base seed so the run is reproducible; each world offsets from it.
        Random seedRng = new Random(0xC0FFEE);

        // Floor-only and total (floor + mob-carried) tallies, plus level count.
        Map<Item.InventoryCategory, Long> floorTotal = new EnumMap<>(Item.InventoryCategory.class);
        Map<Item.InventoryCategory, Long> grandTotal = new EnumMap<>(Item.InventoryCategory.class);
        for (Item.InventoryCategory c : REPORTED) { floorTotal.put(c, 0L); grandTotal.put(c, 0L); }
        long levelCount = 0;

        for (int w = 0; w < worlds; w++) {
            Random rng = new Random(seedRng.nextLong());
            World world = new World();
            world.unique = new UniqueTracker();
            world.levels = WorldTopology.build(width, height, rng, world.unique);
            if (world.levels == null) continue;
            for (Level lvl : world.levels) {
                if (lvl == null || lvl.tiles == null) continue;   // topology holes
                levelCount++;
                if (lvl.items != null) {
                    for (Item it : lvl.items) bump(floorTotal, it);
                }
                Map<Item.InventoryCategory, Long> levelAll = new EnumMap<>(Item.InventoryCategory.class);
                if (lvl.items != null) for (Item it : lvl.items) bump(levelAll, it);
                if (lvl.mobs != null) {
                    for (Mob m : lvl.mobs) {
                        if (m == null || m.inventory == null) continue;
                        for (Item it : m.inventory.bag) bump(levelAll, it);
                        for (Item it : m.inventory.allEquipped()) bump(levelAll, it);
                    }
                }
                for (Item.InventoryCategory c : REPORTED) {
                    grandTotal.merge(c, levelAll.getOrDefault(c, 0L), Long::sum);
                }
            }
        }

        System.out.printf("[rl2] sampled %d worlds, %d levels (%dx%d, DUNGEON_DEPTH=%d)%n",
                worlds, levelCount, width, height,
                com.bjsp123.rl2.logic.GameBalance.DUNGEON_DEPTH);
        System.out.println();
        System.out.printf("%-10s %12s %12s%n", "category", "floor/level", "total/level");
        System.out.println("-------------------------------------------");
        double lc = Math.max(1, levelCount);
        for (Item.InventoryCategory c : REPORTED) {
            System.out.printf("%-10s %12.2f %12.2f%n", c,
                    floorTotal.get(c) / lc, grandTotal.get(c) / lc);
        }
        System.out.println();
        System.out.println("floor/level = items resting on the ground; "
                + "total/level also counts mob-carried loot (bag + equipped).");
    }

    private static void bump(Map<Item.InventoryCategory, Long> tally, Item it) {
        if (it == null || it.inventoryCategory == null) return;
        if (tally.containsKey(it.inventoryCategory)
                || isReported(it.inventoryCategory)) {
            tally.merge(it.inventoryCategory, 1L, Long::sum);
        }
    }

    private static boolean isReported(Item.InventoryCategory c) {
        for (Item.InventoryCategory r : REPORTED) if (r == c) return true;
        return false;
    }
}
