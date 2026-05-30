package com.bjsp123.rl2.ai.goal;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.model.Mob;

/**
 * One candidate objective the SMART AI may pursue this turn. Goals are stateless
 * singletons; per-mob state lives on {@link com.bjsp123.rl2.ai.MobMemory}.
 *
 * <p>Each tick, {@link GoalSelector} asks every goal for a {@link #score} in
 * {@code [0, 1]} given the current {@link WorldState}, picks the highest with
 * hysteresis, and the {@link com.bjsp123.rl2.ai.action.ActionLibrary} enumerates
 * legal actions filtered by the selected goal.
 *
 * <p>{@link #intentDetail} produces the human-readable label the UI shows on the
 * mob this tick.
 */
public interface Goal {
    /** Short identifier used in logs / intent detail. */
    String name();

    /** Score the urgency of this goal under the current state. Higher = more pressing. */
    double score(WorldState state);

    /** Whether the goal is already satisfied (so the selector should pick another). */
    boolean isSatisfied(WorldState state);

    /**
     * Short human-readable annotation set as {@link Mob#intentDetail} when this goal is
     * active. Subclasses may include the chosen target / item for richer detail.
     */
    default String intentDetail(WorldState state) {
        return name();
    }
}
