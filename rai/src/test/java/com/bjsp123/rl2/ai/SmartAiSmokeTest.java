package com.bjsp123.rl2.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.ai.game.AutoplayGame;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.util.ArenaHarness;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke + determinism coverage for the SMART AI brain, driven by the
 * headless {@link AutoplayGame}. Verifies the planner can pilot a real generated
 * world for thousands of ticks without throwing, makes forward progress, and is
 * deterministic for a fixed seed (the property the regression harness leans on).
 */
class SmartAiSmokeTest {

    private static final int WORLD_W = AutoplayGame.worldWidth();
    private static final int WORLD_H = AutoplayGame.worldHeight();

    @BeforeAll
    static void setUp() throws IOException {
        ArenaHarness.loadData(ArenaHarness.locateAssetsDir());
        RaiBootstrap.init(); // register the SMART brain (idempotent)
    }

    // -- happy path ----------------------------------------------------------

    @Test
    void agentPilotsAWorldToATerminalOutcome() {
        AutoplayGame run = AutoplayGame.newRun(12345L, Mob.CharacterClass.WARRIOR, WORLD_W, WORLD_H);
        run.runUntil(3000);
        // After the budget the run must have settled into a terminal state.
        assertNotEquals(AutoplayGame.Outcome.IN_PROGRESS, run.outcome);
        assertTrue(run.turnsElapsed > 0, "agent took no turns");
        assertTrue(run.stats.depthReached >= 0);
        assertNotNull(run.agent);
    }

    @Test
    void everyClassRunsWithoutError() {
        for (Mob.CharacterClass cls : Mob.CharacterClass.values()) {
            AutoplayGame run = AutoplayGame.newRun(777L, cls, WORLD_W, WORLD_H);
            run.runUntil(500); // bounded - just exercise the decision loop per class
            assertTrue(run.turnsElapsed > 0, "no turns for class " + cls);
        }
    }

    // -- determinism ---------------------------------------------------------

    @Test
    void sameSeedProducesIdenticalRuns() {
        AutoplayGame a = AutoplayGame.newRun(2024L, Mob.CharacterClass.ROGUE, WORLD_W, WORLD_H);
        a.runUntil(1200);
        AutoplayGame b = AutoplayGame.newRun(2024L, Mob.CharacterClass.ROGUE, WORLD_W, WORLD_H);
        b.runUntil(1200);
        assertEquals(a.outcome, b.outcome, "outcome diverged for identical seed");
        assertEquals(a.turnsElapsed, b.turnsElapsed, "turn count diverged for identical seed");
        assertEquals(a.stats.depthReached, b.stats.depthReached, "depth diverged for identical seed");
    }

    // -- unhappy / boundary --------------------------------------------------

    @Test
    void zeroTickBudgetTimesOutWithoutCrashing() {
        AutoplayGame run = AutoplayGame.newRun(99L, Mob.CharacterClass.MAGE, WORLD_W, WORLD_H);
        run.runUntil(0); // no ticks at all
        assertEquals(AutoplayGame.Outcome.TIMEOUT, run.outcome);
        assertEquals(0, run.turnsElapsed);
    }
}
