package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.LevelUtilities;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/**
 * Fire a TELEPORT-effect wand (BLINKSTONE) at a threat tile to blink the threat
 * away. Defensive sibling of {@link ActionFireWandAt} - scored higher when the
 * threat is in melee range because it cleanly resolves the immediate danger.
 */
public final class ActionEscapeWand implements Action {
    public final Item item;
    public final Point dest;

    public ActionEscapeWand(Item item, Point dest) {
        this.item = item;
        this.dest = dest;
    }

    @Override public String name() { return "blink"; }
    @Override public boolean isApplicable(WorldState s) {
        if (item == null || dest == null) return false;
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        return LevelUtilities.getLineOfSight(s.level, s.mob, dest);
    }
    @Override public double utility(WorldState s) {
        if (item == null) return 0.0;
        if (item.baseChargeMax > 0 && item.charge < 1f) return 0.0;
        // Top defensive utility when the threat is adjacent or near-adjacent; lower
        // when the threat is far away (less urgent to blink them).
        int dist = s.nearestEnemy != null
                ? WorldState.chebyshev(s.mob.position, s.nearestEnemy.position)
                : Integer.MAX_VALUE;
        if (dist <= 1) return 0.82;
        if (dist <= 3) return 0.7;
        return 0.55;
    }
    @Override public void execute(Mob mob, Level level) {
        ItemSystem.fireWand(level, mob, item, dest);
        TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
    }
    @Override public String intentDetail() {
        return "blink " + (item != null ? item.type : "?");
    }
}
