package com.bjsp123.rl2.ai.game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-run accumulator for {@link AutoplayGame}. All counts updated each tick by
 * scanning the level's event log for {@code ItemPickedUp} / {@code MobKilled} and
 * detecting {@code World.currentLevelIndex} changes for stairs.
 */
public final class AutoplayStats {

    public int    turnsSurvived;
    public int    depthReached;
    public int    maxDepth;
    public int    stairsDescended;
    public int    stairsAscended;

    public int    itemsPickedUp;
    public final Map<String, Integer> itemsByType = new LinkedHashMap<>();
    public int[]  itemsPerDepth = new int[0];

    public int    mobsKilled;
    public int    mobsKilledByEnv;
    public final Map<String, Integer> killsByMobType = new LinkedHashMap<>();
    public int[]  killsPerDepth = new int[0];

    /** First tick ({@code turnsElapsed}) the agent ARRIVED at each depth index;
     *  {@code -1} = never reached, index 0 = 0 (the run starts on depth 0).
     *  Drives the average pace-to-depth report. */
    public int[]  arrivalTickPerDepth = new int[0];

    public int    bombsThrown;
    public int    wandsFired;
    public int    potionsDrunk;
    public int    meleeAttacks;

    public double finalHp;
    public double finalMaxHp;
    public int    finalCharLevel;
    public int    finalPerksSpent;
    /** True if stairs-down on the level we ended on were ever sighted by the agent's FOV. */
    public boolean stairsKnownAtEnd;
    /** True if stairs were reachable (Pathfinder.nextStep != null) at the time the run ended. */
    public boolean stairsReachableAtEnd;
    /** Fraction of non-wall tiles on the end-level that were in {@code knownTiles}. */
    public double  endLevelKnownFraction;

    /** {@link AutoplayGame.Outcome#name()} when run is done. */
    public String outcome = "IN_PROGRESS";

    /** Wall-clock time the run took, in milliseconds. Filled in by the driver. */
    public double wallClockMs;

    void ensureSizedFor(int levels) {
        if (itemsPerDepth.length < levels) itemsPerDepth = new int[levels];
        if (killsPerDepth.length < levels) killsPerDepth = new int[levels];
        if (arrivalTickPerDepth.length < levels) {
            arrivalTickPerDepth = new int[levels];
            java.util.Arrays.fill(arrivalTickPerDepth, -1);
            if (levels > 0) arrivalTickPerDepth[0] = 0; // start on depth 0 at turn 0
        }
    }

    void bumpItem(String type, int depthIdx) {
        itemsPickedUp++;
        if (type != null) itemsByType.merge(type, 1, Integer::sum);
        if (depthIdx >= 0 && depthIdx < itemsPerDepth.length) itemsPerDepth[depthIdx]++;
    }

    void bumpKill(String mobType, int depthIdx, boolean byAgent) {
        if (byAgent) {
            mobsKilled++;
            if (mobType != null) killsByMobType.merge(mobType, 1, Integer::sum);
            if (depthIdx >= 0 && depthIdx < killsPerDepth.length) killsPerDepth[depthIdx]++;
        } else {
            mobsKilledByEnv++;
        }
    }
}
