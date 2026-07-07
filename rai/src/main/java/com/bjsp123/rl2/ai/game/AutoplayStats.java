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
    /** Deepest flat world-node index reached (drives win-detection). */
    public int    depthReached;
    public int    maxDepth;
    /** Deepest ACTUAL dungeon depth reached ({@code Level.depth}, 1-based). The
     *  reporting axis - consistent across runs, unlike {@link #depthReached}. */
    public int    maxDungeonDepth = 1;
    public int    stairsDescended;
    public int    stairsAscended;

    public int    itemsPickedUp;
    public final Map<String, Integer> itemsByType = new LinkedHashMap<>();
    public int[]  itemsPerDepth = new int[0];

    public int    mobsKilled;
    public int    mobsKilledByEnv;
    public final Map<String, Integer> killsByMobType = new LinkedHashMap<>();
    public int[]  killsPerDepth = new int[0];

    /** HP the agent lost on each depth index, summed over the run. Lets the
     *  report show where in the dungeon the agent bleeds the most HP. */
    public int[]  damageTakenPerDepth = new int[0];

    /** First tick ({@code turnsElapsed}) the agent ARRIVED at each depth index;
     *  {@code -1} = never reached, index 0 = 0 (the run starts on depth 0).
     *  Drives the average pace-to-depth report. */
    public int[]  arrivalTickPerDepth = new int[0];

    public int    bombsThrown;
    public int    wandsFired;
    public int    potionsDrunk;
    public int    meleeAttacks;

    /** Total damage the agent dealt with melee swings (sum of
     *  {@code MobMeleeAttacked.dealt} for attacks where the agent is the
     *  attacker). Missed swings contribute 0; periodic bleed / DOT damage is
     *  not included (it lands as DamageDealt with the source still the
     *  agent but its origin item is the bleed mechanism, not the weapon). */
    public int    meleeDamage;
    /** Total damage the agent dealt via wands - any
     *  {@link com.bjsp123.rl2.event.GameEvent.DamageDealt} where source is
     *  the agent and the cause's originating item is a WAND. */
    public int    wandDamage;
    /** Total damage the agent dealt with thrown bombs - any DamageDealt
     *  where source is the agent and the originating item is a BOMB. */
    public int    bombDamage;

    /** Damage the agent DEALT, split into the four attack modes
     *  ({@code "melee"} / {@code "thrown"} / {@code "bomb"} / {@code "wand"}).
     *  Melee is attributed from {@code MobMeleeAttacked}; the ranged modes from
     *  {@code DamageDealt} by the originating item's category. */
    public final Map<String, Integer> damageDoneByCategory = new LinkedHashMap<>();
    /** Damage the agent DEALT, keyed {@code "<mode>:<ITEM_TYPE>"} (e.g.
     *  {@code "melee:KATANA"}, {@code "wand:WAND_FIRE"}), so the report can break
     *  each mode down by the specific weapon / wand / bomb / thrown type. */
    public final Map<String, Integer> damageDoneByType = new LinkedHashMap<>();

    /** Accumulate one outgoing-damage hit into both the by-mode and the
     *  by-mode-and-type maps. No-op for non-positive amounts (misses). */
    void addDamageDone(String mode, String itemType, int amount) {
        if (amount <= 0 || mode == null) return;
        damageDoneByCategory.merge(mode, amount, Integer::sum);
        damageDoneByType.merge(mode + ":" + (itemType == null ? "?" : itemType),
                amount, Integer::sum);
    }

    /** Total HP the agent lost across the run, summed from every
     *  {@link com.bjsp123.rl2.event.GameEvent.DamageDealt} that landed on
     *  the agent. Includes misses-rounded-up? No — only positive amounts;
     *  misses are amount=0. */
    public int    damageTaken;
    /** Damage taken, bucketed by {@code DamageCause.medium} ("blow",
     *  "magic", "throw", "fire", "chasm", "wall-slam", ...). Lets the
     *  regression report tell you whether HP is leaking to melee, ranged
     *  shots, DOTs or terrain. {@code "environment"} catches the
     *  {@link com.bjsp123.rl2.logic.MobSystem.DamageCause#NONE} case;
     *  {@code "unknown"} catches malformed cause records (shouldn't fire). */
    public final Map<String, Integer> damageTakenByMedium = new LinkedHashMap<>();
    /** Damage taken, bucketed by source-mob type (e.g. {@code "KOBOLD"}).
     *  {@code "ENV"} catches environmental damage (no attacker mob). */
    public final Map<String, Integer> damageTakenBySource = new LinkedHashMap<>();
    /** Damage taken keyed by depth index then by source-mob type, so the report
     *  can name the top damage dealers on each individual floor. */
    public final Map<Integer, Map<String, Integer>> damageTakenBySourceByDepth =
            new LinkedHashMap<>();

    /** Add one incoming hit to the per-depth, per-source damage tally. */
    void bumpDamageTakenSource(int depthIdx, String source, int amount) {
        if (amount <= 0 || source == null) return;
        damageTakenBySourceByDepth
                .computeIfAbsent(depthIdx, k -> new LinkedHashMap<>())
                .merge(source, amount, Integer::sum);
    }

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

    /** Mob type of the killer that landed the final blow on the agent, or
     *  {@code "ENV"} for environmental deaths (chasms, fire, ambient DOTs),
     *  or {@code null} when the run ended via WIN / TIMEOUT. Used by the
     *  regression harness to bucket deaths by attacker. */
    public String deathCause;

    /** Wall-clock time the run took, in milliseconds. Filled in by the driver. */
    public double wallClockMs;

    void ensureSizedFor(int levels) {
        if (itemsPerDepth.length < levels) itemsPerDepth = new int[levels];
        if (killsPerDepth.length < levels) killsPerDepth = new int[levels];
        if (damageTakenPerDepth.length < levels) damageTakenPerDepth = new int[levels];
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
