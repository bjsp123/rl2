package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Eat a regen-granting food item (e.g. a healing pear) to restore HP when hurt. Food's
 *  only mechanical effect now is its on-eat buff, so this fires only for food that applies
 *  REGENERATION and only while {@link ItemEval#wouldHealHelp} says the eater is low enough
 *  to want it. */
public final class ActionEatFood implements Action {
    public final Item item;

    public ActionEatFood(Item item) { this.item = item; }

    @Override public String name() { return "eat"; }
    @Override public boolean isApplicable(WorldState s) {
        return ItemEval.wouldHealHelp(s.mob, item);
    }
    @Override public double utility(WorldState s) {
        // Just below a dedicated healing potion (0.85) so the agent prefers a potion
        // when it has one, but still eats a regen pear if that's its heal on hand.
        return 0.8;
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.eat(level, mob, item);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "eat " + (item != null ? item.type : "food");
    }
}
