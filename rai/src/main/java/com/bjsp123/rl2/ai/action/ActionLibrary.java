package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.CombatEval;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.ai.goal.Goal;
import com.bjsp123.rl2.ai.goal.GoalActivateBeacon;
import com.bjsp123.rl2.ai.goal.GoalDescend;
import com.bjsp123.rl2.ai.goal.GoalEquipBetter;
import com.bjsp123.rl2.ai.goal.GoalExplore;
import com.bjsp123.rl2.ai.goal.GoalPickupKnown;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick, produces every {@link Action} that could plausibly satisfy the active
 * {@link Goal}. Filtering by goal keeps branching factor sane: KILL doesn't
 * enumerate "walk to that floor item", EXPLORE doesn't enumerate "fire wand".
 *
 * <p>{@link com.bjsp123.rl2.ai.Planner} picks the highest-utility entry and the
 * SmartAi entry executes step 0.
 */
public final class ActionLibrary {

    private ActionLibrary() {}

    public static List<Action> enumerate(WorldState s, Goal goal) {
        List<Action> out = new ArrayList<>();
        if (goal == null) { out.add(new ActionWait()); return out; }
        String n = goal.name();
        switch (n) {
            case "SURVIVE", "HEAL" -> {
                // SURVIVE means: get out of range of the threat, then heal or
                // explore. Order matches that intent:
                //   1. Drink an immediate-cure potion if held.
                //   2. Escape: jump / blink / phase / retreat - whichever puts
                //      meaningful distance or LOS-break between us and the threat.
                //   3. Adjacent enemy: melee it (finishing is a valid escape).
                //   4. Wait as last resort - the wait-streak escape kicks in
                //      after a few of these.
                // Offensive ranged options (throws, wands, charges, chasing the
                // last-known threat) are NOT survive moves and don't appear here.
                // If the agent wants to keep fighting, KILL re-asserts next tick.
                addHealingPotions(s, out);
                addJumpAway(s, out);
                addEscapeWandAtNearest(s, out);
                addSelfBuff(s, out);
                addRetreatStep(s, out);
                addMeleeAdjacent(s, out);
                addAbility(s, out);
                addWait(out);
            }
            case "KILL" -> {
                addMeleeAdjacent(s, out);
                // Heal mid-combat when HP drops - matches the MOB AI which fires
                // tryUseInventoryItem every turn regardless of goal. wouldDrinkHelp
                // gates entry at hp < 0.7 * maxHp so fresh fighters don't waste it.
                addHealingPotions(s, out);
                addThrowsAtNearest(s, out);
                addWandAtNearest(s, out);
                addGrappleNearest(s, out);
                addChargeNearest(s, out);
                addJumpToward(s, out);
                addSelfBuff(s, out);
                addAbility(s, out);
                addCloseStep(s, out);
                addChaseLastKnown(s, out);
            }
            case "FLEE" -> {
                addRetreatStep(s, out);
                addAbility(s, out);
                addThrowsAtNearest(s, out);
                addMeleeAdjacent(s, out);
            }
            case "EAT" -> {
                addFood(s, out);
                addWait(out);
            }
            case "EQUIP" -> {
                Item best = GoalEquipBetter.bestBagUpgrade(s);
                if (best != null) out.add(new ActionEquip(best));
                addWait(out);
            }
            case "PICKUP" -> {
                Point t = GoalPickupKnown.bestPickupTarget(s);
                if (t != null) {
                    if (t.equals(s.mob.position)) out.add(new ActionPickup());
                    else out.add(new ActionMoveToward(t, "pickup", 0.6));
                }
                addWait(out);
            }
            case "EXPLORE" -> {
                Point f = GoalExplore.frontier(s);
                // useBfsStep=true: step source is the same BFS that picked the
                // frontier, so a "reachable" frontier always produces a step.
                // Also bypasses the ONETIME_DOOR safety bail - exploring a low-HP
                // agent should walk through onetime doors to find heals beyond them.
                if (f != null) out.add(new ActionMoveToward(f, "explore", 0.6, true));
                addWait(out);
            }
            case "BEACON" -> {
                Point adj = GoalActivateBeacon.adjacentTo(s);
                if (adj != null) out.add(new ActionMoveToward(adj, "activate beacon", 0.55));
                addWait(out);
            }
            case "DESCEND" -> {
                if (GoalDescend.onStairs(s)) {
                    out.add(new ActionDescendStairs());
                } else {
                    Point dest = s.memory.stairsDown != null ? s.memory.stairsDown : s.level.stairsDown;
                    if (dest != null) {
                        // useBfsStep=true: same BFS rules as GoalDescend.computeStairsReachable,
                        // so a reachable-per-BFS staircase always produces a step. Also
                        // bypasses ONETIME_DOOR safety so the agent commits to the descent.
                        out.add(new ActionMoveToward(dest, "descend", 0.6, true));
                    }
                }
                addWait(out);
            }
            default -> out.add(new ActionWait());
        }
        if (out.isEmpty()) out.add(new ActionWait());
        return out;
    }

