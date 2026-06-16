package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/**
 * Registry for the auto-explore driver (RL-53). Mirrors {@link MobBrains}: the
 * SMART planner lives in the {@code rai} module, which registers a {@link Driver}
 * at startup, so rlib/rgame can drive the player one exploration step at a time
 * without depending on AI code.
 *
 * <p>The driver runs the SMART planner on the player mob WITHOUT changing the
 * player's {@link Mob#behavior} (it stays {@code PLAYER}, so turn detection and
 * field-of-view keep working). It executes only explore / pickup actions and
 * reports {@link Result#DONE} the moment the planner would do anything else
 * (descend, fight, flee, heal) so control hands back to the player.
 */
public final class AutoExplore {

    public enum Result {
        /** An explore/pickup action was taken this call (the player's turn advanced). */
        STEPPED,
        /** No reachable unexplored floor remains - the level is fully explored. The
         *  caller should report "nothing left to explore". */
        DONE_EXPLORED,
        /** Auto-explore handed back for a reason other than completion - the planner
         *  wants to do something the player should decide (heal, fight, flee, ...).
         *  The caller should stop quietly rather than claim the level is explored. */
        DONE_BUSY
    }

    public interface Driver {
        Result step(Mob player, Level level);
    }

    private static Driver driver;

    private AutoExplore() {}

    public static void register(Driver d) { driver = d; }

    /** @return the registered driver, or {@code null} if none (rai not bootstrapped). */
    public static Driver get() { return driver; }
}
