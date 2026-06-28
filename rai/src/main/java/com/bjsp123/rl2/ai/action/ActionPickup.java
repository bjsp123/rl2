package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Pick up whatever is on the mob's current tile. */
public final class ActionPickup implements Action {
    @Override public String name() { return "pickup"; }
    @Override public boolean isApplicable(WorldState s) {
        if (s.level.items == null) return false;
        for (Item it : s.level.items) {
            if (it == null || it.location == null) continue;
            if (it.location.equals(s.mob.position)) return true;
        }
        return false;
    }
    @Override public double utility(WorldState s) { return 0.6; }
    @Override public void execute(Mob mob, Level level) {
        // AI picker: when full, drop the least valuable item to make room.
        MobSystem.pickupAtFeet(level, mob, true);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
}
