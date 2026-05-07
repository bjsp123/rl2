package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Loot pipeline. Loot rolling happens at <em>spawn time</em>
 * ({@link #rollAndStashLoot}) — the mob carries its catalogued drops in its
 * inventory bag from the moment it's created. {@link #dropLootOnDeath} then
 * just empties bag + equipment onto the floor and posts a
 * {@link GameEvent.LootDropped} per item so the renderer can play the
 * arcing-toss animation from corpse to landing tile. Pre-rolling means a
 * dungeon's loot is fully determined at world-build time (deterministic
 * given the seed) and inspecting a sleeping mob's bag mid-run will reveal
 * exactly what they're going to drop.
 *
 * <p>Drop-quality formula (applied during spawn):
 * {@code total = (dropQuality - 1) * 10 + mobLevel}
 * <ul>
 *   <li>Each whole 10 points = roll a new item (level
 *       {@code max(1, mobLevel - 2)}) <em>or</em> bump a previously-rolled
 *       item by +2 levels.</li>
 *   <li>Leftover {@code N} points (0..9) = one item rolled at {@code N × 10%}.</li>
 * </ul>
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
        if (mob.behavior == Mob.Behavior.PLAYER) return;
        MobDefinition def = MobRegistry.get(mob.mobType);
        int dropQuality = def == null ? 1 : Math.max(1, def.dropQuality);
        int mobLevel    = Math.max(1, mob.characterLevel);
        int total       = (dropQuality - 1) * 10 + mobLevel;
        int itemLevel   = Math.max(1, mobLevel - 2);
        double powerLevel = itemLevelToPower(itemLevel);

        List<Item> rolled = new ArrayList<>();
        int chunks   = total / 10;
        int leftover = total % 10;
        for (int i = 0; i < chunks; i++) {
            // First chunk always rolls something so the rest can bump it.
            if (rolled.isEmpty() || rng.nextBoolean()) {
                Item it = ItemGenerator.generateItem(powerLevel, level.theme,
                        ItemGenerator.LootCategory.NON_GEM, rng);
                if (it != null) {
                    rolled.add(it);
                    mob.inventory.bag.add(it);
                }
            } else {
                Item bump = rolled.get(rng.nextInt(rolled.size()));
                bump.level += 2;
            }
        }
        if (leftover > 0 && rng.nextDouble() < leftover / 10.0) {
            Item it = ItemGenerator.generateItem(powerLevel, level.theme,
                    ItemGenerator.LootCategory.NON_GEM, rng);
            if (it != null) mob.inventory.bag.add(it);
        }
    }

    public static void dropLootOnDeath(Level level, Mob mob) {
        if (level == null || mob == null || mob.position == null) return;
        // The player's death ends the run; their corpse is never inspected so
        // the inventory dump would be wasted work. Skip.
        if (mob.behavior == Mob.Behavior.PLAYER) return;

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
