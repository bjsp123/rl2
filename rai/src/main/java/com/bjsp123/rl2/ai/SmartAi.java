package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.ai.action.Action;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.MobBrains;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-tick entry for SMART mobs. Registered with rlib via
 * {@link com.bjsp123.rl2.logic.MobBrains} by {@link RaiBootstrap}.
 *
 * <p>Flow each tick:
 * <ol>
 *   <li>Look up / lazily allocate the mob's {@link MobMemory}.</li>
 *   <li>Refresh memory from the bounded per-mob FOV.</li>
 *   <li>{@link Decider#decide} picks the action via the hardcoded priority tree.</li>
 *   <li>Annotate {@link Mob#intent} and {@link Mob#intentDetail} so the UI can show
 *       both the structural label and the human-readable goal/action.</li>
 * </ol>
 */
public final class SmartAi implements MobBrains.Brain {

    /** Per-mob memory side-table - keeps {@link Mob} free of rai types. */
    private static final Map<Mob, MobMemory> MEMORIES = new WeakHashMap<>();

    /** Process-wide counters for the diagnostic runners.
     *  Key: "GOAL/action". Reset via {@link #resetDecisionCounters}. */
    private static final Map<String, Long> DECISION_COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Optional per-mob turn logger; consulted in {@link #run}. Null disables. */
    private static java.util.function.BiConsumer<Mob, String> TRACE_SINK;

    public static void resetDecisionCounters() { DECISION_COUNTS.clear(); }
    public static Map<String, Long> decisionCounters() {
        return new java.util.TreeMap<>(DECISION_COUNTS);
    }
    /** Read-only peek at a mob's MobMemory - lets the autoplay driver answer
     *  "did this agent ever see stairs / how much of the level was explored". */
    public static MobMemory peekMemory(Mob mob) {
        return MEMORIES.get(mob);
    }
    public static void setTraceSink(java.util.function.BiConsumer<Mob, String> sink) {
        TRACE_SINK = sink;
    }

    /** Diagnostic hook fired per agent decision with the picked Action. The
     *  autoplay harness uses this to dump per-decision CSV rows. Set to null to
     *  disable. */
    public interface DecisionTraceHook {
        void onDecision(Mob mob, WorldState state, com.bjsp123.rl2.ai.action.Action step);
    }
    private static volatile DecisionTraceHook DECISION_TRACE;
    public static void setDecisionTraceHook(DecisionTraceHook hook) { DECISION_TRACE = hook; }

    /**
     * One auto-explore step for RL-53. Runs the planner on {@code player}
     * (whose behavior stays {@code PLAYER}) and executes the chosen action only
     * when it is pure exploration / pickup; otherwise hands back. Shares the
     * static {@link #MEMORIES} side-table with {@link #run}, so the player's
     * remembered map persists across calls.
     */
    public static com.bjsp123.rl2.logic.AutoExplore.Result autoExploreStep(Mob player, Level level) {
        MobMemory mem = memoryFor(player);
        int levelStamp = System.identityHashCode(level);
        if (mem.levelStamp != levelStamp) mem.onLevelChange(levelStamp);
        mem.ensureSized(level);
        refreshMemory(player, level, mem);

        WorldState state = new WorldState(player, level, mem);
        Action step = Decider.decide(state);
        String branch = Decider.LAST_BRANCH.get();
        boolean exploring = "explore".equals(branch)
                || "pickup-item".equals(branch)
                || "pickup-powerup".equals(branch)
                || "search".equals(branch);
        if (!exploring) {
            // Genuine combat hand-backs (an enemy the player must deal with) stop
            // auto-explore so the player takes over. These are effectively
            // unreachable here - PlayController.autoExploreShouldStop already halts
            // on ANY visible hostile before the driver runs - but guard anyway.
            boolean combat = branch.equals("fight") || branch.equals("flee")
                    || branch.equals("clear-fight") || branch.equals("clear-hunt");
            if (combat) {
                mem.autoExploreStuckTurns = 0;
                return com.bjsp123.rl2.logic.AutoExplore.Result.DONE_BUSY;
            }
            // Every other non-exploring branch (descend / heal / search-miss) is a
            // suggestion the SMART agent would act on for itself but manual
            // auto-explore must not: it never auto-descends, never auto-drinks a
            // potion to heal, and never stops just because HP dipped below the AI's
            // caution threshold while nothing is threatening it. Instead keep
            // mapping the floor toward the nearest reachable frontier; only when no
            // frontier remains is the floor genuinely done.
            Point frontier = com.bjsp123.rl2.ai.eval.ExplorationEval
                    .committedExploreTarget(player, level, mem);
            if (frontier == null) {
                mem.autoExploreStuckTurns = 0;
                return com.bjsp123.rl2.logic.AutoExplore.Result.DONE_EXPLORED;
            }
            step = new com.bjsp123.rl2.ai.action.ActionMoveToward(frontier, "explore", 0.6, true);
        }
        // Backstop livelock guard: commitment + pocket-draining should prevent the
        // two-square flip, but if a step neither moves the player nor reveals a new
        // tile for several consecutive turns, stop rather than spin forever.
        Point posBefore = player.position;
        int stallBefore = mem.exploreStallTurns;
        step.execute(player, level);   // self-applies the move/pickup cost
        boolean moved = posBefore != null && !posBefore.equals(player.position);
        boolean revealed = mem.exploreStallTurns < stallBefore || mem.exploreStallTurns == 0;
        if (moved || revealed) {
            mem.autoExploreStuckTurns = 0;
        } else if (++mem.autoExploreStuckTurns > AUTO_EXPLORE_STUCK_LIMIT) {
            mem.autoExploreStuckTurns = 0;
            return com.bjsp123.rl2.logic.AutoExplore.Result.DONE_EXPLORED;
        }
        return com.bjsp123.rl2.logic.AutoExplore.Result.STEPPED;
    }

    /** Consecutive no-progress auto-explore steps after which manual explore stops.
     *  Pure backstop; the commitment + pocket-drain path should reach it only in
     *  pathological geometry. */
    private static final int AUTO_EXPLORE_STUCK_LIMIT = 12;

    @Override
    public void run(Mob mob, Level level) {
        MobMemory mem = memoryFor(mob);
        int levelStamp = System.identityHashCode(level);
        if (mem.levelStamp != levelStamp) mem.onLevelChange(levelStamp);
        mem.ensureSized(level);
        mem.ticksOnCurrentLevel++;
        // Off-clock housekeeping: spend any banked perk points the agent has
        // earned from kills / level-ups. Doesn't cost a turn - just allocates
        // the points so the agent benefits from them in the same tick.
        spendAvailablePerks(mob);
        refreshMemory(mob, level, mem);

        WorldState state = new WorldState(mob, level, mem);
        updateStalemateCounter(mem, state);

        // Hardcoded priority tree (see Decider). Top-level branch selection is
        // deterministic; scored argmax happens inside each branch only. There is
        // no goal-vs-goal scoring race.
        Action step = Decider.decide(state);
        annotate(mob, step);
        DECISION_COUNTS.merge(Decider.LAST_BRANCH.get() + "/" + step.name(), 1L, Long::sum);
        DecisionTraceHook hook = DECISION_TRACE;
        if (hook != null) hook.onDecision(mob, state, step);
        if (TRACE_SINK != null) {
            TRACE_SINK.accept(mob, "hp=" + (int)mob.hp + "/" + (int)state.myStats.maxHp
                    + " enemy=" + (state.nearestEnemy == null ? "-" : state.nearestEnemy.mobType
                        + "@" + state.nearestEnemyDist)
                    + " " + step.name());
        }
        // No-progress escape hatch. A move action can return without actually
        // moving when its BFS/pathfinder step returns null at execute time
        // (target unreachable from the fresh state). The action's name is still
        // "move" so a naive name=="wait" check misses it - we'd reset the
        // streak counter every tick while the agent silently idled forever.
        //
        // Count any tick where the agent's position didn't change as a "no
        // progress" tick, EXCEPT for action types that legitimately advance the
        // game without moving the agent (drink heals, attacks land, descend
        // through stairs, etc.). After 8 such ticks force a random walkable
        // adjacent step so we break out and either find a new angle, drop into
        // combat, or die. Anything beats infinite no-op.
        Point posBefore = mob.position;
        step.execute(mob, level);
        boolean stationaryActionOk = switch (step.name()) {
            case "drink", "eat", "pickup", "ability",
                 "throw", "wand", "grapple", "melee",
                 "descend stairs", "jump", "charge",
                 "fire wand", "use ability" -> true;
            default -> false;
        };
        boolean noProgress = "wait".equals(step.name())
                || (!stationaryActionOk && posBefore != null && posBefore.equals(mob.position));
        if (noProgress) {
            mem.consecutiveWaitTurns++;
            if (mem.consecutiveWaitTurns > 8) {
                Action escape = pickRandomWalkAction(mob, level, state);
                if (escape != null) {
                    mob.intentDetail = "escape no-progress streak";
                    mem.consecutiveWaitTurns = 0;
                    DECISION_COUNTS.merge("escape/no-progress", 1L, Long::sum);
                    escape.execute(mob, level);
                }
            }
        } else {
            mem.consecutiveWaitTurns = 0;
        }
    }

    /** Escape-path destination picker. Two rules layered, per user spec:
     *  <ol>
     *    <li>If there are no unknown FLOOR-class tiles left anywhere on the
     *        level, return the descend action - the level is fully explored
     *        and there's nothing to find by walking. Matches the Decider's
     *        top-level "fully explored => DESCEND" rule.</li>
     *    <li>Otherwise, pick uniformly from walkable adjacent tiles whose
     *        8-neighbourhood contains an unknown floor tile. This biases the
     *        escape toward exploration progress so each escape tick advances
     *        FOV instead of stepping back into already-known territory.</li>
     *  </ol>
     *  Fallback: if unknown floor exists but no walkable neighbour is next to
     *  any of it, pick uniformly from all walkable adjacent tiles so the
     *  escape never returns null when steppable tiles exist. */
    private static Action pickRandomWalkAction(Mob mob, Level level, WorldState state) {
        MobMemory mem = MEMORIES.get(mob);
        if (mem != null && mem.knownTiles != null && !anyUnknownFloor(level, mem)) {
            return com.bjsp123.rl2.ai.Decider.planDescend(state);
        }
        java.util.List<com.bjsp123.rl2.model.Point> nearUnknown = new java.util.ArrayList<>();
        java.util.List<com.bjsp123.rl2.model.Point> anyWalkable = new java.util.ArrayList<>();
        int mx = mob.position.tileX(), my = mob.position.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = mx + dx, y = my + dy;
                if (x < 1 || y < 1 || x >= level.width - 1 || y >= level.height - 1) continue;
                if (com.bjsp123.rl2.model.TileQuery.blocksMovementAt(level, x, y, mob)) continue;
                com.bjsp123.rl2.model.Point p = new com.bjsp123.rl2.model.Point(x, y);
                anyWalkable.add(p);
                if (mem != null && mem.knownTiles != null
                        && hasUnknownFloorNeighbour(level, mem, x, y)) {
                    nearUnknown.add(p);
                }
            }
        }
        java.util.List<com.bjsp123.rl2.model.Point> pool =
                !nearUnknown.isEmpty() ? nearUnknown : anyWalkable;
        if (pool.isEmpty()) return null;
        com.bjsp123.rl2.model.Point pick =
                pool.get(WALK_RNG.nextInt(pool.size()));
        return new com.bjsp123.rl2.ai.action.ActionMoveToward(pick, "wander", 0.5);
    }

    /** True if any FLOOR-class tile (FLOOR / FLOOR_WOOD / FLOOR_SPECIAL) on the
     *  level is not yet in {@code mem.knownTiles}. O(w*h); only called from
     *  the escape path where the caller has already detected a no-progress
     *  streak, so the cost is amortised. */
    private static boolean anyUnknownFloor(Level level, MobMemory mem) {
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (mem.knownTiles[x][y]) continue;
                Tile t = level.tiles[x][y];
                if (t == Tile.FLOOR || t == Tile.FLOOR_WOOD || t == Tile.FLOOR_SPECIAL) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the 8-neighbourhood of ({@code x}, {@code y}) contains an
     *  unknown FLOOR-class tile - the gate for "stepping here grows FOV
     *  toward unknown floor". */
    private static boolean hasUnknownFloorNeighbour(Level level, MobMemory mem, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (mem.knownTiles[nx][ny]) continue;
                Tile t = level.tiles[nx][ny];
                if (t == Tile.FLOOR || t == Tile.FLOOR_WOOD || t == Tile.FLOOR_SPECIAL) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Spend any unspent perk points without disturbing the agent's turn. Mirrors
     *  {@link com.bjsp123.rl2.logic.MobProgression#autoLevelUpPerks}'s spend loop
     *  but does NOT overwrite {@code mob.perkPoints} (calling this every tick is
     *  safe; only banked points are spent). Signature perks (already at level >= 1)
     *  level up first, then any other perk under the cap. */
    private static void spendAvailablePerks(Mob mob) {
        if (mob == null || mob.perks == null || mob.perkPoints <= 0) return;
        java.util.EnumSet<com.bjsp123.rl2.model.Perk> signatures =
                java.util.EnumSet.noneOf(com.bjsp123.rl2.model.Perk.class);
        for (var e : mob.perks.entrySet()) {
            if (e.getValue() != null && e.getValue() >= 1) signatures.add(e.getKey());
        }
        com.bjsp123.rl2.model.Perk[] all = com.bjsp123.rl2.model.Perk.values();
        java.util.List<com.bjsp123.rl2.model.Perk> candidates = new java.util.ArrayList<>();
        java.util.Random rng = PERK_RNG;
        int cap = 8;  // mirrors MobProgression.PERK_LEVEL_CAP (private)
        while (mob.perkPoints > 0) {
            candidates.clear();
            for (var p : signatures) {
                if (mob.perks.getOrDefault(p, 0) < cap) candidates.add(p);
            }
            if (candidates.isEmpty()) {
                for (var p : all) {
                    if (mob.perks.getOrDefault(p, 0) < cap) candidates.add(p);
                }
            }
            if (candidates.isEmpty()) break;
            var chosen = candidates.get(rng.nextInt(candidates.size()));
            mob.perks.merge(chosen, 1, Integer::sum);
            mob.perkPoints--;
        }
        mob.statsDirty = true;
    }
    private static final java.util.Random PERK_RNG =
            com.bjsp123.rl2.util.SimRng.register("SmartAi.perk", new java.util.Random(0x5EEDEDL));
    /** Random-walk tie-break for the escape wander. Registered (was
     *  ThreadLocalRandom) so escapes replay deterministically under a seed. */
    private static final java.util.Random WALK_RNG =
            com.bjsp123.rl2.util.SimRng.register("SmartAi.walk", new java.util.Random());

    /** Track how long we've been failing to advance the fight against the nearest
     *  enemy. If we keep firing ranged into the void while their HP holds steady,
     *  ActionMoveToward (close) will get a bonus so the planner commits to closing
     *  the gap instead of spinning bombs at a kiter for 200 turns. */
    private static void updateStalemateCounter(MobMemory mem, WorldState state) {
        Mob enemy = state.nearestEnemy;
        if (enemy == null) {
            mem.stalemateTurns = 0;
            mem.lastEnemyRef = null;
            mem.lastEnemyHp = -1.0;
            return;
        }
        if (enemy != mem.lastEnemyRef) {
            mem.lastEnemyRef = enemy;
            mem.lastEnemyHp = enemy.hp;
            mem.stalemateTurns = 0;
            return;
        }
        // Treat "no meaningful HP drop" as a stalemate. 5% of maxHp keeps the
        // counter advancing when bombs do scratch damage but the enemy isn't
        // actually going down - that's the "can't hit with a bomb" scenario the
        // user wants the planner to detect.
        double maxHp = Math.max(1.0, enemy.effectiveStats().maxHp);
        double meaningfulDrop = Math.max(0.5, maxHp * 0.05);
        if (enemy.hp < mem.lastEnemyHp - meaningfulDrop) {
            mem.stalemateTurns = 0;
        } else {
            mem.stalemateTurns++;
        }
        mem.lastEnemyHp = enemy.hp;
    }

    /* ---------- memory bookkeeping ---------- */

    private static MobMemory memoryFor(Mob mob) {
        MobMemory mem = MEMORIES.get(mob);
        if (mem == null) {
            mem = new MobMemory();
            MEMORIES.put(mob, mem);
        }
        return mem;
    }

    /**
     * Sweep the per-mob FOV into {@link MobMemory#knownTiles}, refresh item /
     * beacon / stairs sightings, and age {@code lastSeenThreat} entries.
     */
    private static void refreshMemory(Mob mob, Level level, MobMemory mem) {
        boolean[][] fov = new boolean[level.width][level.height];
        LevelSystem.computeMobVisibilityInto(level, mob, fov);
        boolean revealedNew = false;
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (!fov[x][y]) continue;
                if (!mem.knownTiles[x][y]) revealedNew = true; // flipped unknown->known this turn
                mem.knownTiles[x][y] = true;
                Tile t = level.tiles[x][y];
                mem.rememberedTile[x][y] = t;
                if (t == Tile.STAIRS_DOWN) mem.stairsDown = new Point(x, y);
                else if (t == Tile.STAIRS_UP) mem.stairsUp = new Point(x, y);
                if (t == Tile.BEACON_INACTIVE) mem.knownInactiveBeacons.add(new Point(x, y));
                else if (t == Tile.BEACON_ACTIVE) mem.knownInactiveBeacons.remove(new Point(x, y));
            }
        }
        // Track exploration progress: a turn that reveals no new tile counts
        // toward the dwell-livelock fallback (Decider's "descend-stalled" branch).
        if (revealedNew) mem.exploreStallTurns = 0; else mem.exploreStallTurns++;
        // Items currently visible: snapshot known floor items; drop ones we used to remember
        // here that are no longer present.
        if (level.items != null) {
            for (Item it : level.items) {
                if (it == null || it.location == null) continue;
                int x = it.location.tileX(), y = it.location.tileY();
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                if (fov[x][y]) mem.knownItems.put(it.location, it);
            }
            // Prune memory entries whose item is no longer where we last saw it
            // or whose item has been removed from the level entirely (consumed
            // POWERUPs, picked up by another mob, destroyed by fire, etc.). The
            // tile-match check alone misses powerup consumption because
            // applyPowerupsAt removes from level.items without nulling the
            // item's location.
            mem.knownItems.entrySet().removeIf(e -> {
                Item it = e.getValue();
                if (it == null || it.location == null) return true;
                if (!it.location.equals(e.getKey())) return true;
                return !level.items.contains(it);
            });
        }
        // Age threat sightings first; then refresh any currently-visible hostile.
        for (MobMemory.ThreatSighting sight : mem.lastSeenThreat.values()) sight.turnsSince++;
        if (mob.visibleMobsAtTurnStart != null) {
            for (Mob other : mob.visibleMobsAtTurnStart) {
                if (other == mob || other.hp <= 0 || other.position == null) continue;
                if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(mob, other)
                        != com.bjsp123.rl2.logic.MobSystem.Attitude.ATTACK) continue;
                MobMemory.ThreatSighting cur = mem.lastSeenThreat.get(other);
                if (cur == null) mem.lastSeenThreat.put(other, new MobMemory.ThreatSighting(other.position));
                else { cur.position = other.position; cur.turnsSince = 0; }
            }
        }
        // Drop sightings on confirmed kill, off-level, or persistent unreachability.
        mem.lastSeenThreat.entrySet().removeIf(e -> {
            Mob m = e.getKey();
            if (m == null || m.hp <= 0) return true;
            if (!level.mobs.contains(m)) return true;
            return e.getValue() != null && e.getValue().unreachableStreak > 5;
        });
        // Only run Pathfinder on the freshest sighting per tick - we don't need
        // per-threat reachability data, just enough to evict the nearest one if
        // we can't reach it. Doing N pathfinder calls per tick scales O(threats)
        // and slowed long autoplay runs to a crawl (50-100 hostiles on a level).
        MobMemory.ThreatSighting freshest = null;
        int freshestAge = Integer.MAX_VALUE;
        for (MobMemory.ThreatSighting sight : mem.lastSeenThreat.values()) {
            if (sight == null || sight.position == null) continue;
            if (sight.turnsSince < freshestAge) { freshestAge = sight.turnsSince; freshest = sight; }
        }
        if (freshest != null) {
            if (com.bjsp123.rl2.logic.Pathfinder.nextStep(level, mob, freshest.position) == null) {
                freshest.unreachableStreak++;
            } else {
                freshest.unreachableStreak = 0;
            }
        }
        // Also hard-cap memory size so a dense dungeon level doesn't grow
        // lastSeenThreat to 50+ entries that each cost cycles to manage.
        if (mem.lastSeenThreat.size() > 8) {
            mem.lastSeenThreat.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().turnsSince, a.getValue().turnsSince))
                    .limit(mem.lastSeenThreat.size() - 8)
                    .map(java.util.Map.Entry::getKey)
                    .toList()
                    .forEach(mem.lastSeenThreat::remove);
        }
    }

    /**
     * Populate {@link Mob#intent} (enum-bucket) and {@link Mob#intentDetail} (free-text)
     * with what the AI is doing this tick, so the UI can show it.
     */
    /** Map the action name to a Mob.Intent enum for the UI label. The action
     *  name is a stable short identifier ("move", "melee", "drink", etc.) so we
     *  switch on it directly rather than re-deriving from a goal class. */
    private static void annotate(Mob mob, Action step) {
        if (step == null) { mob.intent = Mob.Intent.IDLE; mob.intentDetail = null; return; }
        mob.intent = switch (step.name()) {
            case "melee", "throw", "wand", "charge", "grapple" -> Mob.Intent.PURSUING;
            case "drink", "eat", "pickup"                       -> Mob.Intent.USING_ITEM;
            case "ability"                                      -> Mob.Intent.USING_ABILITY;
            case "wait"                                         -> Mob.Intent.IDLE;
            case "descend stairs"                               -> Mob.Intent.WANDERING;
            default                                              -> Mob.Intent.WANDERING;
        };
        mob.intentDetail = step.intentDetail();
    }
}
