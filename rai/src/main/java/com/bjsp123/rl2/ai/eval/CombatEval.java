package com.bjsp123.rl2.ai.eval;

import com.bjsp123.rl2.logic.LevelUtilities;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.StatBlock;

/**
 * Combat heuristics shared by goals and actions. Pure functions over rlib types -
 * no caching, no allocation per query.
 *
 * <p>Numbers here are intentionally rough: the GOAP planner doesn't need exact
 * damage rolls, just a consistent ordering so "kite the bigger threat" beats
 * "close on the weaker one" reliably.
 */
public final class CombatEval {

    private CombatEval() {}

    /** Expected damage per attack ignoring armor, accuracy/evasion folded in. */
    public static double expectedHit(Mob attacker, Mob target) {
        StatBlock a = attacker.effectiveStats();
        StatBlock d = target.effectiveStats();
        double hitChance = hitChance(a.accuracy, d.evasion);
        double dmg = a.damage.average();
        double armor = d.armor.average();
        return hitChance * Math.max(0.0, dmg - armor);
    }

    /** Effective damage per game tick - higher = bigger threat. Folds in attack cost. */
    public static double dpsEstimate(Mob attacker, Mob target) {
        double cost = Math.max(1, attacker.effectiveStats().attackCost);
        return expectedHit(attacker, target) * (100.0 / cost);
    }

    /** Heuristic accuracy/evasion model - matches the curve MobSystem uses. */
    public static double hitChance(int accuracy, int evasion) {
        double diff = accuracy - evasion;
        return Math.max(0.05, Math.min(0.95, 0.5 + 0.05 * diff));
    }

    /**
     * Symmetric threat rating in [0, ~1]: how scary {@code target} is to {@code viewer}.
     * Combines target DPS vs viewer HP, viewer DPS vs target HP, and HP fraction.
     */
    public static double threatRating(Mob viewer, Mob target) {
        if (viewer == null || target == null || target.hp <= 0) return 0.0;
        StatBlock vs = viewer.effectiveStats();
        double viewerHp = Math.max(1.0, viewer.hp);
        double targetHp = Math.max(1.0, target.hp);
        double incoming = dpsEstimate(target, viewer);
        double outgoing = dpsEstimate(viewer, target);
        double turnsTillIDie = viewerHp / Math.max(0.01, incoming);
        double turnsTillTheyDie = targetHp / Math.max(0.01, outgoing);
        double ratio = turnsTillTheyDie / Math.max(0.01, turnsTillIDie);
        return Math.min(1.0, 0.5 * ratio + 0.5 * (1.0 - viewer.hp / Math.max(1.0, vs.maxHp)));
    }

    /** True if {@code target}'s expected hit alone could drop viewer this tick. */
    public static boolean canOhko(Mob target, Mob viewer) {
        return expectedHit(target, viewer) >= viewer.hp;
    }

    /** Sum of threat ratings from every visible enemy weighted by inverse distance. */
    public static double aggregateThreat(Mob viewer, java.util.List<Mob> visibleEnemies) {
        double sum = 0.0;
        for (Mob e : visibleEnemies) {
            int d = Math.max(1, Math.max(
                    Math.abs(viewer.position.tileX() - e.position.tileX()),
                    Math.abs(viewer.position.tileY() - e.position.tileY())));
            sum += threatRating(viewer, e) / Math.sqrt(d);
        }
        return Math.min(1.0, sum);
    }

    /** Whether viewer has clear LOS to target's tile - cheap delegation to LevelUtilities. */
    public static boolean los(Level level, Mob viewer, Mob target) {
        if (viewer == null || target == null || target.position == null) return false;
        return LevelUtilities.getLineOfSight(level, viewer, target.position);
    }

    /** True if {@code viewer} is meaningfully stronger than {@code target}.
     *  Uses {@link #threatRating} symmetrically: target is "stronger" if their
     *  threat rating against the viewer is well above 0.5 (i.e. they hurt us
     *  faster than we hurt them). */
    public static boolean isStrongerThan(Mob target, Mob viewer) {
        return threatRating(viewer, target) >= 0.6;
    }

