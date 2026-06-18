package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Dash toward a distant enemy and slam them. */
public final class ActionCastCharge implements Action {
    public final Item item;
    public final Mob target;

    public ActionCastCharge(Item item, Mob target) {
        this.item = item;
        this.target = target;
    }

    @Override public String name() { return "charge"; }
    @Override public boolean isApplicable(WorldState s) {
        if (item == null || target == null) return false;
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        // Tool's effective dash range - castCharge silently no-ops if the
        // target is out of range, which would leave the agent stuck while
        // charge keeps winning argmax.
        int effLvl = com.bjsp123.rl2.logic.ItemStats.effectiveLevel(item, s.mob);
        int dashRange = Math.max(1, com.bjsp123.rl2.logic.ItemStats.effectiveRange(item, effLvl));
        // Range / LOS / non-ally / alive / not-already-adjacent all in one.
        if (!com.bjsp123.rl2.ai.eval.CombatEval.dashWillLandUsefully(s, target, dashRange)) return false;
        // A charge runs along the floor: an impassable square in the path makes
        // castCharge no-op, so don't let it win argmax in that case.
        return target.position != null
                && com.bjsp123.rl2.logic.MobSystem.chargePathClear(s.level, s.mob.position, target.position);
    }
    @Override public double utility(WorldState s) {
        if (target == null) return 0.0;
        // Charge does a melee-style strike at the end of the dash. Score by the
        // melee damage expected against the target, plus a one-turn-close bonus.
        double dmg = com.bjsp123.rl2.ai.eval.CombatEval.expectedAttackValue(
                s.mob, target, item,
                com.bjsp123.rl2.ai.eval.CombatEval.AttackKind.CHARGE);
        int chebDist = s.mob.position == null || target.position == null
                ? 1 : WorldState.chebyshev(s.mob.position, target.position);
        double closingSaving = 0.08 * Math.max(0, chebDist - 1);
        return Math.min(1.1, 0.7 + dmg / 30.0 + closingSaving);
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.castCharge(level, mob, item, target.position);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "charge " + (target != null && target.name != null ? target.name : target == null ? "?" : target.mobType);
    }
}
