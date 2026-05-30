package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Drink a beneficial potion from the bag. */
public final class ActionDrinkPotion implements Action {
    public final Item item;

    public ActionDrinkPotion(Item item) { this.item = item; }

    @Override public String name() { return "drink"; }
    @Override public boolean isApplicable(WorldState s) {
        return ItemEval.wouldDrinkHelp(s.mob, item);
    }
    @Override public double utility(WorldState s) {
        if (item == null) return 0.0;
        if (ItemEval.wouldHealHelp(s.mob, item)) return 0.85;
        com.bjsp123.rl2.model.Buff.BuffType primary = item.primaryBuff();
        if (primary == null || primary == com.bjsp123.rl2.model.Buff.BuffType.REGENERATION) {
            return 0.5;
        }
        // Combat-relevant defensive/offensive buffs get top utility so the mage's
        // JADE_CRAB / JADE_FISH / SORCERY etc. actually fire in combat. Pure-utility
        // buffs (INSIGHT, ESP) score low so they don't displace wand / throw / melee
        // turns during a fight.
        switch (primary) {
            case SHIELDED:
            case PHASE:
            case HASTED:
            case SORCERY:
                return 0.8;
            case INVISIBLE:
                // Useful when retreating or evading; under KILL it costs us our own
                // target lock, so drop the priority below ranged attacks.
                return 0.45;
            case INSIGHT:
            case ESP:
            default:
                return 0.3;
        }
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.useItem(level, mob, item);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "drink " + (item != null ? item.type : "potion");
    }
}
