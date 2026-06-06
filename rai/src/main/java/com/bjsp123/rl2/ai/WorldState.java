package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.StatBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight projection of the current decision context handed to goals and
 * actions. Created once per SMART AI tick and discarded; never mutated by the
 * planner. Holds derived values that goals share (HP%, nearest-threat,
 * visible enemies/allies) so each goal doesn't recompute them.
 */
public final class WorldState {

    public final Mob mob;
    public final Level level;
    public final MobMemory memory;
    public final StatBlock myStats;

    public final double hpFrac;

    public final List<Mob> visibleEnemies = new ArrayList<>();
    public final List<Mob> visibleAllies  = new ArrayList<>();
    public final Mob nearestEnemy;
    public final int nearestEnemyDist;
    /** Last-known location of a recently-seen enemy (within the threat memory window),
     *  even if no enemy is currently visible. Lets KILL keep driving the agent when
     *  smoke / kiting briefly hides the target. */
    public final Point lastKnownEnemyTile;
    public final int lastKnownEnemyTurnsSince;

    public WorldState(Mob mob, Level level, MobMemory memory) {
        this.mob = mob;
        this.level = level;
        this.memory = memory;
        this.myStats = mob.effectiveStats();
        this.hpFrac = myStats.maxHp > 0 ? Math.max(0.0, Math.min(1.0, mob.hp / myStats.maxHp)) : 1.0;

        Mob nearest = null;
        int bestDist = Integer.MAX_VALUE;
        java.util.Set<Mob> vis = mob.visibleMobsAtTurnStart;
        if (vis != null) {
            for (Mob other : vis) {
                if (other == mob || other.hp <= 0 || other.position == null) continue;
                com.bjsp123.rl2.logic.MobSystem.Attitude att =
                        com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(mob, other);
                if (att == com.bjsp123.rl2.logic.MobSystem.Attitude.ATTACK) {
                    visibleEnemies.add(other);
                    int d = chebyshev(mob.position, other.position);
                    if (d < bestDist) { bestDist = d; nearest = other; }
                } else if (att == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) {
                    visibleAllies.add(other);
                }
            }
        }
        this.nearestEnemy = nearest;
        this.nearestEnemyDist = nearest == null ? Integer.MAX_VALUE : bestDist;

        // Fallback chain for "where should I go if I can't see anyone?":
        //   1. Freshest memory sighting (from MobMemory.lastSeenThreat)
        //   2. Nearest live hostile anywhere on the level (matches the MOB AI's
        //      targetRequiresSight=false path - the agent always knows there's an
        //      enemy out there even when a sight-blocker hides the exact tile)
        Point recall = null;
        int recallAge = Integer.MAX_VALUE;
        if (nearest == null && memory != null) {
            for (MobMemory.ThreatSighting sight : memory.lastSeenThreat.values()) {
                if (sight == null || sight.position == null) continue;
                if (sight.turnsSince < recallAge) { recallAge = sight.turnsSince; recall = sight.position; }
            }
        }
        if (recall == null && nearest == null) {
            int bestD = Integer.MAX_VALUE;
            for (Mob other : level.mobs) {
                if (other == mob || other.hp <= 0 || other.position == null) continue;
                if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(mob, other)
                        != com.bjsp123.rl2.logic.MobSystem.Attitude.ATTACK) continue;
                int d = chebyshev(mob.position, other.position);
                if (d < bestD) { bestD = d; recall = other.position; }
            }
            if (recall != null) recallAge = Integer.MAX_VALUE - 1;  // mark as "level-scan", not memory
        }
        this.lastKnownEnemyTile = recall;
        this.lastKnownEnemyTurnsSince = recallAge;
    }

    /** True if a hostile is either visible now or was sighted recently (within
     *  {@link #RECENT_THREAT_TURNS}). Stale sightings and the level-wide-scan
     *  fallback don't count - otherwise dungeon EXPLORE/DESCEND get suppressed
     *  forever once any enemy has been seen. */
    public boolean hasActiveOrRecentThreat() {
        return nearestEnemy != null
                || (lastKnownEnemyTile != null
                    && lastKnownEnemyTurnsSince <= RECENT_THREAT_TURNS);
    }

    /** Window used by goals that want a *recent* sighting bias (e.g. KILL ranking). */
    public static final int RECENT_THREAT_TURNS = 30;

    /** Lazy-cached stairs-reachability for this tick - set by {@link com.bjsp123.rl2.ai.goal.GoalDescend}
     *  on first query to avoid 1-9 Pathfinder calls per selection pass per tick. */
    private Boolean cachedStairsReachable;
    public boolean stairsReachable() {
        if (cachedStairsReachable != null) return cachedStairsReachable;
        cachedStairsReachable = com.bjsp123.rl2.ai.goal.GoalDescend.computeStairsReachable(this);
        return cachedStairsReachable;
    }

    /** Lazy-cached reachability of the {@link #lastKnownEnemyTile}. Lets
     *  {@link com.bjsp123.rl2.ai.goal.GoalKill} drop its score to 0 when the
     *  chase target is unreachable, so KILL doesn't pin the agent into Wait
     *  forever while EXPLORE could be making progress. */
    private Boolean cachedLastKnownReachable;
    public boolean lastKnownEnemyReachable() {
        if (cachedLastKnownReachable != null) return cachedLastKnownReachable;
        if (lastKnownEnemyTile == null) { cachedLastKnownReachable = false; return false; }
        cachedLastKnownReachable = com.bjsp123.rl2.logic.Pathfinder.nextStep(
                level, mob, lastKnownEnemyTile) != null;
        return cachedLastKnownReachable;
    }

    public static int chebyshev(Point a, Point b) {
        return Math.max(Math.abs(a.tileX() - b.tileX()), Math.abs(a.tileY() - b.tileY()));
    }

    public boolean isAdjacent(Mob other) {
        return other != null && other.position != null && chebyshev(mob.position, other.position) == 1;
    }
}
