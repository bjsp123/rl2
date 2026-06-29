package com.bjsp123.rl2.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import org.junit.jupiter.api.Test;

/** Unit tests for the per-mob {@link MobMemory} blackboard. */
class MobMemoryTest {

    // -- ensureSized: happy --------------------------------------------------

    @Test
    void ensureSizedAllocatesArraysToLevelDimensions() {
        MobMemory mem = new MobMemory();
        mem.ensureSized(new Level(12, 9));
        assertNotNull(mem.knownTiles);
        assertNotNull(mem.rememberedTile);
        assertEquals(12, mem.knownTiles.length);
        assertEquals(9, mem.knownTiles[0].length);
    }

    @Test
    void ensureSizedReallocatesWhenDimensionsChange() {
        MobMemory mem = new MobMemory();
        mem.ensureSized(new Level(12, 9));
        mem.knownTiles[0][0] = true;
        mem.ensureSized(new Level(20, 20)); // different size -> fresh arrays
        assertEquals(20, mem.knownTiles.length);
        assertEquals(20, mem.knownTiles[0].length);
        assertTrue(!mem.knownTiles[0][0], "reallocated array should be cleared");
    }

    @Test
    void ensureSizedIsIdempotentForSameDimensions() {
        MobMemory mem = new MobMemory();
        mem.ensureSized(new Level(10, 10));
        boolean[][] first = mem.knownTiles;
        mem.ensureSized(new Level(10, 10)); // same size -> keep arrays
        assertEquals(first, mem.knownTiles);
    }

    // -- onLevelChange: clears per-level state -------------------------------

    @Test
    void onLevelChangeWipesEverythingAndStampsLevel() {
        MobMemory mem = new MobMemory();
        mem.ensureSized(new Level(10, 10));
        mem.knownItems.put(new Point(1, 1), new Item());
        mem.lastSeenThreat.put(new Mob(), new MobMemory.ThreatSighting(new Point(2, 2)));
        mem.knownInactiveBeacons.add(new Point(3, 3));
        mem.stairsDown = new Point(4, 4);
        mem.stairsUp = new Point(5, 5);
        mem.exploreTarget = new Point(6, 6);
        mem.ticksOnCurrentLevel = 99;
        mem.consecutiveWaitTurns = 5;

        mem.onLevelChange(7);

        assertNull(mem.knownTiles);
        assertNull(mem.rememberedTile);
        assertTrue(mem.knownItems.isEmpty());
        assertTrue(mem.lastSeenThreat.isEmpty());
        assertTrue(mem.knownInactiveBeacons.isEmpty());
        assertNull(mem.stairsDown);
        assertNull(mem.stairsUp);
        assertNull(mem.exploreTarget);
        assertEquals(0, mem.ticksOnCurrentLevel);
        assertEquals(0, mem.consecutiveWaitTurns);
        assertEquals(7, mem.levelStamp);
    }
}
