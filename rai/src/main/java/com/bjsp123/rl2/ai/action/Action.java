package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/**
 * One concrete operation the SMART AI may execute this tick. Implementations
 * delegate to existing rlib system calls (MobSystem, ItemSystem, InventorySystem,
 * LevelSystem) and charge their own action/move cost via {@link com.bjsp123.rl2.logic.TurnSystem}.
 *
 * <p>Actions are produced fresh by {@link ActionLibrary}'s {@code add*}
 * enumerators each tick.
 *
 * <p>{@link #utility} scores how well the action advances the current branch's
 * intent - {@link com.bjsp123.rl2.ai.Decider} picks {@code argmax}.
 */
public interface Action {
    /** Short identifier used in logs / intent detail. */
    String name();

    /** Whether the action can be executed right now (preconditions met). */
    boolean isApplicable(WorldState state);

    /** Goal-relative score. Higher = better choice. */
    double utility(WorldState state);

    /** Execute against the live level. Caller has already verified applicability. */
    void execute(Mob mob, Level level);

    /** Short label for {@link Mob#intentDetail}, e.g. {@code "melee Kobold"}. */
    default String intentDetail() {
        return name();
    }
}
