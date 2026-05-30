package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.logic.InventorySystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Equip a candidate item from the bag. */
public final class ActionEquip implements Action {
    public final Item item;

    public ActionEquip(Item item) { this.item = item; }

    @Override public String name() { return "equip"; }
    @Override public boolean isApplicable(WorldState s) {
        return item != null && item.inventoryCategory != null
                && item.inventoryCategory.isEquipment()
                && s.mob.inventory != null && s.mob.inventory.bag.contains(item);
    }
    @Override public double utility(WorldState s) {
        return 0.7 + Math.min(0.2, ItemEval.equipmentScore(item, s.mob) / 100.0);
    }
    @Override public void execute(Mob mob, Level level) {
        InventorySystem.equip(mob.inventory, item);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "equip " + (item != null ? item.type : "?");
    }
}
