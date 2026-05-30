package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.LevelUtilities;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/** Fire an offensive wand at a target tile. */
public final class ActionFireWandAt implements Action {
    public final Item item;
    public final Point dest;

    public ActionFireWandAt(Item item, Point dest) {
        this.item = item;
        this.dest = dest;
    }

    @Override public String name() { return "wand"; }
    @Override public boolean isApplicable(WorldState s) {
        if (item == null || dest == null) return false;
        if (!ItemEval.isFireableWand(item)) return false;
        // Hard zero on depleted charges - belt-and-suspenders with isFireableWand.
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        if (!LevelUtilities.getLineOfSight(s.level, s.mob, dest)) return false;
        // Predict the actual impact tile after trajectory clipping and reject
        // if no damageable hostile sits there. Eliminates wand fizzles where
        // the missile clips on a wall short of the target, or where the
        // target is immune (e.g. fire wand vs fire-immune mob).
        return com.bjsp123.rl2.ai.eval.CombatEval.wandWillLandUsefully(s, item, dest);
    }
    @Override public double utility(WorldState s) {
        if (item == null) return 0.0;
        if (item.baseChargeMax > 0 && item.charge < 1f) return 0.0;
        // At adjacent range melee wins clearly; don't pre-empt with a wand.
        if (s.nearestEnemy != null && s.isAdjacent(s.nearestEnemy)) return 0.35;
        if (s.nearestEnemy == null) return 0.0;
        // Expected damage against this specific target - armor / magicResist /
        // fire immunity / brand effects all folded into expectedAttackValue.
        double dmg = com.bjsp123.rl2.ai.eval.CombatEval.expectedAttackValue(
                s.mob, s.nearestEnemy, item,
                com.bjsp123.rl2.ai.eval.CombatEval.AttackKind.WAND);
        return Math.min(1.05, 0.6 + dmg / 25.0);
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.fireWand(level, mob, item, dest);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "wand " + (item != null ? item.type : "?");
    }
}
