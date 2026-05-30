package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Yank a distant enemy adjacent with a grappling hook. */
public final class ActionCastGrapple implements Action {
    public final Item item;
    public final Mob target;

    public ActionCastGrapple(Item item, Mob target) {
        this.item = item;
        this.target = target;
    }

    @Override public String name() { return "grapple"; }
    @Override public boolean isApplicable(WorldState s) {
        if (item == null) return false;
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        // Size / range / LOS / non-ally / alive consolidated into the helper.
        // Grapple silently flashes-and-fades if the target's size exceeds the
        // tool's effective power; the helper enforces that gate so the
        // planner doesn't lock onto grappling an oversized mob it can never
        // pull.
        return com.bjsp123.rl2.ai.eval.CombatEval.grappleWillLandUsefully(s, item, target);
    }
    @Override public double utility(WorldState s) {
        if (target == null) return 0.0;
        // Grapple yanks the target adjacent so we can swing next turn.
        // Score by (a) the time saved closing (vs walking), and (b) the
        // expected melee damage we will inflict when adjacent.
        int chebDist = s.mob.position == null || target.position == null
                ? 1 : WorldState.chebyshev(s.mob.position, target.position);
        double closingSaving = 0.08 * Math.max(0, chebDist - 1);
        double meleeFollowup = com.bjsp123.rl2.ai.eval.CombatEval.expectedAttackValue(
                s.mob, target, null,
                com.bjsp123.rl2.ai.eval.CombatEval.AttackKind.MELEE);
        return Math.min(1.05, 0.6 + closingSaving + meleeFollowup / 30.0);
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.castGrapple(level, mob, item, target.position);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "grapple " + (target != null && target.name != null ? target.name : target == null ? "?" : target.mobType);
    }
}
