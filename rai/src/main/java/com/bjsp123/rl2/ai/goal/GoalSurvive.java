package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.CombatEval;

/**
 * Stay alive in an immediate emergency: low HP combined with an adjacent threat
 * or a visible enemy that could OHKO us next tick. Drives "drink healing potion
 * now" / "flee toward the wall" decisions before they're too late.
 */
public final class GoalSurvive implements Goal {

    public static final GoalSurvive INSTANCE = new GoalSurvive();

    @Override public String name() { return "SURVIVE"; }

    /** Fires only when the agent is hurt AND has a potion that would actually
     *  heal right now. No "manage threats" semantics, no fatigue ramp, no memory
     *  peeking - drink the cure and move on. */
    @Override public double score(WorldState s) {
        if (s.hpFrac >= 0.7) return 0.0;
        if (!hasImmediateHeal(s)) return 0.0;
        return 0.85;
    }

    /** "Has a way to heal" - strictly HP-restoring items only (REGENERATION potions /
     *  HP_UP powerups). Defensive buffs like JADE_FISH→PHASE or JADE_CRAB→SHIELDED
     *  do NOT count as "a way to heal"; SURVIVE must not fire on those (they belong
     *  in the KILL branch via addSelfBuff). Without this gate, a hp=3 rogue with
     *  JADE_FISH in bag locks itself in SURVIVE drinking PHASE forever instead of
     *  EXPLOREing toward heals on the next floor. */
    public static boolean hasImmediateHeal(WorldState s) {
        if (s.mob.inventory == null || s.mob.inventory.bag == null) return false;
        for (com.bjsp123.rl2.model.Item it : s.mob.inventory.bag) {
            if (com.bjsp123.rl2.ai.eval.ItemEval.wouldHealHelp(s.mob, it)) return true;
        }
        return false;
    }

    @Override public boolean isSatisfied(WorldState s) {
        return s.hpFrac >= 0.7 || !hasImmediateHeal(s);
    }

    @Override public String intentDetail(WorldState s) {
        return "SURVIVE @ " + (int)(s.hpFrac * 100) + "% HP";
    }
}
