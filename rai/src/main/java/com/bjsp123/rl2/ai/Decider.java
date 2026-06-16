package com.bjsp123.rl2.ai;

import com.bjsp123.rl2.ai.action.Action;
import com.bjsp123.rl2.ai.action.ActionDescendStairs;
import com.bjsp123.rl2.ai.action.ActionLibrary;
import com.bjsp123.rl2.ai.action.ActionMoveToward;
import com.bjsp123.rl2.ai.action.ActionPickup;
import com.bjsp123.rl2.ai.action.ActionWait;
import com.bjsp123.rl2.ai.eval.CombatEval;
import com.bjsp123.rl2.ai.eval.ExplorationEval;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.ai.goal.GoalDescend;
import com.bjsp123.rl2.ai.goal.GoalPickupKnown;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level hardcoded decision tree for the SMART player-agent.
 *
 * <p>Branches fire in the user's exact priority order, first match wins. No
 * goal-score race at the top level. Inside each branch, scored utility argmax
 * picks the concrete action.
 *
 * <pre>
 *   if levelFullyExplored:                            → planDescend
 *   if enemiesInSight:
 *       if enemiesStronger AND canEscape:             → planFlee
 *       else:                                         → planFight
 *   if hpBelowThreshold AND healingAvailable:         → planHeal
 *   if usefulPowerupAvailable:                        → planPickupPowerup
 *   if betterOrConsumableItemAvailable:               → planPickupItem
 *   if recentlySeenEnemy:                             → planSearchLastKnown
 *                                                       → planExplore
 * </pre>
 *
 * <p>All item / mob classification reads stats, buffs, and behaviors - no item
 * type-string matching anywhere. See {@link ItemEval} and
 * {@link CombatEval#canEscapeFrom}.
 *
 * <p>Per-branch plan helpers reuse {@link ActionLibrary}'s stats-based
 * enumerators. The user explicitly approved keeping scored selection WITHIN
 * each branch; what's gone is the goal-vs-goal score race at the top.
 */
public final class Decider {

    private Decider() {}

    /** HP fraction below which {@code planHeal} is allowed to fire. The
     *  threshold is the same one {@link ItemEval#wouldHealHelp} uses internally
     *  so the action stays applicable for the entire window where the goal is
     *  willing to fire. */
    public static final double HP_LOW_THRESHOLD = 0.7;

    /** Consecutive no-reveal decisions after which the agent abandons frontier
     *  exploration and commits to known, reachable stairs (see the
     *  "descend-stalled" branch in {@link #decide}). Catches a hard stall where
     *  {@code nearestExploreTarget} keeps returning a frontier the agent can
     *  never actually reveal. */
    public static final int EXPLORE_STALL_LIMIT = 60;

    /** Decisions spent on one floor after which - once a reachable down-stair is
     *  known - the agent commits to descending rather than keep grinding the
     *  floor. Catches the slow-dwell pathology (the agent DOES reveal tiles, just
     *  ~10x too slowly via explore<->loot ping-pong, so {@link #EXPLORE_STALL_LIMIT}
     *  never trips). Set well above a normal floor's decision count (~10-50) and
     *  far below the observed dwell (~700-2500), so productive exploration is
     *  unaffected. */
    public static final int EXPLORE_FATIGUE_LIMIT = 250;

    /** Return the chosen {@link Action} for this tick. Never null.
     *
     *  <p>The "no enemies in sight" leaves (heal / pickup-powerup / pickup-item /
     *  search-last-known / explore) fall through if their branch's argmax
     *  produces a Wait - matching the user's implicit "if it can help" wording.
     *  The top-level branches (fully-explored / enemies-in-sight) do NOT fall
     *  through; if fight has no applicable action the agent waits and the
     *  wait-streak escape in {@link SmartAi} handles deadlocks. */
    public static Action decide(WorldState s) {
        // 0. Locked-exit floor (e.g. Mirrormatch): the way down only appears
        //    once every enemy is dead, so clearing the floor outranks
        //    everything - fight what's in sight, hunt the rest. Never wait for
        //    stairs that won't come, and never flee the very foes we must kill
        //    to leave.
        if (mustClearToExit(s)) {
            if (!s.visibleEnemies.isEmpty()) { LAST_BRANCH.set("clear-fight"); return planFight(s); }
            Point foe = nearestEnemyTile(s);
            if (foe != null) {
                LAST_BRANCH.set("clear-hunt");
                return new ActionMoveToward(foe, "clear-exit", 0.6, true);
            }
            // No enemy remains; the exit stamps this turn - fall through to descend.
        }

        // 1. Level fully explored → descend.
        if (levelFullyExplored(s)) { LAST_BRANCH.set("descend"); return planDescend(s); }

        // 2. Enemies in sight.
        if (!s.visibleEnemies.isEmpty()) {
            if (anyEnemyStrongerThanPlayer(s)
                    && CombatEval.canEscapeFrom(s.mob, s.visibleEnemies, s.level)) {
                LAST_BRANCH.set("flee");
                return planFlee(s);
            }
            Action fight = planFight(s);
            if (!isWait(fight)) { LAST_BRANCH.set("fight"); return fight; }
            // Visible but un-engageable: every fight option (melee / ranged /
            // charge / reachable close-step) came back empty - the foe is a
            // flyer across a chasm, sealed off, or otherwise un-hittable. Fall
            // through to descend / explore instead of freezing in place (which
            // was burning the whole turn budget on a fight<->stall oscillation).
        }

        // 2.5 Exploration stalled OR we've dwelt too long on this floor, and a
        //     reachable down-stair is known -> commit to descent instead of
        //     grinding the floor. Placed after combat so the agent still fights
        //     visible enemies rather than walking past them; skipped while stairs
        //     are unknown/unreachable (so it keeps exploring to find a route, and
        //     never hijacks a locked-exit floor where stairsDown is null).
        if (s.memory != null && s.stairsReachable()
                && (s.memory.exploreStallTurns > EXPLORE_STALL_LIMIT
                    || s.memory.ticksOnCurrentLevel > EXPLORE_FATIGUE_LIMIT)) {
            LAST_BRANCH.set("descend-stalled");
            return planDescend(s);
        }

        // 3. No enemies in sight - try each leaf branch; fall through on Wait.
        Action a;
        if (s.hpFrac < HP_LOW_THRESHOLD) {
            a = planHeal(s);
            if (!isWait(a)) { LAST_BRANCH.set("heal"); return a; }
        }
        a = planPickupPowerup(s);
        if (!isWait(a)) { LAST_BRANCH.set("pickup-powerup"); return a; }

        a = planPickupItem(s);
        if (!isWait(a)) { LAST_BRANCH.set("pickup-item"); return a; }

        a = planSearchLastKnown(s);
        if (!isWait(a)) { LAST_BRANCH.set("search"); return a; }

        LAST_BRANCH.set("explore");
        return planExplore(s);
    }

    /** Diagnostic - which branch decide() last fired. Read by SmartAi for the
     *  per-tick trace and decision counter. */
    public static final ThreadLocal<String> LAST_BRANCH = ThreadLocal.withInitial(() -> "?");

    /** Diagnostic snapshot of the candidate list pickBest just ran. One entry
     *  per Action with its name, intent detail, utility, and isApplicable
     *  result. Cleared each call to pickBest. */
    public static final ThreadLocal<List<CandidateScore>> LAST_CANDIDATES =
            ThreadLocal.withInitial(java.util.ArrayList::new);

    public record CandidateScore(String name, String detail, double utility, boolean applicable) {}

    private static boolean isWait(Action a) { return a == null || "wait".equals(a.name()); }

    /* ---------- branch predicates ---------- */

    private static boolean levelFullyExplored(WorldState s) {
        return ExplorationEval.nearestExploreTarget(s.mob, s.level, s.memory) == null;
    }

    private static boolean anyEnemyStrongerThanPlayer(WorldState s) {
        for (Mob e : s.visibleEnemies) {
            if (CombatEval.isStrongerThan(e, s.mob)) return true;
        }
        return false;
    }

    /** True while on a floor whose exit only unlocks once every enemy is dead
     *  and that exit hasn't stamped yet (e.g. the Mirrormatch special floor).
     *  The agent must clear it rather than wait for stairs that won't appear. */
    private static boolean mustClearToExit(WorldState s) {
        return s.level != null && s.level.exitUnlocksOnClear && s.level.stairsDown == null;
    }

    /** Position of the nearest living enemy anywhere on the level (not just in
     *  sight) - used to hunt the last foes keeping a locked exit sealed. Skips
     *  the agent itself, inanimates, and the agent's own allies. Null when no
     *  enemy remains. */
    private static Point nearestEnemyTile(WorldState s) {
        Point me = s.mob.position;
        if (me == null) return null;
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (Mob m : s.level.mobs) {
            if (m == null || m == s.mob || m.hp <= 0) continue;
            if (m.behavior == Mob.Behavior.INANIMATE) continue;
            if (m.position == null) continue;
            if (MobSystem.isAlly(s.mob, m)) continue;
            int d = Math.abs(m.position.tileX() - me.tileX())
                    + Math.abs(m.position.tileY() - me.tileY());
            if (d < bestD) { bestD = d; best = m.position; }
        }
        return best;
    }

    // Predicates for the leaf branches are inlined into `decide` via fall-through:
    // a branch's plan helper returns ActionWait when no candidate is applicable
    // (no reachable / useful item, etc.), and decide moves to the next leaf.

    /* ---------- per-branch planners ---------- */

    /** On the stairs → descend immediately. Otherwise step toward them. */
    static Action planDescend(WorldState s) {
        if (GoalDescend.onStairs(s)) return new ActionDescendStairs();
        Point dest = s.memory != null && s.memory.stairsDown != null
                ? s.memory.stairsDown : s.level.stairsDown;
        if (dest == null) return new ActionWait();
        return new ActionMoveToward(dest, "descend", 0.6, true);
    }

    /** Scored argmax over every escape option: teleport tools, smoke bombs,
     *  HASTED/PHASE/INVISIBLE buff tools, jump tools, plain retreat-step
     *  (favored when speed advantage). */
    static Action planFlee(WorldState s) {
        List<Action> candidates = new ArrayList<>();
        ActionLibrary.addTeleportEscapes(s, candidates);
        ActionLibrary.addSmokeThrowEscapes(s, candidates);
        ActionLibrary.addEscapeBuffTools(s, candidates);
        ActionLibrary.addJumpAway(s, candidates);
        ActionLibrary.addRetreatStep(s, candidates);
        // Adjacent enemy on top of us? Whacking them is the only escape.
        ActionLibrary.addMeleeAdjacent(s, candidates);
        return pickBest(s, candidates);
    }

    /** Scored argmax over melee / ranged / charge / wand / close-step. */
    static Action planFight(WorldState s) {
        List<Action> candidates = new ArrayList<>();
        ActionLibrary.addMeleeAdjacent(s, candidates);
        ActionLibrary.addThrowsAtNearest(s, candidates);
        ActionLibrary.addWandAtNearest(s, candidates);
        ActionLibrary.addGrappleNearest(s, candidates);
        ActionLibrary.addChargeNearest(s, candidates);
        ActionLibrary.addJumpToward(s, candidates);
        ActionLibrary.addSelfBuff(s, candidates);
        ActionLibrary.addAbility(s, candidates);
        ActionLibrary.addCloseStep(s, candidates);
        return pickBest(s, candidates);
    }

    /** Scored argmax over healing options. */
    static Action planHeal(WorldState s) {
        List<Action> candidates = new ArrayList<>();
        ActionLibrary.addHealingConsumables(s, candidates);
        // Known floor HP_UP powerups - walk to them.
        if (s.memory != null) {
            for (Map.Entry<Point, Item> e : s.memory.knownItems.entrySet()) {
                Item it = e.getValue();
                if (!ItemEval.isHealingItem(it)) continue;
                Point t = e.getKey();
                if (t.equals(s.mob.position)) continue;   // POWERUP auto-consumes on entry
                int d = WorldState.chebyshev(s.mob.position, t);
                candidates.add(new ActionMoveToward(t,
                        "pickup heal",
                        Math.max(0.4, 0.85 - 0.03 * d),
                        true));
            }
        }
        return pickBest(s, candidates);
    }

    /** Scored argmax over moves to known useful powerups. */
    static Action planPickupPowerup(WorldState s) {
        List<Action> candidates = new ArrayList<>();
        ActionLibrary.addPickupUsefulPowerups(s, candidates);
        return pickBest(s, candidates);
    }

    /** Scored argmax over consumable + upgrade pickups. */
    static Action planPickupItem(WorldState s) {
        List<Action> candidates = new ArrayList<>();
        ActionLibrary.addPickupConsumableOrUpgrade(s, candidates);
        return pickBest(s, candidates);
    }

    /** Step toward the last-known enemy tile - or Wait so decide falls
     *  through to explore. */
    static Action planSearchLastKnown(WorldState s) {
        Point dest = s.lastKnownEnemyTile;
        if (dest == null) return new ActionWait();
        if (s.memory == null || s.memory.lastSeenThreat == null
                || s.memory.lastSeenThreat.isEmpty()) return new ActionWait();
        return new ActionMoveToward(dest, "search last-seen", 0.6, true);
    }

    /** Step toward the nearest walkable cell adjacent to unknown floor (per the
     *  user's spec: "the *nearest* square that is adjacent to unexplored floor.
     *  Not random. Nearest"). Last leaf - if nothing left to explore, descend. */
    static Action planExplore(WorldState s) {
        // Committed frontier (cached on MobMemory) so the agent walks the whole way
        // to one target instead of re-picking the BFS-nearest each tick and flipping
        // between two equidistant cells. Also drains unrevealable pockets on arrival.
        Point f = ExplorationEval.committedExploreTarget(s.mob, s.level, s.memory);
        if (f == null) return planDescend(s);
        return new ActionMoveToward(f, "explore", 0.6, true);
    }

    /** Argmax of applicable actions by utility; ActionWait if none are
     *  applicable. Mirrors {@link com.bjsp123.rl2.ai.Planner#plan} but stays
     *  local to the branch so the top-level GoalSelector race is gone. */
    private static Action pickBest(WorldState s, List<Action> candidates) {
        Action best = null;
        double bestU = -Double.MAX_VALUE;
        List<CandidateScore> scoreList = LAST_CANDIDATES.get();
        scoreList.clear();
        for (Action a : candidates) {
            boolean applicable = a.isApplicable(s);
            double u = applicable ? a.utility(s) : 0.0;
            scoreList.add(new CandidateScore(a.name(), a.intentDetail(), u, applicable));
            if (!applicable) continue;
            if (u > bestU) { bestU = u; best = a; }
        }
        return best != null ? best : new ActionWait();
    }
}
