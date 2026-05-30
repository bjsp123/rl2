package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/**
 * No-op fallback: charge a move-cost tick and do nothing. Last-resort when no
 * other action is applicable, also used by HEAL_IN_SAFETY to rest until full.
 */
public final class ActionWait implements Action {
    @Override public String name() { return "wait"; }
    @Override public boolean isApplicable(WorldState s) { return true; }
    @Override public double utility(WorldState s) { return 0.05; }
    @Override public void execute(Mob mob, Level level) {
        TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
    }
}
