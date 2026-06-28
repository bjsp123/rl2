package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.model.Item;

/**
 * Equipment-upgrade test for the live decision tree. Formerly a scored Goal; now
 * only {@link #isEquipmentUpgrade} survives, called by
 * {@link com.bjsp123.rl2.ai.action.ActionLibrary}'s pickup filter.
 */
public final class GoalPickupKnown {

    private GoalPickupKnown() {}

    /** True if {@code it} beats the agent's current best equipped piece in the
     *  same category, so floor pickup and bag-equip decisions agree on what's an
     *  upgrade. */
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
}
