package com.bjsp123.rl2.ai.eval;

import com.bjsp123.rl2.ai.goal.Goal;
import com.bjsp123.rl2.model.Mob;

/**
 * Maps a chosen {@link Goal} to the closest {@link Mob.Intent} enum value so the
 * existing renderer (which reads {@code mob.intent}) continues to show a sensible
 * label even before it learns to read {@code mob.intentDetail}.
 *
 * <p>Goals own the human-readable detail string via {@link Goal#intentDetail};
 * this class only picks the structural enum bucket.
 */
public final class IntentMapping {

    private IntentMapping() {}

    public static Mob.Intent forGoal(Goal goal) {
        if (goal == null) return Mob.Intent.IDLE;
        return switch (goal.name()) {
            case "SURVIVE", "FLEE" -> Mob.Intent.FLEEING;
            case "KILL"            -> Mob.Intent.PURSUING;
            case "HEAL", "EAT"     -> Mob.Intent.USING_ITEM;
            case "USE_ABILITY"     -> Mob.Intent.USING_ABILITY;
            case "EQUIP", "PICKUP", "EXPLORE", "BEACON", "DESCEND" -> Mob.Intent.WANDERING;
            default -> Mob.Intent.CONSIDERING;
        };
    }
}
