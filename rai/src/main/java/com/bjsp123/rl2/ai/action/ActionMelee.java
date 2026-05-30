package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.CombatEval;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Hit an adjacent hostile in melee. Scored by expected damage vs. the target. */
public final class ActionMelee implements Action {
    public final Mob target;

    public ActionMelee(Mob target) { this.target = target; }

    @Override public String name() { return "melee"; }

    @Override public boolean isApplicable(WorldState s) {
        return target != null && target.hp > 0 && target.position != null
                && WorldState.chebyshev(s.mob.position, target.position) == 1;
    }

    @Override public double utility(WorldState s) {
        if (target == null || target.hp <= 0) return 0.0;
        // Expected damage uses attacker's full melee range (mob + weapon) plus
        // weapon brand effects, minus target armor / immunities.
        double dmg = CombatEval.expectedAttackValue(s.mob, target, null,
                CombatEval.AttackKind.MELEE);
        double finisherBonus = dmg >= target.hp ? 0.4 : 0.0;
        double maxHp = Math.max(1.0, target.effectiveStats().maxHp);
        double hpFrac = target.hp / maxHp;
        double woundedBonus = hpFrac < 0.4 ? (0.4 - hpFrac) * 0.75 : 0.0;
        return 0.7 + finisherBonus + woundedBonus
                + Math.min(0.3, dmg / Math.max(1.0, target.hp));
    }

    @Override public void execute(Mob mob, Level level) {
        MobSystem.attack(level, mob, target);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }

    @Override public String intentDetail() {
        return "melee " + (target != null && target.name != null ? target.name : target == null ? "?" : target.mobType);
    }
}