    /** Composite "can the agent escape this fight?" calculation. Sums every
     *  escape capability the agent has - tools, buffs, speed delta, distance,
     *  terrain - and compares to a threshold. Single boolean answer but the
     *  weights inside spread credit across several modest advantages, so e.g.
     *  the rogue's natural speed edge over ants is enough on its own without
     *  needing a JADE_FISH. All inputs are stats-derived; no item identity.
     *
     *  <p>Lives here next to {@link #threatRating} so the planner can ask "is
     *  this enemy stronger?" and "can I escape?" in the same pass. */
    public static boolean canEscapeFrom(Mob player, java.util.List<Mob> visibleEnemies,
                                        Level level) {
        if (player == null || visibleEnemies == null || visibleEnemies.isEmpty()) return true;
        double score = 0.0;

        // Pick the nearest threat for distance / speed comparisons; count crowd
        // and ranged enemies as penalties.
        Mob nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        int rangedThreats = 0;
        for (Mob e : visibleEnemies) {
            if (e == null || e.hp <= 0 || e.position == null) continue;
            int d = Math.max(Math.abs(player.position.tileX() - e.position.tileX()),
                             Math.abs(player.position.tileY() - e.position.tileY()));
            if (d < nearestDist) { nearestDist = d; nearest = e; }
            StatBlock es = e.effectiveStats();
            if (es.rangedDamage != null && es.rangedDamage.max() > 0
                    && es.rangedDistance > 1) rangedThreats++;
        }
        if (nearest == null) return true;
        StatBlock ps = player.effectiveStats();
        StatBlock ns = nearest.effectiveStats();

        // 1. Tools in bag - teleport is near-guaranteed escape, smoke breaks LOS.
        boolean teleport = false, smoke = false, escapeBuff = false, jump = false;
        if (player.inventory != null && player.inventory.bag != null) {
            for (com.bjsp123.rl2.model.Item it : player.inventory.bag) {
                if (it == null) continue;
                if (ItemEval.isTeleportTool(it))      teleport    = true;
                if (ItemEval.isSmokeBomb(it))         smoke       = true;
                if (ItemEval.isReadyEscapeBuffTool(it)) escapeBuff = true;
                if (ItemEval.isReadyJumpTool(it))     jump        = true;
            }
        }
        if (teleport)                                       score += 100.0;
        if (smoke && nearestDist <= 4)                      score +=  60.0;
        if (jump)                                           score +=  30.0;
        if (escapeBuff)                                     score +=  30.0;

        // 2. Speed delta. moveCost is "ticks per step" - LOWER is faster.
        //    Bigger positive delta = we're meaningfully faster.
        int speedDelta = ns.moveCost - ps.moveCost;
        if (speedDelta > 0) score += Math.min(40.0, speedDelta);

        // 3. Headroom from the nearest threat.
        if (nearestDist > 1) score += 5.0 * (nearestDist - 1);

        // 4. Existing escape buffs already on the player (PHASE / INVISIBLE /
        //    HASTED) - already running away.
        if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(player,
                com.bjsp123.rl2.model.Buff.BuffType.PHASE)) score += 30.0;
        if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(player,
                com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)) score += 30.0;
        if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(player,
                com.bjsp123.rl2.model.Buff.BuffType.HASTED)) score += 30.0;

        // 5. Penalties: crowd and ranged threats squeeze escape windows.
        score -= 15.0 * Math.max(0, visibleEnemies.size() - 1);
        score -= 25.0 * rangedThreats;

        return score >= 40.0;
    }

    /* ---------- Expected-damage evaluator for attack actions ---------- */

    /** How an attack delivers damage - drives armor vs antiMagic selection and
     *  whether the attacker's mob/weapon damage stat or the item's damage stat
     *  applies. */
    public enum AttackKind { MELEE, CHARGE, THROW, WAND, GRAPPLE }

    /** Composite "expected damage if I do this attack right now" estimate, used
     *  by {@code ActionMelee / ActionThrowAt / ActionFireWandAt / ActionCastCharge /
     *  ActionCastGrapple}'s {@code utility} methods. Folds in:
     *  <ul>
     *    <li>Attacker's effective melee damage (mob inherent + equipped weapon)
     *        for {@link AttackKind#MELEE} / {@link AttackKind#CHARGE}, the
     *        item's effective damage range otherwise.</li>
     *    <li>Hit chance against the target's evasion (1.0 for direct-hit wand /
     *        thrown bomb).</li>
     *    <li>Target's armor for physical damage, antiMagic for magical.</li>
     *    <li>Equipped-weapon BRAND effects for melee/charge (e.g. fire-branded
     *        sword applies ON_FIRE).</li>
     *    <li>Item's appliesBuff plus throwEffect / wandEffect for items.</li>
     *    <li>Target immunities (e.g. fireImmune zeroes ON_FIRE / FIRE effects).</li>
     *  </ul>
     *  Returns a damage-units value the planner divides by ~25-30 to convert to
     *  utility delta. */
    public static double expectedAttackValue(Mob attacker, Mob target,
                                             com.bjsp123.rl2.model.Item item,
                                             AttackKind kind) {
        if (attacker == null || target == null) return 0.0;
        StatBlock ts = target.effectiveStats();
        double rawDmgAvg;
        if (kind == AttackKind.MELEE || kind == AttackKind.CHARGE) {
            com.bjsp123.rl2.model.MinMax mm =
                    com.bjsp123.rl2.logic.MobSystem.rawDamageRange(attacker);
            rawDmgAvg = mm == null ? 0.0 : mm.average();
        } else {
            if (item == null) return 0.0;
            com.bjsp123.rl2.model.MinMax mm =
                    com.bjsp123.rl2.logic.ItemStats.effectiveDamageRange(item);
            rawDmgAvg = mm == null ? item.damage : mm.average();
        }
        double hit = (kind == AttackKind.WAND) ? 1.0
                : hitChance(attacker.effectiveStats().accuracy, ts.evasion);
        boolean magical = isMagicalKind(kind, item);
        double defense = magical
                ? (ts.magicResist == null ? 0.0 : ts.magicResist.average())
                : (ts.armor == null ? 0.0 : ts.armor.average());
        double directDmg = Math.max(0.0, hit * (rawDmgAvg - defense));

        // Equipped-weapon brand effects (melee/charge only).
        double brandDmg = 0.0;
        if (kind == AttackKind.MELEE || kind == AttackKind.CHARGE) {
            com.bjsp123.rl2.model.Item w = attacker.inventory == null
                    ? null : attacker.inventory.weapon;
            if (w != null && w.appliesBuff != null) {
                int dur = com.bjsp123.rl2.logic.ItemStats.effectiveBuffDuration(w);
                for (var b : w.appliesBuff) brandDmg += expectedBuffDamage(b, dur, target);
            }
        }

        // Item-side appliesBuff + throwEffect / wandEffect.
        double buffDmg = 0.0;
        if (item != null) {
            int dur = com.bjsp123.rl2.logic.ItemStats.effectiveBuffDuration(item);
            if (item.appliesBuff != null) {
                for (var b : item.appliesBuff) buffDmg += expectedBuffDamage(b, dur, target);
            }
            int effDur = com.bjsp123.rl2.logic.ItemStats.effectiveDuration(item);
            if (item.throwEffect != null)
                buffDmg += expectedEffectDamage(item.throwEffect, effDur, target);
            if (item.wandEffect != null
                    && item.wandEffect != item.throwEffect)
                buffDmg += expectedEffectDamage(item.wandEffect, effDur, target);
        }
        return directDmg + brandDmg + buffDmg;
    }

    private static boolean isMagicalKind(AttackKind kind, com.bjsp123.rl2.model.Item item) {
        if (kind == AttackKind.WAND) return true;
        if (kind != AttackKind.THROW || item == null) return false;
        // Throws of items with elemental / magical effects are resisted by antiMagic
        // (matches rlib's processAttack DamageElement classification).
        var te = item.throwEffect;
        if (te == null) return false;
        return te == com.bjsp123.rl2.model.Item.ItemEffect.FIRE
                || te == com.bjsp123.rl2.model.Item.ItemEffect.FREEZE
                || te == com.bjsp123.rl2.model.Item.ItemEffect.LIGHTNING
                || te == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE
                || te == com.bjsp123.rl2.model.Item.ItemEffect.BANISHMENT
                || te == com.bjsp123.rl2.model.Item.ItemEffect.APPLYBUFFS
                || te == com.bjsp123.rl2.model.Item.ItemEffect.POISONCLOUD;
    }

    /** Per-buff expected damage over the buff's duration, zeroed if the target
     *  is immune. Source-of-truth values match rlib's BuffSystem. */
    public static double expectedBuffDamage(com.bjsp123.rl2.model.Buff.BuffType b,
                                            int durationTicks, Mob target) {
        if (b == null || target == null) return 0.0;
        StatBlock ts = target.effectiveStats();
        int turns = Math.max(1, durationTicks
                / com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS);
        switch (b) {
            case ON_FIRE:
                if (ts.fireImmune) return 0.0;
                return 8.0 * turns;
            case POISONED:
                return 2.0 * turns;     // base poison level 1
            case BLEEDING:
                return 3.0 * turns;
            case FROZEN:
                return 5.0 * turns;     // skip-turn equivalence
            case CHILLED:
                return 1.0 * turns;     // slow effect; small score
            case WET:
                return 0.5 * turns;     // enables FROZEN combo with CHILLED
            default:
                return 0.0;
        }
    }

    /** Per-ItemEffect expected damage / utility on the target. */
    public static double expectedEffectDamage(com.bjsp123.rl2.model.Item.ItemEffect e,
                                              int durationTicks, Mob target) {
        if (e == null || target == null) return 0.0;
        StatBlock ts = target.effectiveStats();
        int turns = Math.max(1, durationTicks
                / com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS);
        switch (e) {
            case FIRE:
                if (ts.fireImmune) return 0.0;
                return 8.0 * turns;     // FIRE vegetation applies ON_FIRE while target stands on it
            case DETONATION:
                if (ts.fireImmune) return 0.0;
                return 4.0 * turns;     // single radial fire pulse
            case POISONCLOUD:
                return 2.0 * turns;
            case FREEZE:
                return 1.0 * turns;     // CHILLED stack
            case BLAST:
            case DAMAGE:
            case MISSILE:
            case LIGHTNING:
                return 0.0;             // already accounted for in item.damage
            case SMOKE:
            case WATER:
            case OIL:
            case GRASS:
            case FUNGUS:
            case APPLYBUFFS:
            case VOID:
            case POLYMORPH:
            case LEVEL_UP:
            case HP_UP:
            case MANA_UP:
            case TELEPORT:
            case CAPTURE:
            case BANISHMENT:
            default:
                return 0.0;
        }
    }

    /* ---------- "Will this ranged attack actually hit something useful?"
     * ----------
     *
     * Predicates the planner uses to gate ranged-attack candidates BEFORE
     * they reach pickBest. Sync-impact in rlib means every ranged attack
     * resolves at the same tick the AI decides to fire it, so the planner
     * can deterministically simulate the impact and reject candidates that
     * would do nothing. Reduces bombDuds / wand fizzles caused by
     * trajectory clipping, fire-immune targets, empty-disc throws, blocked
     * dash paths, oversized grapple targets, etc.
     *
     * Each helper returns true iff the attack would deliver >0 useful
     * effect to at least one non-thrower, non-ally, alive mob. */

    /** True iff throwing {@code item} from {@code thrower} at {@code dst}
     *  would land on / splash a damageable hostile after trajectory
     *  clipping and disc placement. Covers BOMB and thrown POTION
     *  categories. Returns true (defer to existing utility) for items the
     *  planner shouldn't reject by impact prediction - non-BOMB/POTION
     *  throws, items missing key data. */
    public static boolean throwWillLandUsefully(com.bjsp123.rl2.ai.WorldState s,
                                                com.bjsp123.rl2.model.Item item,
                                                com.bjsp123.rl2.model.Point dst) {
        if (s == null || s.mob == null || s.level == null
                || item == null || dst == null || s.mob.position == null) return false;
        Level level = s.level;
        Mob thrower = s.mob;

        // Where does the projectile actually land? Trajectory clips on the
        // first blocking mob OR wall - mirrors MobSystem.throwItem.
        com.bjsp123.rl2.model.Point impact =
                com.bjsp123.rl2.logic.MobSystem.firstMobBlocking(level, thrower.position, dst, thrower);
        if (impact == null) return false;

        com.bjsp123.rl2.model.Item.InventoryCategory cat = item.inventoryCategory;
        java.util.List<com.bjsp123.rl2.model.Point> tiles;
        if (cat == com.bjsp123.rl2.model.Item.InventoryCategory.BOMB) {
            int effLvl = com.bjsp123.rl2.logic.ItemStats.effectiveLevel(item, thrower);
            int size = Math.max(1,
                    com.bjsp123.rl2.logic.ItemStats.effectiveSize(item, effLvl));
            tiles = com.bjsp123.rl2.logic.ItemSystem.packTilesAround(level, impact, size);
        } else if (cat == com.bjsp123.rl2.model.Item.InventoryCategory.POTION) {
            // applyThrowImpact's potion branch splashes onto every adjacent
            // tile - chebyshev <= 1 around the impact.
            tiles = new java.util.ArrayList<>(9);
            int ix = impact.tileX(), iy = impact.tileY();
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = ix + dx, y = iy + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    tiles.add(new com.bjsp123.rl2.model.Point(x, y));
                }
            }
        } else {
            // Unrecognised throw category - don't reject, let the existing
            // utility scoring decide.
            return true;
        }

        com.bjsp123.rl2.model.Item.ItemEffect te = item.throwEffect;
        for (com.bjsp123.rl2.model.Point p : tiles) {
            Mob m = com.bjsp123.rl2.logic.MobQueries.mobAt(level, p);
            if (m == null || m == thrower || m.hp <= 0) continue;
            if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(thrower, m)
                    == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) continue;
            if (throwAffects(m, item, te)) return true;
        }
        return false;
    }

    /** Element gate for a single (mob, item) pair. */
    private static boolean throwAffects(Mob m, com.bjsp123.rl2.model.Item item,
                                        com.bjsp123.rl2.model.Item.ItemEffect te) {
        if (m == null || item == null || te == null) return false;
        StatBlock ms = m.effectiveStats();
        boolean hasDamage = item.damage > 0;
        switch (te) {
            case BLAST:
            case DAMAGE:
                return hasDamage;
            case FIRE:
            case DETONATION:
                return hasDamage && !ms.fireImmune;
            case FREEZE:
            case POISONCLOUD:
            case WATER:
            case OIL:
            case VOID:
                // Damage or surface/cloud/buff effect lands on any non-ally.
                return true;
            case APPLYBUFFS:
                return item.appliesBuff != null && !item.appliesBuff.isEmpty();
            case TELEPORT:
            case CAPTURE:
            case BANISHMENT:
                // These are normally filtered upstream; if one slips through,
                // err on the side of letting the existing utility scoring
                // decide rather than mis-rejecting.
                return true;
            default:
                return hasDamage;
        }
    }

    /** True iff firing {@code wand} from {@code caster} at {@code dst}
     *  would land on a hostile that the element can affect. The wand
     *  missile / ray clips on the first blocking mob OR wall - we predict
     *  the actual impact tile and check the occupant. */
    public static boolean wandWillLandUsefully(com.bjsp123.rl2.ai.WorldState s,
                                               com.bjsp123.rl2.model.Item wand,
                                               com.bjsp123.rl2.model.Point dst) {
        if (s == null || s.mob == null || s.level == null
                || wand == null || dst == null || s.mob.position == null) return false;
        // Summon wands don't need a target - they spawn an ally adjacent.
        // Leave them out of the impact-prediction path; existing applicability
        // checks (charge, summon-cap) handle them.
        if (wand.summonsWhenUsed != null) return true;

        Level level = s.level;
        Mob caster = s.mob;
        com.bjsp123.rl2.model.Point impact =
                com.bjsp123.rl2.logic.MobSystem.firstMobBlocking(level, caster.position, dst, caster);
        if (impact == null) return false;
        Mob m = com.bjsp123.rl2.logic.MobQueries.mobAt(level, impact);
        if (m == null || m == caster || m.hp <= 0) return false;
        if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(caster, m)
                == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) return false;

        com.bjsp123.rl2.model.Item.ItemEffect we = wand.wandEffect;
        if (we == null) return wand.damage > 0;
        StatBlock ms = m.effectiveStats();
        switch (we) {
            case MISSILE:
            case LIGHTNING:
                return wand.damage > 0;
            case FIRE:
            case DETONATION:
                return wand.damage > 0 && !ms.fireImmune;
            case BANISHMENT:
                // Banishment yeets the target; useful against any non-ally.
                return true;
            case WATER:
            case OIL:
            case GRASS:
            case FUNGUS:
                // Surface paint - we want a hostile in the path. Don't fire
                // these as offensive moves unless damage > 0 (most have 0).
                return wand.damage > 0;
            case TELEPORT:
                // Defensive blink; handled by addEscapeWandAtNearest, but if
                // it reaches this path treat the hit as fine.
                return true;
            default:
                return wand.damage > 0;
        }
    }

    /** True iff a CHARGE / JUMP dash from {@code mob} toward {@code target}
     *  would end on an adjacent damageable hostile with a clear line of
     *  sight. Mirrors the gates already in ActionCastCharge.isApplicable
     *  plus a non-ally / alive check. */
    public static boolean dashWillLandUsefully(com.bjsp123.rl2.ai.WorldState s,
                                               Mob target, int dashRange) {
        if (s == null || s.mob == null || s.level == null
                || target == null || target.position == null) return false;
        if (target.hp <= 0) return false;
        if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(s.mob, target)
                == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) return false;
        int d = com.bjsp123.rl2.ai.WorldState.chebyshev(s.mob.position, target.position);
        if (d <= 1) return false;          // already adjacent - use melee instead
        if (d > Math.max(1, dashRange)) return false;
        return com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, target.position);
    }

    /** True iff a GRAPPLE from {@code mob} at {@code grapple} can yank
     *  {@code target} - covers size, LOS, alive, non-ally, and not-adjacent
     *  (grapple is for distant pulls). */
    public static boolean grappleWillLandUsefully(com.bjsp123.rl2.ai.WorldState s,
                                                  com.bjsp123.rl2.model.Item grapple,
                                                  Mob target) {
        if (s == null || s.mob == null || s.level == null
                || grapple == null || target == null || target.position == null) return false;
        if (target.hp <= 0) return false;
        if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(s.mob, target)
                == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) return false;
        int d = com.bjsp123.rl2.ai.WorldState.chebyshev(s.mob.position, target.position);
        if (d <= 1) return false;
        // Grapple silently fades if target.size > tool effective power.
        int maxSize = Math.max(0, (int) com.bjsp123.rl2.logic.ItemStats.effectivePower(grapple));
        if (target.effectiveStats().size > maxSize) return false;
        return com.bjsp123.rl2.logic.LevelUtilities.getLineOfSight(s.level, s.mob, target.position);
    }
}
