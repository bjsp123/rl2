package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/** Throw a bomb or projectile at a target tile. ActionLibrary already ally-filters. */
public final class ActionThrowAt implements Action {
    public final Item item;
    public final Point dest;

    public ActionThrowAt(Item item, Point dest) {
        this.item = item;
        this.dest = dest;
    }

    @Override public String name() { return "throw"; }
    @Override public boolean isApplicable(WorldState s) {
        if (item == null || dest == null) return false;
        // Self-blast guard: a TELEPORT-effect throw scatters every non-thrower
        // mob inside the impact disc across random levels. The thrower is
        // excluded by rlib, but we still refuse to throw if the disc covers
        // the agent's own tile - this keeps SMART from voluntarily building
        // a turn around an orb that would also scramble allies / itself if
        // the exclusion rule ever changes.
        if (item.throwEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT
                && s.mob != null && s.mob.position != null) {
            int radius = com.bjsp123.rl2.logic.ItemStats.effectiveSize(item);
            int dx = s.mob.position.tileX() - dest.tileX();
            int dy = s.mob.position.tileY() - dest.tileY();
            if (dx * dx + dy * dy <= radius * radius) return false;
        }
        // Predict the actual impact disc and reject if no damageable hostile
        // sits in it. Eliminates "bomb explodes on a wall short of target",
        // "fire bomb at fire-immune mob", "splash hits only allies", etc.
        return com.bjsp123.rl2.ai.eval.CombatEval.throwWillLandUsefully(s, item, dest);
    }
    @Override public double utility(WorldState s) {
        if (item == null || item.damage <= 0) return 0.0;
        if (s.nearestEnemy == null) return 0.0;
        // At point-blank prefer melee - bombs may friendly-fire / miss high-evasion.
        int d = WorldState.chebyshev(s.mob.position, s.nearestEnemy.position);
        if (d <= 1) return 0.3;
        // Stalemate-aware damping (kept so we stop spamming bombs at a kiter).
        double stalemateDamp = 0.0;
        if (s.memory != null && s.memory.stalemateTurns > 4) {
            stalemateDamp = Math.min(0.3, (s.memory.stalemateTurns - 4) * 0.05);
        }
        // Expected damage against the target - direct + secondary buff / cloud
        // effects, with target armor / immunity / fire-immunity folded in.
        double dmg = com.bjsp123.rl2.ai.eval.CombatEval.expectedAttackValue(
                s.mob, s.nearestEnemy, item,
                com.bjsp123.rl2.ai.eval.CombatEval.AttackKind.THROW);
        // d==2 used to flat-score 0.45 which lost to walk-to-melee (0.58) every
        // time, so the agent committed to melee even with a damaging bomb in
        // hand. Score it the same way as the d>2 case minus a small
        // close-quarters penalty - keeps melee competitive against weak foes
        // but lets a heavy bomb win against an armored / dodgy / dangerous one.
        double base = d == 2 ? 0.55 : 0.6;
        return Math.max(0.2, Math.min(1.0, base + dmg / 30.0 - stalemateDamp));
    }
    @Override public void execute(Mob mob, Level level) {
        MobSystem.throwItem(level, mob, item, dest);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "throw " + (item != null ? item.type : "?");
    }
}
