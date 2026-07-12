package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** AI turn entry points and iteration policy for mobs. */
public final class MobAi {
    private MobAi() {}

    public static void processAllAiTurns(Level level) {
        processAllAiTurns(level, 0L);
    }

    /**
     * Process ready AI mobs. If {@code deadlineNs} is positive, yields after at least one
     * AI action once that wall-clock deadline has passed. Returns true when all currently
     * ready AI mobs have been handled.
     */
    public static boolean processAllAiTurns(Level level, long deadlineNs) {
        int limit = level.mobs.size();
        for (int i = 0; i < limit; i++) {
            // ANIMATION-GATED LIFECYCLE step 1 ("ticking stops"): if a prior
            // mob in this tick threw / wand-fired / triggered any deferred
            // impact, drain it before the next brain runs so that brain sees
            // post-impact world state. The drain may KILL mobs (a thrown
            // bomb hits the next mob to act), so re-check the size + index
            // after running it.
            if (level.pendingImpacts != null && !level.pendingImpacts.isEmpty()) {
                MobSystem.drainPendingImpactsImmediate(level);
            }
            if (i >= level.mobs.size()) break;
            Mob mob = level.mobs.get(i);
            if (mob.ticksTillMove != 0) continue;
            if (mob.behavior == Mob.Behavior.PLAYER
                    || mob.behavior == Mob.Behavior.INANIMATE) continue;
            if (mob.visibleMobsAtTurnStart == null) {
                MobSystem.snapshotVisibleMobsAtTurnStart(level, mob);
            }
            int before = mob.ticksTillMove;
            MobAiBehavior.processAiTurn(mob, level);
            if (mob.ticksTillMove == before) {
                mob.intent = Mob.Intent.CONSIDERING;
                int guardrailCost = Math.max(TurnSystem.STANDARD_TURN_TICKS + 1,
                        GameBalance.AI_GUARDRAIL_COST);
                TurnSystem.applyMoveCost(mob, guardrailCost);
            }
            if (deadlineNs > 0L && System.nanoTime() >= deadlineNs) return false;
        }
        // Final drain after the last mob - covers the case where the very
        // last AI to act this tick queued an impact.
        if (level.pendingImpacts != null && !level.pendingImpacts.isEmpty()) {
            MobSystem.drainPendingImpactsImmediate(level);
        }
        return true;
    }
}
