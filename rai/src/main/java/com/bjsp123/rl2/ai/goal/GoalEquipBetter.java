package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;

/** Swap to a better-scoring weapon/armor/amulet/gem if one is sitting in the bag. */
public final class GoalEquipBetter implements Goal {

    public static final GoalEquipBetter INSTANCE = new GoalEquipBetter();

    @Override public String name() { return "EQUIP"; }

    @Override public double score(WorldState s) {
        Item best = bestBagUpgrade(s);
        if (best == null) return 0.0;
        double base = 0.25;
        if (!s.visibleEnemies.isEmpty()) base *= 0.4;
        return Math.min(0.6, base + ItemEval.equipmentScore(best, s.mob) / 200.0);
    }

    @Override public boolean isSatisfied(WorldState s) {
        return bestBagUpgrade(s) == null;
    }

    @Override public String intentDetail(WorldState s) {
        Item best = bestBagUpgrade(s);
        return best == null ? "EQUIP" : "EQUIP " + best.type;
    }

    /** Highest-value bag item that beats the currently equipped piece in its category. */
    public static Item bestBagUpgrade(WorldState s) {
        Inventory inv = s.mob.inventory;
        if (inv == null) return null;
        Item bestCandidate = null;
        double bestDelta = 0.0;
        for (Item it : inv.bag) {
            if (it == null || it.inventoryCategory == null) continue;
            InventoryCategory c = it.inventoryCategory;
            if (!c.isEquipment()) continue;
            double mine = currentBestEquippedInCategory(inv, c, s.mob);
            double cand = ItemEval.equipmentScore(it, s.mob);
            double delta = cand - mine;
            if (delta > bestDelta) { bestDelta = delta; bestCandidate = it; }
        }
        return bestCandidate;
    }

    private static double currentBestEquippedInCategory(
            Inventory inv, InventoryCategory cat, com.bjsp123.rl2.model.Mob wearer) {
        double best = 0.0;
        int n = Inventory.positionCount(cat);
        for (int i = 0; i < n; i++) {
            Item slot = inv.equipped(cat, i);
            if (slot != null) best = Math.max(best, ItemEval.equipmentScore(slot, wearer));
        }
        return best;
    }
}
