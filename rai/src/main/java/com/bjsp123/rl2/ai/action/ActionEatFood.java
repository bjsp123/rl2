package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Eat a food item to restore satiety. */
public final class ActionEatFood implements Action {
    public final Item item;

    public ActionEatFood(Item item) { this.item = item; }

    @Override public String name() { return "eat"; }
    @Override public boolean isApplicable(WorldState s) {
        return ItemEval.isUsefulFood(s.mob, item, s.satietyFrac);
    }
    @Override public double utility(WorldState s) {
        return Math.min(0.95, 1.0 - s.satietyFrac);
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.eat(level, mob, item);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "eat " + (item != null ? item.type : "food");
    }
}
