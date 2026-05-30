package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Tile;

/** Descend if standing on STAIRS_DOWN. Pure pass-through to LevelSystem.descendStairs. */
public final class ActionDescendStairs implements Action {
    @Override public String name() { return "descend"; }
    @Override public boolean isApplicable(WorldState s) {
        return s.level.tiles[s.mob.position.tileX()][s.mob.position.tileY()] == Tile.STAIRS_DOWN
                && s.level.world != null;
    }
    @Override public double utility(WorldState s) { return 0.9; }
    @Override public void execute(Mob mob, Level level) {
        if (!LevelSystem.descendStairs(level.world, mob)) {
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
        }
    }
    @Override public String intentDetail() { return "descend stairs"; }
}
