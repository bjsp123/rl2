package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.model.Buff;

/** Eat to keep satiety up; spikes when STARVING is active. */
public final class GoalEat implements Goal {

    public static final GoalEat INSTANCE = new GoalEat();

    @Override public String name() { return "EAT"; }

    @Override public double score(WorldState s) {
        if (BuffSystem.hasBuff(s.mob, Buff.BuffType.STARVING)) return 0.95;
        if (s.satietyFrac >= 0.65) return 0.0;
        return Math.min(0.7, 1.0 - s.satietyFrac);
    }

    @Override public boolean isSatisfied(WorldState s) {
        // Satiated -> done.
        if (s.satietyFrac >= 0.9) return true;
        // No food in the bag -> nothing to do for this goal. The selector should
        // fall through to EXPLORE / PICKUP / DESCEND to go find some. Without
        // this gate the agent locks into EAT/wait for the rest of the game once
        // satiety drops, because a hungry mob with no food in inventory can't
        // act on the goal.
        return !hasFoodInBag(s);
    }

    private static boolean hasFoodInBag(WorldState s) {
        if (s.mob.inventory == null || s.mob.inventory.bag == null) return false;
        for (com.bjsp123.rl2.model.Item it : s.mob.inventory.bag) {
            if (it != null
                    && it.inventoryCategory == com.bjsp123.rl2.model.Item.InventoryCategory.FOOD) {
                return true;
            }
        }
        return false;
    }

    @Override public String intentDetail(WorldState s) {
        return "EAT @ " + (int)(s.satietyFrac * 100) + "%";
    }
}