    /* ---------- helpers ---------- */

    public static void addMeleeAdjacent(WorldState s, List<Action> out) {
        for (Mob e : s.visibleEnemies) {
            if (s.isAdjacent(e)) out.add(new ActionMelee(e));
        }
    }

    public static void addCloseStep(WorldState s, List<Action> out) {
        // Enumerate a close-step toward each visible enemy - planner picks the
        // highest-utility one, which favours wounded targets (matches the way a
        // human player walks toward the 20%-HP imp instead of the full-HP one).
        if (s.visibleEnemies.isEmpty() && s.nearestEnemy == null) return;
        java.util.List<Mob> targets = s.visibleEnemies.isEmpty()
                ? java.util.List.of(s.nearestEnemy)
                : s.visibleEnemies;
        double stalemateBoost = 0.0;
        if (s.memory != null && s.memory.stalemateTurns > 5) {
            stalemateBoost = Math.min(0.25, (s.memory.stalemateTurns - 5) * 0.03);
        }
        // Inventory-derived fast-close inventory: charge, jump, grapple, teleport.
        // Each lets the agent reach the enemy in ~1 turn instead of walking the
        // full chebyshev distance. HASTED buff halves effective walking cost.
        boolean hasCharge   = bagHasReadyOfBehavior(s.mob, Item.UseBehavior.CHARGE);
        boolean hasJump     = bagHasReadyOfBehavior(s.mob, Item.UseBehavior.JUMP);
        boolean hasGrapple  = bagHasReadyOfBehavior(s.mob, Item.UseBehavior.GRAPPLE);
        boolean hasTeleport = bagHasReadyTeleportTool(s.mob);
        boolean hasted = com.bjsp123.rl2.logic.BuffSystem.hasBuff(s.mob,
                com.bjsp123.rl2.model.Buff.BuffType.HASTED);
        for (Mob e : targets) {
            if (e == null || e.position == null || e.hp <= 0) continue;
            double maxHp = Math.max(1.0, e.effectiveStats().maxHp);
            double hpFrac = e.hp / maxHp;
            double woundedBonus = hpFrac < 0.4 ? (0.4 - hpFrac) * 0.5 : 0.0;
            int chebDist = WorldState.chebyshev(s.mob.position, e.position);
            int turnsToClose;
            if (hasCharge || hasGrapple || hasTeleport) {
                turnsToClose = 1;
            } else if (hasJump) {
                turnsToClose = Math.max(1, chebDist - 2);
            } else if (hasted) {
                turnsToClose = Math.max(1, (chebDist + 1) / 2);
            } else {
                turnsToClose = chebDist;
            }
            double closingPenalty = 0.08 * Math.max(0, turnsToClose - 1);
            double base = 0.58 + stalemateBoost + woundedBonus - closingPenalty;
            String label = "close on "
                    + (e.name != null ? e.name : e.mobType != null ? e.mobType : "enemy");
            out.add(new ActionMoveToward(e.position, label, base));
        }
    }

    /** True if the agent carries an item with the given UseBehavior whose
     *  charge is ready (or charge-free). */
    private static boolean bagHasReadyOfBehavior(Mob mob, Item.UseBehavior beh) {
        if (mob.inventory == null || mob.inventory.bag == null) return false;
        for (Item it : mob.inventory.bag) {
            if (it == null) continue;
            if (it.useBehavior != beh) continue;
            if (it.baseChargeMax > 0 && it.charge < 1f) continue;
            return true;
        }
        return false;
    }

