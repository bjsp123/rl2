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
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.TileQuery;
import com.bjsp123.rl2.util.ArenaHarness;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * GHOSTLY buff contract: +100 evasion, flight, free passage through solid
 * terrain and other mobs while active; on expiry inside something solid the
 * mob snaps to the nearest legal tile (MobSystem.resolveGhostlyEnd).
 */
class GhostlyBuffTest extends DataFixture {

    private static Mob anyMob(Point at) {
        for (String type : ArenaHarness.fightableMobs(false)) {
            Mob m = MobFactory.spawn(type, at);
            if (m != null) return m;
        }
        return null;
    }

    @Test
    void ghostlyGrantsEvasionFlightAndPassage() {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(11));
        level.tiles[8][8] = Tile.WALL;
        Mob ghost = anyMob(new Point(7, 8));
        Mob other = anyMob(new Point(9, 8));
        assertNotNull(ghost);
        assertNotNull(other);
        CombatArena.placeMobs(level, List.of(ghost, other),
                List.of(new Point(7, 8), new Point(9, 8)));

        int evasionBefore = ghost.effectiveStats().evasion;
        assertTrue(TileQuery.blocksMovementAt(level, 8, 8, ghost),
                "wall should block a solid mob");
        assertTrue(MobQueries.blocksMovement(level, ghost, new Point(9, 8)),
                "occupied tile should block a solid mob");

        BuffSystem.apply(level, ghost, Buff.BuffType.GHOSTLY, 5, null);
        ghost.statsDirty = true;

        assertTrue(ghost.isGhostly());
        assertEquals(evasionBefore + 100, ghost.effectiveStats().evasion,
                "ghostly grants +100 evasion");
        assertTrue(ghost.effectiveStats().flying, "ghostly grants flight");
        assertFalse(TileQuery.blocksMovementAt(level, 8, 8, ghost),
                "ghost passes through walls");
        assertFalse(MobQueries.blocksMovement(level, ghost, new Point(9, 8)),
                "ghost passes through other mobs");
        assertTrue(TileQuery.blocksMovementAt(level, 8, 8, null),
                "wall still blocks everyone else");
    }

    @Test
    void ghostEndingInsideWallSnapsToNearestFreeTile() {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(12));
        level.tiles[8][8] = Tile.WALL;
        Mob ghost = anyMob(new Point(8, 8));
        assertNotNull(ghost);
        CombatArena.placeMobs(level, List.of(ghost), List.of(new Point(8, 8)));

        // 1 stack: the next per-turn tick decrements it to 0 and expires it.
        BuffSystem.apply(level, ghost, Buff.BuffType.GHOSTLY, 1, null);
        ghost.statsDirty = true;
        assertTrue(ghost.isGhostly());

        BuffSystem.tickPerTurn(level);

        assertFalse(ghost.isGhostly(), "buff should have expired");
        int x = ghost.position.tileX(), y = ghost.position.tileY();
        assertFalse(x == 8 && y == 8, "mob must not remain inside the wall");
        assertFalse(TileQuery.blocksMovementAt(level, x, y, ghost),
                "mob must land on a tile it can legally occupy");
    }

    @Test
    void ghostEndingOnOpenFloorStaysPut() {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(13));
        Mob ghost = anyMob(new Point(5, 5));
        assertNotNull(ghost);
        CombatArena.placeMobs(level, List.of(ghost), List.of(new Point(5, 5)));

        BuffSystem.apply(level, ghost, Buff.BuffType.GHOSTLY, 1, null);
        ghost.statsDirty = true;
        BuffSystem.tickPerTurn(level);

        assertFalse(ghost.isGhostly());
        assertEquals(5, ghost.position.tileX());
        assertEquals(5, ghost.position.tileY());
    }
}
