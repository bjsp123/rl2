package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.ItemEval;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.util.List;

/**
 * Stats-based enumerators that append candidate {@link Action}s to a list. The
 * live {@link com.bjsp123.rl2.ai.Decider} calls the relevant {@code add*}
 * helpers per branch, then picks the highest-utility entry. No item-identity
 * matching - every filter reads {@code useBehavior} / {@code wandEffect} / buffs.
 */
public final class ActionLibrary {

    private ActionLibrary() {}

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
            // Reachability gate (SMART-timeout fix): a walk toward an enemy with
            // no reachable approach - a sealed pocket, or a flyer across a chasm -
            // makes no progress yet is still a non-Wait action, so the agent
            // spins on it forever and never falls through to a Wait (which is
            // what triggers SmartAi's deadlock escape). Skip the futile walk;
            // in-range ranged / charge / grapple candidates are added separately
            // and still cover a foe we can hit but not walk to.
            if (nearestWalkableNeighbour(s, e.position) == null) continue;
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

    /** Step back from an adjacent threat so the next turn can throw a damaging
     *  bomb at range. Only enumerated under FIGHT when (a) the agent carries a
     *  ready damage bomb, (b) a hostile sits within 2 tiles, and (c) the
     *  expected bomb damage exceeds the agent's expected melee damage on that
     *  same target. Solves the rogue's "commits to melee even with a heavy
     *  bomb in hand" pattern - the planner now has a "back up and throw"
     *  candidate scored off the bomb's expected hit, not the flat 0.65 of a
     *  pure flee step. */
    public static void addRetreatToThrowBomb(WorldState s, List<Action> out) {
        if (s.mob.inventory == null || s.nearestEnemy == null) return;
        if (s.nearestEnemyDist > 2) return;
        // Pick the strongest available damage bomb and use its expected damage
        // to score the retreat. If we have several, the best one wins.
        Item bestBomb = null;
        double bestDmg = 0.0;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (it.inventoryCategory != InventoryCategory.BOMB) continue;
            if (it.damage <= 0) continue;
            double dmg = com.bjsp123.rl2.ai.eval.CombatEval.expectedAttackValue(
                    s.mob, s.nearestEnemy, it,
                    com.bjsp123.rl2.ai.eval.CombatEval.AttackKind.THROW);
            if (dmg > bestDmg) { bestDmg = dmg; bestBomb = it; }
        }
        if (bestBomb == null || bestDmg <= 0) return;
        // Only retreat-to-throw when the bomb's expected hit exceeds melee.
        // Against weak / lightly-armored foes the rogue just whacks them.
        double meleeDmg = com.bjsp123.rl2.ai.eval.CombatEval.expectedAttackValue(
                s.mob, s.nearestEnemy, null,
                com.bjsp123.rl2.ai.eval.CombatEval.AttackKind.MELEE);
        if (meleeDmg >= bestDmg) return;
        // Reuse the same "walk back along the away-vector" target the flee
        // branch uses; if no retreat tile exists (boxed against a wall),
        // this turn falls through to melee instead.
        Point threat = s.nearestEnemy.position;
        Point me = s.mob.position;
        int dx = Integer.signum(me.tileX() - threat.tileX());
        int dy = Integer.signum(me.tileY() - threat.tileY());
        if (dx == 0 && dy == 0) { dx = 1; dy = 0; }
        Point dest = null;
        for (int step = 4; step >= 2 && dest == null; step--) {
            int tx = me.tileX() + dx * step;
            int ty = me.tileY() + dy * step;
            if (tx < 1 || ty < 1 || tx > s.level.width - 2 || ty > s.level.height - 2) continue;
            if (s.level.tiles[tx][ty].blocksMovement()) continue;
            dest = new Point(tx, ty);
        }
        if (dest == null) return;
        // Score parallels the bomb's own utility at d>2 minus a small one-turn
        // penalty for spending this turn on the back-step rather than the
        // throw - so melee against a foe whose bomb edge is marginal still
        // wins, but a heavy bomb dominates.
        double util = Math.min(0.95, 0.55 + bestDmg / 30.0);
        out.add(new ActionMoveToward(dest, "retreat-to-throw", util));
    }

    /** Healing consumables held in the bag: beneficial potions to drink, plus
     *  regen-granting food to eat when the agent is hurt (see {@link ItemEval#wouldHealHelp}). */
    public static void addHealingConsumables(WorldState s, List<Action> out) {
        if (s.mob.inventory == null) return;
        for (Item it : s.mob.inventory.bag) {
            if (it == null) continue;
            if (ItemEval.wouldDrinkHelp(s.mob, it)) {
                out.add(new ActionDrinkPotion(it));
            } else if (it.useBehavior == UseBehavior.EAT && ItemEval.wouldHealHelp(s.mob, it)) {
                out.add(new ActionEatFood(it));
            }
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
            // Skip TELEPORT-effect wands here - those are defensive escape tools,
            // enumerated as blinks by addTeleportEscapes, not offensive fire.
            if (it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) continue;
            if (!com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, s.nearestEnemy.position)) continue;
            out.add(new ActionFireWandAt(it, s.nearestEnemy.position));
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
            // Skip REGENERATION here - that path runs through addHealingConsumables /
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
     *  equipment upgrade. A non-PLAYER SMART mob auto-picks-up on tile entry via
     *  {@code MobSystem.stepTowardTarget}, but the player's auto-explore (a
     *  PLAYER-behaviour mob driven by this planner) does NOT - so when the agent
     *  is standing on the item we emit an explicit {@link ActionPickup}. */
    public static void addPickupConsumableOrUpgrade(WorldState s, List<Action> out) {
        if (s.memory == null) return;
        Mob me = s.mob;
        for (java.util.Map.Entry<Point, Item> e : s.memory.knownItems.entrySet()) {
            Item it = e.getValue();
            if (it == null) continue;
            boolean want = ItemEval.isConsumable(it)
                    || (it.inventoryCategory != null && it.inventoryCategory.isEquipment()
                        && isEquipmentUpgrade(me, it));
            if (!want) continue;
            Point t = e.getKey();
            if (t.equals(me.position)) {
                // Standing on it: take it. For a non-PLAYER mob that auto-picked
                // up on entry the item is already gone, so ActionPickup is
                // inapplicable and ignored; for the player's auto-explore this is
                // what actually grabs the item instead of ping-ponging beside it.
                out.add(new ActionPickup());
                continue;
            }
            int d = WorldState.chebyshev(me.position, t);
            double value = it.getValue();
            out.add(new ActionMoveToward(t,
                    "pickup item",
                    Math.max(0.2, 0.6 + value * 0.01 - 0.015 * d),
                    true));
        }
    }

    /** True if {@code it} beats the agent's current best equipped piece in the
     *  same category, so floor-pickup and bag-equip decisions agree on what's
     *  an upgrade. (Merged from the former {@code GoalPickupKnown} helper.) */
    private static boolean isEquipmentUpgrade(Mob mob, Item it) {
        if (mob == null || mob.inventory == null) return true;
        var cat = it.inventoryCategory;
        double candScore = ItemEval.equipmentScore(it, mob);
        double best = 0.0;
        int n = com.bjsp123.rl2.model.Inventory.positionCount(cat);
        for (int i = 0; i < n; i++) {
            Item slot = mob.inventory.equipped(cat, i);
            if (slot != null) {
                best = Math.max(best, ItemEval.equipmentScore(slot, mob));
            }
        }
        return candScore > best;
    }
}
