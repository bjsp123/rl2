package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/** Use a jump tool to teleport to a destination tile in range. */
public final class ActionCastJump implements Action {
    public final Item item;
    public final Point dest;

    public ActionCastJump(Item item, Point dest) {
        this.item = item;
        this.dest = dest;
    }

    @Override public String name() { return "jump"; }
    @Override public boolean isApplicable(WorldState s) {
        return item != null && dest != null;
    }
    @Override public double utility(WorldState s) { return 0.65; }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.castJump(level, mob, item, dest);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() { return "jump"; }
}
