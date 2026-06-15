package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.logic.MobBrains;
import com.bjsp123.rl2.model.Mob;

/**
 * Wire the SMART brain into rlib's {@link MobBrains} registry. Call once at
 * startup; idempotent.
 *
 * <p>Both the desktop launcher (in-game) and the headless benchmark driver call
 * {@link #init} so rlib's SMART dispatch arm finds the brain when it looks up
 * by {@link Mob.Behavior#SMART}.
 */
public final class RaiBootstrap {

    private static boolean initialised;

    private RaiBootstrap() {}

    public static synchronized void init() {
        if (initialised) return;
        MobBrains.register(Mob.Behavior.SMART, new SmartAi());
        // RL-53: drive the player's auto-explore with the same SMART planner.
        com.bjsp123.rl2.logic.AutoExplore.register(SmartAi::autoExploreStep);
        initialised = true;
    }
}
