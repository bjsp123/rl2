package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of {@link Brain} implementations keyed by {@link Mob.Behavior}. Lets
 * rlib stay independent of brain code: external modules register at startup, rlib
 * looks up by enum value, and the lookup returns {@code null} when no brain is
 * present so callers can fall back to a built-in behaviour.
 */
public final class MobBrains {

    /**
     * Pluggable mob decision routine. Implementations live outside rlib (e.g. in the
     * {@code rai} module) and register themselves through {@link MobBrains}, so rlib
     * can dispatch to them without depending on the implementing module.
     *
     * <p>A brain runs once per mob per tick when {@link MobSystem#processAiTurn} hits a
     * matching {@link Mob.Behavior}. It mutates the level directly and is responsible
     * for charging the mob's action cost via {@link TurnSystem#applyMoveCost} or an
     * equivalent system call.
     */
    public interface Brain {
        void run(Mob mob, Level level);
    }

    private static final Map<Mob.Behavior, Brain> registry = new EnumMap<>(Mob.Behavior.class);

    private MobBrains() {}

    public static void register(Mob.Behavior behavior, Brain brain) {
        registry.put(behavior, brain);
    }

    /** @return the registered brain for {@code behavior}, or {@code null} if none. */
    public static Brain get(Mob.Behavior behavior) {
        return registry.get(behavior);
    }
}
