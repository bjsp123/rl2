package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;

import java.util.List;
import java.util.Random;

/**
 * Brand assignment and on-hit dispatch for item brands.
 *
 * <p>Call {@link #applyRandomBrand(Item, Random)} after generating a new item;
 * call {@link #applyBrandOnHit(Level, Mob, Mob, BrandDefinition)} from
 * {@code MobSystem.attack()} after a melee hit lands.
 */
public final class BrandSystem {

    private BrandSystem() {}

    /** 8-neighbour offsets, cardinals first, for spreading poison-brand clouds. */
    private static final int[][] ADJ8 = {
            {0, 1}, {0, -1}, {1, 0}, {-1, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    // -- Brand assignment ------------------------------------------------------

    /**
     * 1-in-5 chance to assign a random brand to {@code item}. When the roll
     * passes, picks a brand eligible for the item's category, weighted by
     * {@link BrandDefinition#rarity}. No-op for non-brandable categories or
     * when the registry has no eligible brands.
     */
    public static void applyRandomBrand(Item item, Random rng) {
        if (!isBrandable(item)) return;
        if (rng.nextInt(5) != 0) return;
        List<BrandDefinition> pool = Registries.brandsForCategory(item.inventoryCategory);
        if (pool.isEmpty()) return;
        double total = 0;
        for (BrandDefinition b : pool) total += 1.0 / b.rarity;
        double pick = rng.nextDouble() * total;
        for (BrandDefinition b : pool) {
            pick -= 1.0 / b.rarity;
            if (pick <= 0) { item.brand = b; return; }
        }
        item.brand = pool.get(pool.size() - 1);
    }

    public static boolean isBrandable(Item item) {
        if (item == null || item.inventoryCategory == null) return false;
        return switch (item.inventoryCategory) {
            case WEAPON, OFFHAND, ARMOR, AMULET -> true;
            default -> false;
        };
    }

    // -- On-hit dispatch -------------------------------------------------------

    /**
     * Apply the elemental on-hit effect of {@code weapon}'s brand when
     * {@code attacker} lands a melee blow on {@code target}. {@code elementpower}
     * scales with the weapon's effective level (so a +5 FLAME sword ignites
     * harder than a +0 one); other brand fields like CHILLED stack count
     * also pick up the scaled magnitude. No-op for null/non-elemental brands
     * or if target has no position (e.g. already dead and removed).
     */
    public static void applyBrandOnHit(Level level, Mob attacker, Mob target,
                                       Item weapon) {
        if (weapon == null || weapon.brand == null) return;
        BrandDefinition b = weapon.brand;
        if (b.element == null || target.position == null) return;
        int effLvl = ItemStats.effectiveLevel(weapon, attacker);
        int power  = ItemStats.scaleAmount(b.elementpower, effLvl);
        int tx = target.position.tileX(), ty = target.position.tileY();
        switch (b.element) {
            case FIRE -> FireSystem.ignite(level, tx, ty);
            case LIGHTNING -> {
                // Chain lightning from target outward; attacker is excluded
                // from the chain so a brand can never zap its own wielder.
                // The brand fires from {@code weapon} (the weapon swinging
                // the blow) so the attribution log line reads "Warrior's
                // sword does N shock damage to..." rather than naming the
                // wand.
                MinMax dmgRange = new MinMax(1, Math.max(1, power));
                ItemSystem.applyLightningChain(level, attacker, target.position,
                        dmgRange, weapon, attacker);
            }
            case POISONCLOUD -> {
                // Poison cloud on the hit tile, plus one extra cloud on an
                // adjacent tile for every 2 weapon levels - never on the
                // wielder's own tile, never on a wall. Each lasts `power` turns.
                if (tx >= 0 && ty >= 0 && tx < level.width && ty < level.height) {
                    CloudSystem.addCloud(level, tx, ty, Level.Cloud.POISON, power);
                    int extra = effLvl / 2;
                    if (extra > 0 && attacker.position != null) {
                        int ax = attacker.position.tileX(), ay = attacker.position.tileY();
                        int placed = 0;
                        for (int[] d : ADJ8) {
                            if (placed >= extra) break;
                            int nx = tx + d[0], ny = ty + d[1];
                            if (nx == ax && ny == ay) continue;
                            if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                            if (level.tiles[nx][ny].blocksMovement()) continue;
                            CloudSystem.addCloud(level, nx, ny, Level.Cloud.POISON, power);
                            placed++;
                        }
                    }
                    if (level.events != null) {
                        level.events.add(
                                new com.bjsp123.rl2.event.GameEvent.BlastEffect(
                                        target.position));
                    }
                }
            }
            case FREEZE -> {
                // Cold bite (RL-31): the brand now deals COLD damage (x4 vs wet
                // targets, like the freeze bomb), then applies CHILLED. Fired as
                // MAGIC, not MELEE, so it can't re-trigger this brand and recurse.
                MobSystem.processAttack(level, attacker, target,
                        MobSystem.rollRange(new MinMax(1, Math.max(1, power))),
                        MobSystem.AttackType.MAGIC, MobSystem.DamageElement.COLD, null,
                        new MobSystem.DamageCause(attacker, weapon, "freeze"));
                if (target.hp > 0) {
                    BuffSystem.apply(level, target, Buff.BuffType.CHILLED, 8, attacker);
                }
                if (tx >= 0 && ty >= 0 && tx < level.width && ty < level.height
                        && level.surface[tx][ty] == Level.Surface.WATER) {
                    level.surface[tx][ty] = Level.Surface.ICE;
                }
            }
            default -> {}
        }
    }
}
