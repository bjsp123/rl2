package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.MobMemory;
import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Point;

import java.util.Map;

/** Walk to and grab a known-good floor item. Suppressed while enemies are in sight. */
public final class GoalPickupKnown implements Goal {

    public static final GoalPickupKnown INSTANCE = new GoalPickupKnown();

    @Override public String name() { return "PICKUP"; }

    /** Only consider items within this Chebyshev radius. Past this the agent should
     *  EXPLORE its way to them instead of locking PICKUP for the rest of the run on
     *  an item it spotted across the map. */
    private static final int NEARBY_RADIUS = 15;

    @Override public double score(WorldState s) {
        if (!s.visibleEnemies.isEmpty()) return 0.0;
        Point at = mob(s);
        if (at == null) return 0.0;
        double bestValueOverDist = 0.0;
        // At low HP, healing items are worth chasing across the whole map - boost
        // the goal so the agent actively heads for HEALTHPILL / HEALING_POTION
        // entries in memory instead of dying without trying.
        boolean lowHp = s.hpFrac < 0.5;
        Point bestHeal = null;
        int bestHealDist = Integer.MAX_VALUE;
        for (Map.Entry<Point, Item> e : s.memory.knownItems.entrySet()) {
            Item it = e.getValue();
            if (!isWorthPickingUp(s.mob, it)) continue;
            int dist = WorldState.chebyshev(at, e.getKey());
            if (lowHp && isHealingItem(it) && dist < bestHealDist) {
                bestHealDist = dist; bestHeal = e.getKey();
            }
            if (dist > NEARBY_RADIUS) continue;
            if (dist == 0) return 0.5;
            double v = it.getValue() / (double) Math.max(1, dist);
            if (v > bestValueOverDist) bestValueOverDist = v;
        }
        if (bestHeal != null) {
            // Healing dominates other PICKUP candidates when we're hurt, and
            // outranks the SURVIVE-no-threat floor so the agent goes get it.
            return Math.min(0.85, 0.5 + (1.0 - s.hpFrac) * 0.4);
        }
        return Math.min(0.4, bestValueOverDist * 0.02);
    }

    /** True for items that restore HP - drives the low-HP pickup boost. Covers
     *  HEALING_POTION (REGEN buff) and HEALTHPILL (HP_UP powerup). */
    private static boolean isHealingItem(Item it) {
        if (it == null) return false;
        if (it.appliesBuff != null
                && it.appliesBuff.contains(com.bjsp123.rl2.model.Buff.BuffType.REGENERATION)) {
            return true;
        }
        return it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.HP_UP;
    }

    /** Powerups are always worth grabbing (auto-consume on step). Equipment is only
     *  worth grabbing when it's a real upgrade over what's currently equipped -
     *  otherwise the bag fills with junk weapons the agent will never wield.
     *  Consumables (potions / bombs / wands / food / tools) follow the default
     *  value-based filter. */
    private static boolean isWorthPickingUp(com.bjsp123.rl2.model.Mob mob, Item it) {
        if (it == null || it.location == null) return false;
        if (it.getValue() <= 0) return false;
        com.bjsp123.rl2.model.Item.InventoryCategory cat = it.inventoryCategory;
        if (cat == null) return false;
        if (it.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.POWERUP) return true;
        if (cat.isEquipment()) {
            return isEquipmentUpgrade(mob, it);
        }
        return true;
    }

    /** True if {@code it} beats the agent's current best equipped piece in the
     *  same category. Mirrors {@link GoalEquipBetter}'s comparison so floor
     *  pickup and bag-equip decisions agree on what's an upgrade. */
    public static boolean isEquipmentUpgrade(com.bjsp123.rl2.model.Mob mob, Item it) {
        if (mob == null || mob.inventory == null) return true;
        com.bjsp123.rl2.model.Item.InventoryCategory cat = it.inventoryCategory;
        double candScore = com.bjsp123.rl2.ai.eval.ItemEval.equipmentScore(it, mob);
        double best = 0.0;
        int n = com.bjsp123.rl2.model.Inventory.positionCount(cat);
        for (int i = 0; i < n; i++) {
            Item slot = mob.inventory.equipped(cat, i);
            if (slot != null) {
                best = Math.max(best, com.bjsp123.rl2.ai.eval.ItemEval.equipmentScore(slot, mob));
            }
        }
        return candScore > best;
    }

    @Override public boolean isSatisfied(WorldState s) {
        return s.memory.knownItems.isEmpty();
    }

    /** Best known item (highest value / dist). Symmetric with {@link #score}: applies
     *  the same upgrade-only filter and prioritises healing items when HP is low. */
    public static Point bestPickupTarget(WorldState s) {
        Point at = mob(s);
        if (at == null) return null;
        boolean lowHp = s.hpFrac < 0.5;
        Point bestHeal = null;
        int bestHealDist = Integer.MAX_VALUE;
        Point best = null;
        double bestV = 0.0;
        for (Map.Entry<Point, Item> e : s.memory.knownItems.entrySet()) {
            Item it = e.getValue();
            if (!isWorthPickingUp(s.mob, it)) continue;
            int dist = WorldState.chebyshev(at, e.getKey());
            if (lowHp && isHealingItem(it) && dist < bestHealDist) {
                bestHealDist = dist; bestHeal = e.getKey();
            }
            if (dist == 0) return e.getKey();
            double v = it.getValue() / (double) Math.max(1, dist);
            if (v > bestV) { bestV = v; best = e.getKey(); }
        }
        return bestHeal != null ? bestHeal : best;
    }

    @Override public String intentDetail(WorldState s) {
        Point t = bestPickupTarget(s);
        return t == null ? "PICKUP" : "PICKUP @ " + t.tileX() + "," + t.tileY();
    }

    private static Point mob(WorldState s) { return s.mob.position; }
    private static MobMemory mem(WorldState s) { return s.memory; }
}
