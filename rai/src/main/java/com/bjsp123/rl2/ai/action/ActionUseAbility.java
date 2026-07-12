package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.MobAiBehavior;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/**
 * Delegate a species ability cast (heal / haste / teleport) to rlib's existing
 * support-ability path. Applicable whenever the mob has any ability; on execute
 * it delegates to {@link MobAiBehavior#tryCastAbilities} and, if that fails (all on
 * cooldown / no valid target), spends a move-cost no-op.
 *
 * <p>Treated as a "free try" with low base utility so it only wins when no other
 * action scores high. {@link MobAiBehavior#tryCastAbilities} handles cost-charging
 * internally on success.
 */
public final class ActionUseAbility implements Action {
    @Override public String name() { return "ability"; }
    @Override public boolean isApplicable(WorldState s) {
        return s.mob.abilities != null && !s.mob.abilities.isEmpty();
    }
    @Override public double utility(WorldState s) { return 0.55; }
    @Override public void execute(Mob mob, Level level) {
        if (!MobAiBehavior.tryCastAbilities(mob, level)) {
            com.bjsp123.rl2.logic.TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
        }
    }
}
