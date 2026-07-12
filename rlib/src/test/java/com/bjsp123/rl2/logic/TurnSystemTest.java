package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.util.ArenaHarness;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the {@link TurnSystem} game-clock contract, which was
 * previously covered only transitively through the rai autoplay smoke test:
 * the player-ready early return, the one-tick drain, the standard-turn
 * heartbeat cadence, {@code advanceToNextEvent}'s bulk-advance arithmetic,
 * cost floors, and the INANIMATE / FROZEN exemptions.
 */
class TurnSystemTest extends DataFixture {

    private static final Point PLAYER_AT = new Point(3, 8);
    private static final Point MOB_AT    = new Point(10, 8);

    /** Arena with a player and one sleeping NPC (asleep = brain does nothing,
     *  so the clock arithmetic is observable without combat noise). */
    private static Level arena(Mob player, Mob npc) {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(11));
        CombatArena.placeMobs(level, List.of(player, npc), List.of(PLAYER_AT, MOB_AT));
        return level;
    }

    private static Mob anyNpc() {
        for (String type : ArenaHarness.fightableMobs(false)) {
            Mob m = MobFactory.spawn(type, MOB_AT);
            if (m != null) return m;
        }
        return null;
    }

    @Test
    void readyPlayerBlocksTheClock() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 0;
        npc.ticksTillMove = 7;
        int accBefore = level.standardTurnTickAcc;

        assertFalse(TurnSystem.tick(level), "ready player must block the tick");
        assertEquals(7, npc.ticksTillMove, "no mob may drain while waiting for input");
        assertEquals(accBefore, level.standardTurnTickAcc);
        assertEquals(0, TurnSystem.advanceToNextEvent(level),
                "advanceToNextEvent must also wait for input");
    }

    @Test
    void tickDrainsExactlyOneTick() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 5;
        npc.ticksTillMove = 7;

        assertTrue(TurnSystem.tick(level));
        assertEquals(4, player.ticksTillMove);
        assertEquals(6, npc.ticksTillMove);
    }

    @Test
    void standardTurnFiresEveryHundredTicks() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 1000;   // billed far ahead - never becomes ready
        npc.ticksTillMove = 1000;
        level.standardTurnTickAcc = 0;
        int turnsBefore = level.turnsOnLevel;

        for (int i = 0; i < TurnSystem.STANDARD_TURN_TICKS - 1; i++) {
            assertTrue(TurnSystem.tick(level));
        }
        assertEquals(turnsBefore, level.turnsOnLevel,
                "heartbeat must not fire before the 100th tick");
        assertTrue(TurnSystem.tick(level));
        assertEquals(turnsBefore + 1, level.turnsOnLevel,
                "heartbeat must fire exactly on the 100th tick");
        assertEquals(0, level.standardTurnTickAcc);
    }

    @Test
    void advanceToNextEventJumpsToTheNextActor() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 250;
        npc.ticksTillMove = 30;
        level.standardTurnTickAcc = 0;

        assertEquals(30, TurnSystem.advanceToNextEvent(level),
                "bulk advance must stop at the earliest pending actor");
        // The npc reached 0 and its brain ran inside the same call, re-billing
        // its next action - so only the player's drain is directly observable.
        assertEquals(220, player.ticksTillMove);
        assertEquals(30, level.standardTurnTickAcc);
    }

    @Test
    void advanceToNextEventCapsAtTheStandardTurnBoundary() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 500;
        npc.ticksTillMove = 400;
        level.standardTurnTickAcc = 60;
        int turnsBefore = level.turnsOnLevel;

        assertEquals(TurnSystem.STANDARD_TURN_TICKS - 60,
                TurnSystem.advanceToNextEvent(level),
                "bulk advance must stop at the standard-turn heartbeat");
        assertEquals(turnsBefore + 1, level.turnsOnLevel);
    }

    @Test
    void advanceToNextEventReportsReadyAiWithoutAdvancing() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 250;
        npc.ticksTillMove = 0;   // AI already ready: same-tick work remains

        assertEquals(-1, TurnSystem.advanceToNextEvent(level),
                "ready AI must be processed without advancing the clock");
        assertEquals(250, player.ticksTillMove, "clock must not advance");
    }

    @Test
    void costsHaveAFloorOfOneTick() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        player.ticksTillMove = 0;
        TurnSystem.applyMoveCost(player, 0);
        assertEquals(1, player.ticksTillMove, "zero cost must still bill 1 tick");
        TurnSystem.applyActionCost(player, -5);
        assertEquals(2, player.ticksTillMove, "negative cost must still bill 1 tick");
    }

    @Test
    void inanimateMobsNeverDrain() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 500;
        npc.behavior = Mob.Behavior.INANIMATE;
        npc.ticksTillMove = 42;

        for (int i = 0; i < 50; i++) TurnSystem.tick(level);
        assertEquals(42, npc.ticksTillMove, "INANIMATE mobs are outside the clock");
    }

    @Test
    void frozenReadyPlayerDoesNotGetATurn() {
        Mob player = MobFactory.player(PLAYER_AT, Mob.CharacterClass.WARRIOR);
        Mob npc = anyNpc();
        assertNotNull(npc);
        Level level = arena(player, npc);
        player.ticksTillMove = 0;
        npc.ticksTillMove = 20;
        BuffSystem.apply(level, player, Buff.BuffType.FROZEN, 3, null);

        assertFalse(TurnSystem.isPlayerTurn(level),
                "a FROZEN player is not awaiting input");
        assertTrue(TurnSystem.tick(level),
                "the clock must keep running past a frozen player");
        assertTrue(player.ticksTillMove > 0,
                "billFrozenReadyMobs must re-bill the frozen player's move cost");
    }
}
