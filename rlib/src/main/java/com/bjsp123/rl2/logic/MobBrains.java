package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of {@link MobBrain} implementations keyed by {@link Mob.Behavior}. Lets
 * rlib stay independent of brain code: external modules register at startup, rlib
 * looks up by enum value, and the lookup returns {@code null} when no brain is
 * present so callers can fall back to a built-in behaviour.
 */
public final class MobBrains {

    private static final Map<Mob.Behavior, MobBrain> registry = new EnumMap<>(Mob.Behavior.class);

    private MobBrains() {}

    public static void register(Mob.Behavior behavior, MobBrain brain) {
        registry.put(behavior, brain);
    }

    /** @return the registered brain for {@code behavior}, or {@code null} if none. */
    public static MobBrain get(Mob.Behavior behavior) {
        return registry.get(behavior);
    }
}
