package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** AI turn entry points and iteration policy for mobs. */
public final class MobAi {
    private MobAi() {}

    public static void processAllAiTurns(Level level) {
        int limit = level.mobs.size();
        for (int i = 0; i < limit; i++) {
            if (i >= level.mobs.size()) break;
            Mob mob = level.mobs.get(i);
            if (mob.ticksTillMove != 0) continue;
            if (mob.behavior == Mob.Behavior.PLAYER
                    || mob.behavior == Mob.Behavior.INANIMATE) continue;
            if (mob.visibleMobsAtTurnStart == null) {
                MobSystem.snapshotVisibleMobsAtTurnStart(level, mob);
            }
            int before = mob.ticksTillMove;
            MobSystem.processAiTurn(mob, level);
            if (mob.ticksTillMove == before) {
                mob.intent = Mob.Intent.CONSIDERING;
                int guardrailCost = Math.max(TurnSystem.STANDARD_TURN_TICKS + 1,
                        GameBalance.AI_GUARDRAIL_COST);
                TurnSystem.applyMoveCost(mob, guardrailCost);
            }
        }
    }
}
