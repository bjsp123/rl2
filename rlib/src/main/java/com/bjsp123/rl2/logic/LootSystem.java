package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Loot pipeline. Loot rolling happens at <em>spawn time</em>
 * ({@link #rollAndStashLoot}) - the mob carries its catalogued drops in its
 * inventory bag from the moment it's created. {@link #dropLootOnDeath} then
 * just empties bag + equipment onto the floor and posts a
 * {@link GameEvent.LootDropped} per item so the renderer can play the
 * arcing-toss animation from corpse to landing tile. Pre-rolling means a
 * dungeon's loot is fully determined at world-build time (deterministic
 * given the seed) and inspecting a sleeping mob's bag mid-run will reveal
 * exactly what they're going to drop.
 *
 * <p>Drops are declared via the {@code dropQuality} CSV column as a
 * pipe-separated {@link CsvTable.DropSpec} list. Each entry names a keyword
 * ({@code NONE}, {@code STUFF}, a {@link ItemGenerator.LootCategory}, or a
 * literal item type), with optional {@code +n} (forced plus level) and
 * {@code *n} (fractional count: integer part guaranteed, decimal part =
 * probability of one bonus item).
 */
public final class LootSystem {

    private LootSystem() {}

    /** Roll every item this mob will drop on death and stash them in
     *  {@code mob.inventory.bag} now. Called once per spawned mob from
     *  {@link LevelFactoryPopulate#spawnMobAt} / {@link LevelFactoryPopulate#placeRetainers},
     *  using the world rng so the result is deterministic for a given seed.
     *  Skipped for the player (their death ends the run) and for mobs without
     *  an inventory pointer.
     *
     *  <p>Items go straight into {@code bag} as independent entries (no
     *  stack-merge) so {@link #dropLootOnDeath} can drop them as separate
     *  pickups even when their stack keys match an existing inventory item. */
    public static void rollAndStashLoot(Level level, Mob mob, Random rng) {
        if (level == null || mob == null || mob.inventory == null) return;
        if (mob.isPlayer) return;
        MobDefinition def = Registries.mob(mob.mobType);
        if (def == null || def.drops == null || def.drops.isEmpty()) return;

        double powerLevel = itemLevelToPower(Math.max(1, mob.characterLevel - 2));

        for (CsvTable.DropSpec spec : def.drops) {
            if (spec.keyword == null
                    || "NONE".equalsIgnoreCase(spec.keyword)
                    || "NOTHING_AT_ALL".equalsIgnoreCase(spec.keyword)) continue;

            // Integer part = guaranteed count; fractional part = probability of +1.
            // LOOT_DROP_FREQUENCY_COEFF scales the whole quantity so a coefficient
            // of 2 doubles every drop entry; 0.5 halves it.
            double scaledCount = spec.count * GameBalance.LOOT_DROP_FREQUENCY_COEFF;
            int n = (int) Math.floor(scaledCount);
            double frac = scaledCount - n;
            if (frac > 0 && rng.nextDouble() < frac) n++;

            for (int i = 0; i < n; i++) {
                Item it = resolveItem(level, spec, powerLevel, rng);
                if (it != null) mob.inventory.bag.add(it);
            }
        }
    }

    /** True when the mob definition declares {@code dropQuality=NOTHING_AT_ALL}
     *  (case-insensitive). Used by {@link #dropLootOnDeath} to suppress the
     *  inventory dump on kill - the mob's gear vanishes with it. Distinct
     *  from a plain {@code NONE} entry, which only skips the rolled drop
     *  table but still drops what the mob was carrying. */
    private static boolean dropsNothingAtAll(MobDefinition def) {
        if (def == null || def.drops == null) return false;
        for (CsvTable.DropSpec spec : def.drops) {
            if (spec.keyword != null && "NOTHING_AT_ALL".equalsIgnoreCase(spec.keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Build one item from a drop spec. {@code STUFF} maps to {@link
     *  ItemGenerator.LootCategory#NON_GEM}; other keywords try the
     *  {@link ItemGenerator.LootCategory} enum first, then fall back to a
     *  literal item-type lookup. If {@code spec.plusLevel >= 0} the item's
     *  level is forced to that value after generation. */
    private static Item resolveItem(Level level, CsvTable.DropSpec spec,
                                    double powerLevel, Random rng) {
        String keyword = spec.keyword;
        // STUFF = shorthand for "any non-gem item"
        if ("STUFF".equalsIgnoreCase(keyword)) keyword = "NON_GEM";

        ItemGenerator.LootCategory cat = ItemGenerator.LootCategory.parse(keyword);
        Item it;
        if (cat != null) {
            it = ItemGenerator.generateItem(powerLevel, level.theme, cat, rng);
        } else {
            it = ItemGenerator.buildItem(keyword, powerLevel, rng);
        }

        if (it != null && spec.plusLevel >= 0) {
            it.level = spec.plusLevel;
            if (it.useBehavior == Item.UseBehavior.WAND) it.charge = it.maxCharge();
        }
        return it;
    }

    public static void dropLootOnDeath(Level level, Mob mob) {
        if (level == null || mob == null || mob.position == null) return;
        // The player's death ends the run; their corpse is never inspected so
        // the inventory dump would be wasted work. Skip.
        if (mob.isPlayer) return;
        // Per-instance non-dropping gate (RL-54 renewing enemies): they exist to
        // apply pressure, not to feed the player loot.
        if (mob.suppressLoot) return;
        // Data-driven "drop absolutely nothing" gate: a mobs.csv row with
        // dropQuality=NOTHING_AT_ALL suppresses both the rolled drop table
        // (rollAndStashLoot already skips) AND the inventory dump that would
        // otherwise hand the real player a free kit. Used by ENEMY_PLAYER_*
        // rows so beacon encounter mobs read as a deadly opponent without
        // turning into a piñata on kill.
        MobDefinition def = Registries.mob(mob.mobType);
        if (def != null && dropsNothingAtAll(def)) return;

        Point pos = mob.position;
        List<Item> drops = new ArrayList<>();

        // Drain the mob's inventory + equipment into the drop list. All
        // dropped items were rolled at spawn time via {@link #rollAndStashLoot}
        // (plus anything the mob picked up or had via {@code startingInventory}).
        if (mob.inventory != null) {
            if (mob.inventory.bag != null) {
                drops.addAll(mob.inventory.bag);
                mob.inventory.bag.clear();
            }
            drops.addAll(mob.inventory.allEquipped());
            mob.inventory.weapon = null;
            mob.inventory.offhand = null;
            mob.inventory.armor = null;
            java.util.Arrays.fill(mob.inventory.amulets, null);
            java.util.Arrays.fill(mob.inventory.gems, null);
        }

        // Scatter every drop on a floor tile near the corpse and post a
        // LootDropped event so the renderer can arc the item from corpse
        // to its resting spot.
        scatter(level, pos, drops);
    }

    /** Map an integer mob-level into the 0..1 power-level fraction the
     *  {@link ItemGenerator} expects. Caps at the configured dungeon depth
     *  so over-levelled mobs (e.g. arena bosses) still produce items inside
     *  the catalogued power range. */
    private static double itemLevelToPower(int itemLevel) {
        int total = Math.max(2, GameBalance.DUNGEON_DEPTH);
        double f = (itemLevel - 1) / (double) (total - 1);
        if (f < 0) return 0;
        if (f > 1) return 1;
        return f;
    }

    /** Place each drop on a nearby floor tile and post a LootDropped event so
     *  the rgame Animator can play the corpse-to-landing arc. The radial spread
     *  matches the previous {@code MobSystem.killMob} drop scatter so callers
     *  see no behavioural change at the placement layer. */
    private static void scatter(Level level, Point origin, List<Item> drops) {
        if (drops.isEmpty()) return;
        int cx = origin.tileX(), cy = origin.tileY();
        List<Point> spots = new ArrayList<>();
        for (int r = 0; r <= 5 && spots.size() < drops.size(); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (level.tiles[x][y] != Tile.FLOOR) continue;
                    spots.add(new Point(x, y));
                }
            }
        }
        for (int i = 0; i < drops.size() && i < spots.size(); i++) {
            Item drop = drops.get(i);
            drop.location = spots.get(i);
            level.items.add(drop);
            if (level.events != null) {
                level.events.add(new GameEvent.LootDropped(drop, origin, spots.get(i)));
            }
        }
    }
}