    /** True if the agent carries any ready teleport tool (TELEPORT-use or
     *  TELEPORT-wand). */
    private static boolean bagHasReadyTeleportTool(Mob mob) {
        if (mob.inventory == null || mob.inventory.bag == null) return false;
        for (Item it : mob.inventory.bag) {
            if (ItemEval.isTeleportTool(it)) return true;
        }
        return false;
    }

    /** When no enemy is visible but one was sighted, path toward the last known tile
     *  so we resume contact instead of falling through to EXPLORE/Wait. If we're
     *  already standing on that tile, sweep outward toward the nearest unknown or
     *  walkable neighbour - "search the last known location" rather than stand still.
     *
     *  <p>Also handles the "enemy is on an unreachable tile" case (flying mob on a
     *  CHASM border): if the exact lastKnownEnemyTile can't be pathfound to, walk
     *  to the nearest walkable cell adjacent to it instead, so we hover within
     *  attack/wand range until the enemy moves back into reach. */
    public static void addChaseLastKnown(WorldState s, List<Action> out) {
        if (s.nearestEnemy != null) return;
        if (s.lastKnownEnemyTile == null) return;
        Point dest = s.lastKnownEnemyTile;
        if (s.mob.position.equals(dest)) {
            dest = pickSearchSweepTarget(s);
            if (dest != null) out.add(new ActionMoveToward(dest, "search last-seen", 0.6));
            return;
        }
        // Path direct to the enemy tile first; if that fails (flying enemy over
        // chasm, etc.), pick the nearest walkable neighbour of the enemy tile.
        if (com.bjsp123.rl2.logic.Pathfinder.nextStep(s.level, s.mob, dest) == null) {
            Point reachable = nearestWalkableNeighbour(s, s.lastKnownEnemyTile);
            if (reachable != null) dest = reachable;
        }
        out.add(new ActionMoveToward(dest, "search last-seen", 0.6));
    }

