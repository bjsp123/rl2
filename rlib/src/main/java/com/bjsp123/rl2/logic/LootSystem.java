package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.event.GameEvent;
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
 * ({@link #rollAndStashLoot}) - the mob carries its catalogued drops in its
 * inventory bag from the moment it's created. {@link #dropLootOnDeath} then
 * just empties bag + equipment onto the floor and posts a
 * {@link GameEvent.LootDropped} per item so the renderer can play the
 * arcing-toss animation from corpse to landing tile. Pre-rolling means a
 * dungeon's loot is fully determined at world-build time (deterministic
 * given the seed) and inspecting a sleeping mob's bag mid-run will reveal
 * exactly what they're going to drop.
 *
 * <p>Drops are declared via two CSV columns: {@code dropType} (a {@code |}-separated
 * list of {@link ItemGenerator} drop categories - EQUIPMENT, MAGIC_ITEMS, FOOD,
 * POTION, GEM, SPECIAL_GEM, POWERUPS, BOMBS, or the {@code NOTHING_AT_ALL} marker)
 * and {@code dropAmount} (total item count; integer part guaranteed, fractional part
 * = chance of one bonus). Each rolled item independently picks one type from the
 * list, and items roll as if at {@code depth + floor(dropAmount)}.
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
        if (def == null || def.dropTypes == null || def.dropTypes.isEmpty()) return;
        // NOTHING_AT_ALL is a control marker, not a rollable type.
        if (def.dropTypes.size() == 1
                && "NOTHING_AT_ALL".equalsIgnoreCase(def.dropTypes.get(0))) return;

        // Items roll as if for the current dungeon depth plus one level per whole
        // unit of dropAmount: a more generous drop is also deeper-quality loot.
        // (Powerups / gems carry no level, so depth has no effect on them.)
        int depthBonus = (int) Math.floor(Math.max(0, def.dropAmount));
        double powerLevel = itemLevelToPower(Math.max(1, level.depth) + depthBonus);

        // Integer part = guaranteed count; fractional part = probability of one
        // bonus. LOOT_DROP_FREQUENCY_COEFF scales the whole quantity globally.
        double scaledCount = def.dropAmount * GameBalance.LOOT_DROP_FREQUENCY_COEFF;
        int n = (int) Math.floor(scaledCount);
        double frac = scaledCount - n;
        if (frac > 0 && rng.nextDouble() < frac) n++;

        for (int i = 0; i < n; i++) {
            // Each dropped item independently picks one of the declared types.
            String type = def.dropTypes.get(rng.nextInt(def.dropTypes.size()));
            // Powerups come as a pair per pick - individual pills are minor.
            int copies = "POWERUPS".equalsIgnoreCase(type) ? GameBalance.POWERUPS_PER_DROP : 1;
            for (int c = 0; c < copies; c++) {
                Item it = ItemGenerator.generateDrop(type, powerLevel, level.theme, rng);
                if (it == null) continue;
                // RL-36 mirror: a non-player mob must never carry the teleport orb.
                if (it.scattersOnThrow()) continue;
                mob.inventory.bag.add(it);
            }
        }
    }

    /** True when the mob definition declares {@code dropQuality=NOTHING_AT_ALL}
     *  (case-insensitive). Used by {@link #dropLootOnDeath} to suppress the
     *  inventory dump on kill - the mob's gear vanishes with it. Distinct
     *  from a plain {@code NONE} entry, which only skips the rolled drop
     *  table but still drops what the mob was carrying. */
    private static boolean dropsNothingAtAll(MobDefinition def) {
        if (def == null || def.dropTypes == null) return false;
        for (String type : def.dropTypes) {
            if ("NOTHING_AT_ALL".equalsIgnoreCase(type)) return true;
        }
        return false;
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
