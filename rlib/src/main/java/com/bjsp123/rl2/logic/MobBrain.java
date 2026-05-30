package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

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
public interface MobBrain {
    void run(Mob mob, Level level);
}
