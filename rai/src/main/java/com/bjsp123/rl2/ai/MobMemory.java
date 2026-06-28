package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-mob blackboard. Held off-heap via {@link SmartAi}'s {@code WeakHashMap<Mob, MobMemory>}
 * so rlib's {@link Mob} stays innocent of rai types.
 *
 * <p>Refreshed each tick by {@link SmartAi#refreshMemory}: a bounded shadow-cast FOV updates
 * {@link #knownTiles}, the {@link #knownItems} index, the {@link #stairsDown} sighting,
 * {@link #knownInactiveBeacons}, and {@link #lastSeenThreat} for any enemy still in vision.
 */
public final class MobMemory {

    public boolean[][] knownTiles;
    public Tile[][]    rememberedTile;
    public final Map<Point, Item> knownItems = new LinkedHashMap<>();
    public final Map<Mob, ThreatSighting> lastSeenThreat = new HashMap<>();
    public final Set<Point> knownInactiveBeacons = new LinkedHashSet<>();
    public Point stairsDown;
    public Point stairsUp;

    public int    levelStamp = -1;
    /** Current exploration target; cleared on arrival or when no longer a frontier.
     *  Caching the target prevents the "step one tile, switch frontier, step back"
     *  oscillation that would otherwise burn most agent turns. */
    public Point  exploreTarget;
    public int    exploreTargetAge;
    /** Ticks spent on the current level. Reset by {@link #onLevelChange}. Drives
     *  {@link com.bjsp123.rl2.ai.Decider}'s committed-descent branch (past
     *  {@code EXPLORE_FATIGUE_LIMIT}) so the agent always eventually commits to
     *  stairs instead of looping in explore forever. */
    public int    ticksOnCurrentLevel;
    /** Consecutive agent turns spent on Wait. Resets when any non-Wait action runs.
     *  Used by {@code SmartAi} to break out of stuck states by forcing a random
     *  productive action when this exceeds {@code MAX_WAIT_STREAK}. */
    public int    consecutiveWaitTurns;
    /** Consecutive decisions during which the FOV sweep revealed NO new tile.
     *  Reset to 0 in {@link SmartAi#refreshMemory} whenever a tile flips
     *  unknown->known. Once it exceeds {@code Decider.EXPLORE_STALL_LIMIT} and a
     *  reachable down-stair is known, the agent abandons frontier exploration and
     *  commits to descending - breaks the early-floor dwell livelock. */
    public int    exploreStallTurns;
    /** Consecutive manual auto-explore steps that neither moved the player nor
     *  revealed a new tile. Backstop guard in {@link SmartAi#autoExploreStep} so
     *  manual explore can never livelock even if commitment + pocket-draining miss
     *  a pathological case. Reset on any productive step and on level change. */
    public int    autoExploreStuckTurns;

    /** Discard everything tied to a specific level - called when the mob descends. */
    public void onLevelChange(int newLevelStamp) {
        knownTiles = null;
        rememberedTile = null;
        knownItems.clear();
        lastSeenThreat.clear();
        knownInactiveBeacons.clear();
        stairsDown = null;
        stairsUp = null;
        exploreTarget = null;
        exploreTargetAge = 0;
        ticksOnCurrentLevel = 0;
        consecutiveWaitTurns = 0;
        exploreStallTurns = 0;
        autoExploreStuckTurns = 0;
        levelStamp = newLevelStamp;
    }

    /** Lazily ensure the per-tile arrays match the current level dimensions. */
    public void ensureSized(Level level) {
        if (knownTiles == null || knownTiles.length != level.width
                || knownTiles[0].length != level.height) {
            knownTiles = new boolean[level.width][level.height];
            rememberedTile = new Tile[level.width][level.height];
        }
    }

    public static final class ThreatSighting {
        public Point position;
        public int turnsSince;
        /** Consecutive turns we've been unable to path to {@link #position}. */
        public int unreachableStreak;
        public ThreatSighting(Point p) { this.position = p; this.turnsSince = 0; }
    }

    /** Count of consecutive recent turns we've been firing ranged at the nearest enemy
     *  without its HP dropping. Reset to 0 when HP falls or no enemy is targeted; bumps
     *  {@link com.bjsp123.rl2.ai.action.ActionLibrary}'s close utility when it climbs
     *  past a threshold, breaking range-vs-kiter stalemates. */
    public int stalemateTurns;
    /** HP of nearest enemy at last refreshMemory call - lets us tell if our ranged
     *  attacks are actually denting them. */
    public double lastEnemyHp = -1.0;
    public Mob lastEnemyRef;
}
