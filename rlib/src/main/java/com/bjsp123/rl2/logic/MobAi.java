package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

import java.util.ArrayList;
import java.util.List;

/** AI turn entry points and iteration policy for mobs. */
public final class MobAi {
    private MobAi() {}

    public static void processAllAiTurns(Level level) {
        List<Mob> snapshot = new ArrayList<>(level.mobs);
        for (Mob mob : snapshot) {
            if (mob.ticksTillMove != 0) continue;
            if (mob.behavior == Mob.Behavior.PLAYER
                    || mob.behavior == Mob.Behavior.INANIMATE) continue;
            MobSystem.processAiTurn(mob, level);
        }
    }
}