    /** Pick a walkable tile adjacent to {@code target} that the SMART agent can
     *  pathfind to - used when the target tile itself is movement-blocked (CHASM
     *  for non-flying mobs, etc.). */
    static Point nearestWalkableNeighbour(WorldState s, Point target) {
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = target.tileX() + dx, y = target.tileY() + dy;
                if (x < 1 || y < 1 || x > s.level.width - 2 || y > s.level.height - 2) continue;
                if (s.level.tiles[x][y].blocksMovement()) continue;
                Point cand = new Point(x, y);
                if (com.bjsp123.rl2.logic.Pathfinder.nextStep(s.level, s.mob, cand) == null
                        && !s.mob.position.equals(cand)) continue;
                int d = WorldState.chebyshev(s.mob.position, cand);
                if (d < bestDist) { bestDist = d; best = cand; }
            }
        }
        return best;
    }

    /** From the last-known tile, pick the nearest unknown walkable cell to head to;
     *  if the whole level is known, pick a walkable tile a few cells from the
     *  last-known position so the agent circles instead of standing still. */
    static Point pickSearchSweepTarget(WorldState s) {
        if (s.memory == null) return null;
        Point me = s.mob.position;
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        if (s.memory.knownTiles != null) {
            for (int y = 1; y < s.level.height - 1; y++) {
                for (int x = 1; x < s.level.width - 1; x++) {
                    if (s.memory.knownTiles[x][y]) continue;
                    if (s.level.tiles[x][y].blocksMovement()) continue;
                    int d = WorldState.chebyshev(me, new Point(x, y));
                    if (d < bestDist) { bestDist = d; best = new Point(x, y); }
                }
            }
        }
        if (best != null) return best;
        // Fully-known level (arena): pick a walkable tile a few cells from here.
        for (int r = 3; r <= 5 && best == null; r++) {
            for (int dy = -r; dy <= r && best == null; dy++) {
                for (int dx = -r; dx <= r && best == null; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int x = me.tileX() + dx, y = me.tileY() + dy;
                    if (x < 1 || y < 1 || x > s.level.width - 2 || y > s.level.height - 2) continue;
                    if (!s.level.tiles[x][y].blocksMovement()) best = new Point(x, y);
                }
            }
        }
        return best;
    }

    /** Step to a tile that maximises distance from the nearest enemy (visible or
     *  recently sighted). Picks the farthest walkable tile inside vision range in
     *  the away-direction - clamping to the level border used to land the retreat
     *  on a wall tile, which made the action permanently un-applicable. */
    public static void addRetreatStep(WorldState s, List<Action> out) {
        Point threat = s.nearestEnemy != null ? s.nearestEnemy.position : s.lastKnownEnemyTile;
        if (threat == null) return;
        Point me = s.mob.position;
        int dx = Integer.signum(me.tileX() - threat.tileX());
        int dy = Integer.signum(me.tileY() - threat.tileY());
        if (dx == 0 && dy == 0) { dx = 1; dy = 0; }
        // Walk back from the ideal "6 tiles away" target if it's blocked or out of
        // bounds, so the action stays applicable even when the agent is near a wall.
        Point dest = null;
        for (int step = 6; step >= 2 && dest == null; step--) {
            int tx = me.tileX() + dx * step;
            int ty = me.tileY() + dy * step;
            if (tx < 1 || ty < 1 || tx > s.level.width - 2 || ty > s.level.height - 2) continue;
            if (s.level.tiles[tx][ty].blocksMovement()) continue;
            dest = new Point(tx, ty);
        }
        if (dest != null) out.add(new ActionMoveToward(dest, "retreat", 0.65));
    }

    public static void addHealingPotions(WorldState s, List<Action> out) {
        if (s.mob.inventory == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (ItemEval.wouldDrinkHelp(s.mob, it)) out.add(new ActionDrinkPotion(it));
        }
    }

    public static void addFood(WorldState s, List<Action> out) {
        if (s.mob.inventory == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (ItemEval.isUsefulFood(s.mob, it, s.satietyFrac)) out.add(new ActionEatFood(it));
        }
    }

    public static void addThrowsAtNearest(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.nearestEnemy == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.inventoryCategory != InventoryCategory.BOMB) continue;
            // Skip utility/zero-damage bombs (SMOKE_BOMB, etc.) - they obscure vision
            // without hurting the target, and a SMART agent that loses LOS to its
            // current target degrades to EXPLORE.
            if (it.damage <= 0) continue;
            if (com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, s.nearestEnemy.position)) {
                out.add(new ActionThrowAt(it, s.nearestEnemy.position));
            }
        }
    }

    public static void addWandAtNearest(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.nearestEnemy == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.useBehavior != UseBehavior.WAND) continue;
            if (!ItemEval.isFireableWand(it)) continue;
            // Skip TELEPORT-effect wands here - those are escape tools, scored
            // under addEscapeWandAtNearest with SURVIVE-tier utility.
            if (it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) continue;
            if (!com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, s.nearestEnemy.position)) continue;
            out.add(new ActionFireWandAt(it, s.nearestEnemy.position));
        }
    }

    /** TELEPORT-effect wands (BLINKSTONE) fired at an adjacent or near-adjacent
     *  threat blink the threat away - a defensive get-them-off-me action that
     *  belongs alongside JUMP and PHASE under SURVIVE. */
    public static void addEscapeWandAtNearest(WorldState s, List<Action> out) {
        if (s.mob.inventory == null) return;
        Point threatPos = s.nearestEnemy != null ? s.nearestEnemy.position : s.lastKnownEnemyTile;
        if (threatPos == null) return;
        if (!com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, threatPos)) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.useBehavior != UseBehavior.WAND) continue;
            if (it.wandEffect != com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) continue;
            if (!ItemEval.isFireableWand(it)) continue;
            out.add(new ActionEscapeWand(it, threatPos));
        }
    }

    public static void addGrappleNearest(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.nearestEnemy == null) return;
        if (s.nearestEnemyDist <= 1) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null || it.useBehavior != UseBehavior.GRAPPLE) continue;
            if (it.baseChargeMax > 0 && it.charge < 1f) continue;
            out.add(new ActionCastGrapple(it, s.nearestEnemy));
        }
    }

    public static void addChargeNearest(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.nearestEnemy == null) return;
        if (s.nearestEnemyDist <= 1) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null || it.useBehavior != UseBehavior.CHARGE) continue;
            if (it.baseChargeMax > 0 && it.charge < 1f) continue;
            out.add(new ActionCastCharge(it, s.nearestEnemy));
        }
    }

    public static void addAbility(WorldState s, List<Action> out) {
        if (s.mob.abilities != null && !s.mob.abilities.isEmpty()) out.add(new ActionUseAbility());
    }

    /** Self-buff items (JADE_CRAB → SHIELDED, JADE_FISH → PHASE, etc.) - APPLYBUFF
     *  category that isn't healing. Enumerate under KILL so the mage actually invokes
     *  her jade tools before engaging instead of leaving them in her bag. */
    public static void addSelfBuff(WorldState s, List<Action> out) {
        if (s.mob.inventory == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.useBehavior != UseBehavior.APPLYBUFF) continue;
            if (it.damage > 0) continue;
            if (it.baseChargeMax > 0 && it.charge < 1f) continue;
            com.bjsp123.rl2.model.Buff.BuffType primary = it.primaryBuff();
            if (primary == null) continue;
            if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(s.mob, primary)) continue;
            // Skip REGENERATION here - that path runs through addHealingPotions /
            // SURVIVE, where the HP-threshold gate lives.
            if (primary == com.bjsp123.rl2.model.Buff.BuffType.REGENERATION) continue;
            out.add(new ActionDrinkPotion(it));
        }
    }

    /** Use a JUMP tool (rogue's FROG) to leap AWAY from the nearest threat. Enumerated
     *  under SURVIVE so the rogue spends her FROG charges on escape, not gap-close. */
    public static void addJumpAway(WorldState s, List<Action> out) {
        if (s.mob.inventory == null) return;
        Point threat = s.nearestEnemy != null ? s.nearestEnemy.position : s.lastKnownEnemyTile;
        if (threat == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.useBehavior != UseBehavior.JUMP) continue;
            if (it.baseChargeMax > 0 && it.charge < 1f) continue;
            Point landing = pickJumpAwayLanding(s, threat);
            if (landing != null) out.add(new ActionCastJump(it, landing));
        }
    }

    static Point pickJumpAwayLanding(WorldState s, Point threat) {
        Point me = s.mob.position;
        int dx = Integer.signum(me.tileX() - threat.tileX());
        int dy = Integer.signum(me.tileY() - threat.tileY());
        if (dx == 0 && dy == 0) { dx = 1; dy = 0; }
        // Step back from a 2-tile leap toward 1 if blocked - matches FROG's range=2.
        for (int step = 2; step >= 1; step--) {
            int x = me.tileX() + dx * step;
            int y = me.tileY() + dy * step;
            if (x < 1 || y < 1 || x > s.level.width - 2 || y > s.level.height - 2) continue;
            if (s.level.tiles[x][y].blocksMovement()) continue;
            return new Point(x, y);
        }
        return null;
    }

    /** Use a JUMP tool (rogue's FROG) to leap toward the nearest enemy when not
     *  yet adjacent - faster gap-close than walking and bypasses kiting. */
    public static void addJumpToward(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.nearestEnemy == null || s.nearestEnemy.position == null) return;
        if (s.nearestEnemyDist <= 1) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.useBehavior != UseBehavior.JUMP) continue;
            if (it.baseChargeMax > 0 && it.charge < 1f) continue;
            // Aim adjacent to the enemy: pick a walkable 8-neighbour close to us.
            Point landing = pickJumpLanding(s);
            if (landing != null) out.add(new ActionCastJump(it, landing));
        }
    }

    static Point pickJumpLanding(WorldState s) {
        Point me = s.mob.position;
        Point t = s.nearestEnemy.position;
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = t.tileX() + dx, y = t.tileY() + dy;
                if (x < 1 || y < 1 || x > s.level.width - 2 || y > s.level.height - 2) continue;
                if (s.level.tiles[x][y].blocksMovement()) continue;
                int d = WorldState.chebyshev(me, new Point(x, y));
                if (d < bestDist) { bestDist = d; best = new Point(x, y); }
            }
        }
        return best;
    }

    public static void addWait(List<Action> out) { out.add(new ActionWait()); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /* ---------- escape-tool enumerators (stats-based, no item identity) ---------- */

    /** Enumerate throws of any {@link ItemEval#isSmokeBomb} item onto the agent's
     *  own tile - the cloud immediately breaks LOS for every visible threat that
     *  lacks ESP. */
    public static void addSmokeThrowEscapes(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.mob.inventory.bag == null) return;
        if (s.visibleEnemies.isEmpty()) return;
        for (Item it : s.mob.inventory.bag) {
            if (!ItemEval.isSmokeBomb(it)) continue;
            out.add(new ActionThrowAt(it, s.mob.position));
        }
    }

    /** Enumerate every TELEPORT-effect wand in bag fired at the nearest visible
     *  threat (the wand's resolver moves the threat away, breaking engagement). */
    public static void addTeleportEscapes(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.mob.inventory.bag == null) return;
        Point threat = s.nearestEnemy != null ? s.nearestEnemy.position : s.lastKnownEnemyTile;
        if (threat == null) return;
        if (!com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, threat)) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (!ItemEval.isTeleportTool(it)) continue;
            if (it.useBehavior == UseBehavior.WAND) {
                out.add(new ActionEscapeWand(it, threat));
            }
        }
    }

    /** Enumerate every APPLYBUFF / DRINK tool in bag that would grant an escape
     *  buff (HASTED / INVISIBLE / PHASE) the user doesn't already have, with
     *  charge ready. */
    public static void addEscapeBuffTools(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.mob.inventory.bag == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (!ItemEval.isReadyEscapeBuffTool(it)) continue;
            com.bjsp123.rl2.model.Buff.BuffType primary = it.primaryBuff();
            if (primary != null && com.bjsp123.rl2.logic.BuffSystem.hasBuff(s.mob, primary)) continue;
            out.add(new ActionDrinkPotion(it));
        }
    }

    /* ---------- pickup enumerators (stats-based) ---------- */

    /** Enumerate moves to any known floor item that is a useful powerup. */
    public static void addPickupUsefulPowerups(WorldState s, List<Action> out) {
        if (s.memory == null) return;
        Mob me = s.mob;
        for (java.util.Map.Entry<Point, Item> e : s.memory.knownItems.entrySet()) {
            Item it = e.getValue();
            if (!ItemEval.isUsefulPowerup(it, me)) continue;
            Point t = e.getKey();
            if (t.equals(me.position)) continue;   // POWERUP auto-consumes on entry
            int d = WorldState.chebyshev(me.position, t);
            out.add(new ActionMoveToward(t,
                    "pickup powerup",
                    Math.max(0.3, 0.8 - 0.02 * d),
                    true));
        }
    }

    /** Enumerate moves to any known floor item that is consumable OR a true
     *  equipment upgrade. SMART agents auto-pickup on tile entry via
     *  {@code MobSystem.stepTowardTarget}'s non-PLAYER pickup path, so we never
     *  need an explicit ActionPickup. */
    public static void addPickupConsumableOrUpgrade(WorldState s, List<Action> out) {
        if (s.memory == null) return;
        Mob me = s.mob;
        for (java.util.Map.Entry<Point, Item> e : s.memory.knownItems.entrySet()) {
            Item it = e.getValue();
            if (it == null) continue;
            boolean want = ItemEval.isConsumable(it)
                    || (it.inventoryCategory != null && it.inventoryCategory.isEquipment()
                        && com.bjsp123.rl2.ai.goal.GoalPickupKnown.isEquipmentUpgrade(me, it));
            if (!want) continue;
            Point t = e.getKey();
            if (t.equals(me.position)) continue;   // auto-picked-up; awaiting prune
            int d = WorldState.chebyshev(me.position, t);
            double value = it.getValue();
            out.add(new ActionMoveToward(t,
                    "pickup item",
                    Math.max(0.2, 0.6 + value * 0.01 - 0.015 * d),
                    true));
        }
    }
}
