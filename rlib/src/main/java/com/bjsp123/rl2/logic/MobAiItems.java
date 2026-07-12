package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;

/**
 * AI item-use heuristics extracted from {@link MobSystem}: decides when a mob
 * spends its turn drinking a potion, firing a wand, lobbing a bomb, or using a
 * grapple / jump / charge tool from its inventory, and applies the chosen use.
 */
public final class MobAiItems {

    private MobAiItems() {}

    /** Per-item probability a mob's AI rolls each turn that it'll actually use
     *  the item (after the safety / utility gate). 50% gives the rogue a
     *  steady drumbeat of bomb throws while letting them mix in melee. */
    private static final double AI_USE_ITEM_CHANCE = 0.5;
    /** Max Chebyshev distance for an AI bomb throw. Mirrors the rough range
     *  the player uses in look mode for thrown items. */
    private static final int AI_BOMB_THROW_RANGE = 6;

    /**
     * Run the AI item-use heuristic for {@code mob}: walk the bag, find the
     * first item that's both usable and won't harm self/allies, roll
     * {@link #AI_USE_ITEM_CHANCE}, and on success apply it. The use consumes
     * the mob's turn - caller short-circuits.
     */
    static boolean tryUseInventoryItem(Mob mob, Level level) {
        if (mob == null || mob.inventory == null) return false;
        if (mob.inventory.bag == null || mob.inventory.bag.isEmpty()) return false;
        // Ranged-behavior mobs always use a usable item if the safety gate clears -
        // otherwise a kobold mage with a wand of magic missile would idle half its
        // turns next to a melee enemy. Other mobs roll AI_USE_ITEM_CHANCE.
        boolean alwaysUse = mob.behavior == Behavior.RANGED_MOB_DUMB
                         || mob.behavior == Behavior.RANGED_MOB_STANDOFF;
        // Snapshot the bag so applyAiItemUse can mutate it (heal potions /
        // bombs are removed on use). Sort by Item.getValue() desc so within
        // each kind of item the AI prefers the highest-value instance;
        // since usability is checked per-item the "kind of item to use"
        // choice is still driven by isUsableByAi, just biased to good rolls.
        java.util.List<com.bjsp123.rl2.model.Item> snapshot =
                new java.util.ArrayList<>(mob.inventory.bag);
        snapshot.sort((a, b) -> Double.compare(
                b == null ? Double.NEGATIVE_INFINITY : b.getValue(),
                a == null ? Double.NEGATIVE_INFINITY : a.getValue()));
        for (com.bjsp123.rl2.model.Item item : snapshot) {
            if (!isUsableByAi(mob, item, level)) continue;
            // Wands that pass isWandUsableByAi already represent "best damage
            // option vs. safe to fire" - skip the chance roll so a mage holding
            // a viable wand doesn't idle every other turn. Mirrors the always-
            // use treatment ranged-behavior mobs get above.
            boolean isUsableWand =
                    item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.WAND;
            if (!alwaysUse && !isUsableWand
                    && MobSystem.RANDOM.nextDouble() >= AI_USE_ITEM_CHANCE) continue;
            if (applyAiItemUse(mob, item, level)) return true;
        }
        return false;
    }

    private static boolean isUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (item == null) return false;
        if (item.inventoryCategory == com.bjsp123.rl2.model.Item.InventoryCategory.BOMB) {
            return canThrowBombAtSomeone(mob, item, level);
        }
        if (item.useBehavior == null
                || item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.NONE) return false;
        return switch (item.useBehavior) {
            case DRINK, APPLYBUFF -> wouldDrinkHelp(mob, item);
            case WAND    -> isWandUsableByAi(mob, item, level);
            case GRAPPLE -> isGrappleUsableByAi(mob, item, level);
            case JUMP    -> isJumpUsableByAi(mob, item, level);
            case CHARGE  -> isChargeUsableByAi(mob, item, level);
            case EAT, GRANT_PERK, POWERUP, TELEPORT, NONE -> false;
        };
    }

    /** AI CHARGE gate. Requires (a) a hostile attack-target within the item's
     *  {@link ItemStats#effectiveRange} Chebyshev radius, (b) the target is
     *  NOT adjacent (Chebyshev > 1 - melee is cheaper than burning a charge),
     *  (c) line-of-sight from caster to target, and (d) at least one
     *  walkable + unoccupied 8-neighbour of the target for the dash to land
     *  on. Mirrors the player-side {@code chargeGrid} in {@code PlayController}. */
    private static boolean isChargeUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob == null || mob.position == null) return false;
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        Mob target = MobSystem.nearestAttackTarget(mob, level);
        if (target == null || target.position == null || target.hp <= 0) return false;
        int dx = Math.abs(target.position.tileX() - mob.position.tileX());
        int dy = Math.abs(target.position.tileY() - mob.position.tileY());
        int cheb = Math.max(dx, dy);
        if (cheb <= 1) return false; // already adjacent - just melee
        int effLvl = ItemStats.effectiveLevel(item, mob);
        int dashRange = Math.max(1, ItemStats.effectiveRange(item, effLvl));
        if (cheb > dashRange) return false;
        if (!LevelUtilities.getLineOfSight(level, mob, target.position)) return false;
        int tx = target.position.tileX(), ty = target.position.tileY();
        for (int ndy = -1; ndy <= 1; ndy++) {
            for (int ndx = -1; ndx <= 1; ndx++) {
                if (ndx == 0 && ndy == 0) continue;
                int nx = tx + ndx, ny = ty + ndy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny].blocksMovement()) continue;
                if (MobQueries.mobAt(level, new Point(nx, ny)) != null) continue;
                return true;
            }
        }
        return false;
    }

    /** Heuristic for {@code DRINK} potions - only quaff if it'll actually help.
     *  Damaging potions (non-zero {@code item.damage}, e.g. potion of poison)
     *  are never drunk; buff potions are useful when the buff isn't already
     *  up; the REGENERATION buff additionally requires the mob to actually
     *  be wounded (drinking a healing potion at full HP wastes the potion).
     *  Override: a POISONED mob will drink any potion that would cure the
     *  poison (currently any REGENERATION-applying item), even at full HP. */
    private static boolean wouldDrinkHelp(Mob mob, com.bjsp123.rl2.model.Item item) {
        if (item == null) return false;
        if (item.damage > 0) return false;
        // Depleted charged tools (JADE_CRAB, JADE_FISH, FROG, etc.) can't fire;
        // ItemSystem.useChargedBuffTool would short-circuit anyway, but gating
        // here means the AI doesn't burn a per-item roll on an unusable choice.
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        com.bjsp123.rl2.model.Buff.BuffType primary = item.primaryBuff();
        if (primary == null) return false;
        // Cure-poison override: a poisoned mob holding a REGEN-applying item
        // drinks it to strip POISONED, regardless of HP / existing buffs.
        if (BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.POISONED)
                && removesPoison(item)) {
            return true;
        }
        if (BuffSystem.hasBuff(mob, primary)) return false;
        if (primary == com.bjsp123.rl2.model.Buff.BuffType.REGENERATION) {
            return mob.hp < mob.effectiveStats().maxHp;
        }
        return true;
    }

    /** True if using this item applies a buff that strips POISONED. Today
     *  that's any item whose {@code appliesBuff} list contains REGENERATION
     *  (per {@link BuffSystem}'s per-turn REGEN dispatch, which removes a
     *  POISONED stack). Add other cure mechanisms here as they appear. */
    private static boolean removesPoison(com.bjsp123.rl2.model.Item item) {
        if (item == null || item.appliesBuff == null) return false;
        return item.appliesBuff.contains(
                com.bjsp123.rl2.model.Buff.BuffType.REGENERATION);
    }

    private static boolean isGrappleUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob.position == null) return false;
        Mob target = MobSystem.nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        // No point grappling when already adjacent
        if (LevelFactoryUtils.chebyshev(mob.position, target.position) <= 1) return false;
        int maxSize = Math.max(0, (int) ItemStats.effectivePower(item));
        return target.effectiveStats().size <= maxSize;
    }

    private static boolean isJumpUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob.position == null) return false;
        Mob threat = MobSystem.nearestAttackTarget(mob, level);
        if (threat == null || threat.position == null) return false;
        return pickBestJumpTile(mob, item, level, threat) != null;
    }

    /** Element-wand AI gate. MISSILE / TELEPORT / summon wands always pass
     *  the gate when their preconditions hold. Direct-damage AoE wands
     *  (BLAST, DETONATION, FIRE) pass when there's a hostile target AND no
     *  ally would catch the disc AND the caster's own tile isn't inside the
     *  disc footprint. Utility AoE wands (WATER / OIL / GRASS / FUNGUS) and
     *  BANISHMENT remain deferred - the AI has no heuristic for when those
     *  are useful.
     *
     *  <p>Damage-class wands additionally have to beat the caster's melee
     *  output - "fire a wand only if it's the best damage option this turn"
     *  per the AI rule. A wand that's strictly weaker than what the caster
     *  could land in melee is left in the bag so the caster steps into
     *  melee range and swings instead. */
    private static boolean isWandUsableByAi(Mob mob, com.bjsp123.rl2.model.Item wand, Level level) {
        // Depleted wands can't fire. Without this gate they pass here and
        // then ItemSystem.fireWand silently no-ops at its own charge check,
        // wasting the AI's per-item roll.
        if (wand.baseChargeMax > 0 && wand.charge < 1f) return false;
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
            Mob target = MobSystem.nearestAttackTarget(mob, level);
            if (target == null) return false;
            if (!hasLineOfFire(mob, target, level)) return false;
            if (expectedWandDamage(wand, mob, target) <= 0) return false;
            return wandBeatsMelee(mob, wand, target);
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
            return MobSystem.nearestAttackTarget(mob, level) != null
                    && pickTeleportDestination(mob, level) != null;
        }
        if (wand.summonsWhenUsed != null) {
            return MobQueries.levelHasRoomForSpawn(level)
                    && MobHooks.freeAdjacentFloor(level, mob.position) != null;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.BLAST
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.DETONATION
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.FIRE) {
            Mob target = MobSystem.nearestAttackTarget(mob, level);
            if (target == null || target.position == null) return false;
            if (!hasLineOfFire(mob, target, level)) return false;
            int effLvl = ItemStats.effectiveLevel(wand, mob);
            int effectSize = ItemStats.effectiveSize(wand, effLvl);
            if (effectSize <= 0) return false;
            if (hasAllyInDisc(mob, level, target.position, effectSize)) return false;
            if (casterInDisc(mob, target.position, effectSize)) return false;
            if (expectedWandDamage(wand, mob, target) <= 0) return false;
            return wandBeatsMelee(mob, wand, target);
        }
        return false;
    }

    /** Heuristic for "would firing this wand at this target plausibly deal
     *  damage?". Returns 0 when the target is fundamentally immune to the
     *  effect (fireImmune vs FIRE, non-banishable vs BANISHMENT)
     *  and otherwise nets the wand's stat-based damage against the target's
     *  magic-resist range. AI uses the zero return as an "don't bother
     *  firing" gate; comparators use the magnitude for melee comparison.
     *
     *  <p>Surface contributions (the fire trail WAND_FIRE leaves) are
     *  approximated as a flat bonus tied to {@code effectSize} when the
     *  target can actually be ignited - mobs that ignore the surface get
     *  nothing for it. */
    private static double expectedWandDamage(com.bjsp123.rl2.model.Item wand,
                                             Mob caster, Mob target) {
        if (wand == null || target == null) return 0;
        com.bjsp123.rl2.model.StatBlock ts = target.effectiveStats();
        com.bjsp123.rl2.model.Item.ItemEffect eff = wand.wandEffect;
        if (eff == com.bjsp123.rl2.model.Item.ItemEffect.BANISHMENT) {
            // Treat as a one-shot kill when applicable; otherwise no effect.
            return target.banishable ? 999.0 : 0.0;
        }
        // FIRE wand vs fireImmune target: the projectile leaves a burning patch
        // rather than landing a meaningful direct hit, and a fireImmune mob
        // ignores the patch entirely, so the matchup is "wrong wand for this
        // target" - reject outright. Flying targets are NOT exempt: the wand
        // ignites the ground beneath them and a flyer standing on burning
        // vegetation still catches fire (FireSystem.tickPerTurn spares only
        // fireImmune mobs), so a fire wand stays worth casting at a flyer.
        if (eff == com.bjsp123.rl2.model.Item.ItemEffect.FIRE
                && ts.fireImmune) {
            return 0;
        }
        int effLvl = ItemStats.effectiveLevel(wand, caster);
        double base = wand.damage > 0
                ? ItemStats.effectiveDamageRange(wand, effLvl).average()
                : 0;
        double mr = ts.magicResist.average();
        double net = Math.max(0, base - mr);
        if (eff == com.bjsp123.rl2.model.Item.ItemEffect.FIRE) {
            // Surface bonus: fire wands leave a burning patch worth roughly
            // {@code effectSize} ticks of fire damage to a stationary target.
            int effectSize = ItemStats.effectiveSize(wand, effLvl);
            net += 2.0 * Math.max(1, effectSize);
        }
        return net;
    }

    /** Geometric: would the caster's own tile fall inside the prospective
     *  AoE disc centred on {@code target}? Uses the same approximate
     *  Chebyshev radius ({@code ceil(sqrt(effectSize))}) as
     *  {@link #hasAllyInDisc}, keeping the predictor and the resolver
     *  consistent. Returning true means "AI would singe itself - skip". */
    private static boolean casterInDisc(Mob caster, com.bjsp123.rl2.model.Point target,
                                        int effectSize) {
        if (caster == null || caster.position == null || target == null
                || effectSize <= 0) return false;
        int approxRadius = (int) Math.ceil(Math.sqrt(effectSize));
        int dx = Math.abs(caster.position.tileX() - target.tileX());
        int dy = Math.abs(caster.position.tileY() - target.tileY());
        return Math.max(dx, dy) <= approxRadius;
    }

    /** True if firing {@code wand} at {@code target} would land at least as
     *  much damage as the caster's straight-up melee swing. Out-of-melee
     *  targets (Chebyshev > 1) auto-pass since melee output is 0 from there.
     *  Uses {@link #expectedWandDamage} so resistances / immunities feed
     *  into the comparison too: a fire wand against a fireImmune mob reports
     *  zero expected damage and loses to even a 1-damage dagger. */
    private static boolean wandBeatsMelee(Mob caster, com.bjsp123.rl2.model.Item wand,
                                          Mob target) {
        if (caster == null || target == null || target.position == null
                || caster.position == null) return true;
        int dx = Math.abs(caster.position.tileX() - target.position.tileX());
        int dy = Math.abs(caster.position.tileY() - target.position.tileY());
        if (Math.max(dx, dy) > 1) return true;
        double wandMean = expectedWandDamage(wand, caster, target);
        double meleeMean = caster.effectiveStats().damage.average();
        return wandMean >= meleeMean;
    }

    /** True if any live mob with ALLY attitude to {@code caster} stands
     *  within the prospective effect disc of an AoE wand centred on
     *  {@code target}. Used by the AI to skip casting an AoE wand into a
     *  pack of allies. Disc radius is approximated as
     *  {@code ceil(sqrt(effectSize))} (Chebyshev) - over-cautious so the
     *  AI may skip borderline-safe casts, never friendly-fire. */
    private static boolean hasAllyInDisc(Mob caster, Level level,
                                         com.bjsp123.rl2.model.Point target,
                                         int effectSize) {
        if (level == null || target == null || effectSize <= 0) return false;
        int approxRadius = (int) Math.ceil(Math.sqrt(effectSize));
        int tx = target.tileX(), ty = target.tileY();
        for (Mob m : level.mobs) {
            if (m == null || m == caster || m.hp <= 0 || m.position == null) continue;
            int dx = Math.abs(m.position.tileX() - tx);
            int dy = Math.abs(m.position.tileY() - ty);
            if (Math.max(dx, dy) > approxRadius) continue;
            if (MobSystem.isAlly(caster, m)) return true;
        }
        return false;
    }

    /** True iff {@code shooter} both sees {@code target} AND has an unobstructed
     *  projectile path to it. CRYSTAL_DOOR is sight-transparent but blocks
     *  projectiles, so without the second check a mob lobs bombs / fires wands at
     *  a target it can't actually hit (e.g. the player sealed outside a beacon
     *  room); the shot clips short and splashes its own allies (RL-16). Mirrors
     *  the gate in {@link MobSystem#tryRangedShot}. */
    private static boolean hasLineOfFire(Mob shooter, Mob target, Level level) {
        if (target == null || target.position == null) return false;
        return LevelUtilities.getLineOfSight(level, shooter, target.position)
                && MobSystem.projectileLineReaches(level, shooter.position, target.position, shooter);
    }

    /** True iff the mob has at least one hostile in throw range, a clear line of
     *  fire to it, and no ally inside the bomb's AOE around that target. */
    private static boolean canThrowBombAtSomeone(Mob thrower, com.bjsp123.rl2.model.Item bomb, Level level) {
        Mob target = MobSystem.nearestAttackTarget(thrower, level);
        if (target == null || target.position == null) return false;
        int d = Math.max(Math.abs(target.position.tileX() - thrower.position.tileX()),
                         Math.abs(target.position.tileY() - thrower.position.tileY()));
        if (d > AI_BOMB_THROW_RANGE) return false;
        if (!hasLineOfFire(thrower, target, level)) return false;
        return !allyInBombAoe(thrower, target.position, bomb, level);
    }

    /** Walk the bomb's effect disc around {@code centre}; return true the
     *  moment any tile holds a mob the thrower considers ALLY. */
    private static boolean allyInBombAoe(Mob thrower, com.bjsp123.rl2.model.Point centre,
                                         com.bjsp123.rl2.model.Item bomb, Level level) {
        int radius = ItemStats.effectiveSize(bomb);
        int r2 = radius * radius;
        int cx = centre.tileX(), cy = centre.tileY();
        for (Mob m : level.mobs) {
            if (m == thrower || m.hp <= 0 || m.position == null) continue;
            int dx = m.position.tileX() - cx;
            int dy = m.position.tileY() - cy;
            if (dx * dx + dy * dy > r2) continue;
            if (MobSystem.isAlly(thrower, m)) return true;
        }
        return false;
    }

    /** Apply the chosen AI item-use. Returns true only if the use actually
     *  charged time; depleted or invalid items fall through to later AI choices. */
    private static boolean applyAiItemUse(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        if (item.inventoryCategory == com.bjsp123.rl2.model.Item.InventoryCategory.BOMB) {
            Mob target = MobSystem.nearestAttackTarget(mob, level);
            if (target != null) {
                MobThrowing.throwItem(level, mob, item, target.position);
            }
            return mob.ticksTillMove != before;
        }
        switch (item.useBehavior) {
            case DRINK, APPLYBUFF -> ItemSystem.useItem(level, mob, item);
            case WAND    -> { if (!aiCastWand(mob, item, level)) return false; }
            case GRAPPLE -> {
                if (!aiCastGrapple(mob, item, level)) return false;
                com.bjsp123.rl2.util.ActionTracker.bumpTool(mob);
            }
            case JUMP    -> {
                if (!aiCastJump(mob, item, level)) return false;
                com.bjsp123.rl2.util.ActionTracker.bumpTool(mob);
            }
            case CHARGE  -> {
                if (!aiCastCharge(mob, item, level)) return false;
                com.bjsp123.rl2.util.ActionTracker.bumpTool(mob);
            }
            default      -> { /* unreachable per the gate */ }
        }
        return mob.ticksTillMove != before;
    }

    /** AI wand cast. Picks a target (no targeting UI) and delegates to the shared
     *  {@link ItemSystem#fireWand} entry point so trajectory clipping, event
     *  emission, and move cost stay identical between player and AI. Summon
     *  wands ignore target. Tile-targeting element wands (water/oil/fire/etc.)
     *  are deferred until friendly-fire AOE checks land. */
    private static boolean aiCastWand(Mob caster, com.bjsp123.rl2.model.Item wand, Level level) {
        int before = caster.ticksTillMove;
        if (wand.summonsWhenUsed != null) {
            ItemSystem.fireWand(level, caster, wand, null);
            return caster.ticksTillMove != before;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
            Mob target = MobSystem.nearestAttackTarget(caster, level);
            if (target == null) return false;
            ItemSystem.fireWand(level, caster, wand, target.position);
            return caster.ticksTillMove != before;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
            com.bjsp123.rl2.model.Point dest = pickTeleportDestination(caster, level);
            if (dest != null) ItemSystem.fireWand(level, caster, wand, dest);
            return caster.ticksTillMove != before;
        }
        // AoE damage wands: fire at the nearest hostile (gate already verified
        // no allies in the disc via isWandUsableByAi).
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.BLAST
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.DETONATION
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.FIRE) {
            Mob target = MobSystem.nearestAttackTarget(caster, level);
            if (target == null || target.position == null) return false;
            ItemSystem.fireWand(level, caster, wand, target.position);
            return caster.ticksTillMove != before;
        }
        return caster.ticksTillMove != before;
    }

    private static boolean aiCastGrapple(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        Mob target = MobSystem.nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        // Grapple is only useful against the player or targets with a ranged attack.
        if (target.behavior != Mob.Behavior.PLAYER
                && target.effectiveStats().rangedDamage.max() <= 0) return false;
        ItemSystem.castGrapple(level, mob, item, target.position);
        return mob.ticksTillMove != before;
    }

    private static boolean aiCastJump(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        Mob threat = MobSystem.nearestAttackTarget(mob, level);
        if (threat == null) return false;
        com.bjsp123.rl2.model.Point dest = pickBestJumpTile(mob, item, level, threat);
        if (dest != null) ItemSystem.castJump(level, mob, item, dest);
        return mob.ticksTillMove != before;
    }

    /** AI CHARGE cast - dash to the nearest attack-target's tile. The CHARGE
     *  use itself picks the arrival tile and applies the swing + knockback via
     *  {@link ItemSystem#castCharge}; this helper just provides the target
     *  position. Gate ({@link #isChargeUsableByAi}) already verified range +
     *  LoS + free arrival tile. */
    private static boolean aiCastCharge(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        Mob target = MobSystem.nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        ItemSystem.castCharge(level, mob, item, target.position);
        return mob.ticksTillMove != before;
    }

    /** Find the tile in JUMP radius that maximises Chebyshev distance from {@code threat}.
     *  Returns null when no reachable tile improves on the mob's current distance. */
    private static com.bjsp123.rl2.model.Point pickBestJumpTile(
            Mob mob, com.bjsp123.rl2.model.Item item, Level level, Mob threat) {
        if (mob.position == null || threat.position == null) return null;
        int effLvl = ItemStats.effectiveLevel(item, mob);
        int radius = Math.max(0, ItemStats.effectiveRange(item, effLvl));
        int mx = mob.position.tileX(), my = mob.position.tileY();
        int tx = threat.position.tileX(), ty = threat.position.tileY();
        int currentDist = LevelFactoryUtils.chebyshev(mob.position, threat.position);
        com.bjsp123.rl2.model.Point best = null;
        int bestDist = currentDist;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = mx + dx, ny = my + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny].blocksMovement()) continue;
                com.bjsp123.rl2.model.Point p = new com.bjsp123.rl2.model.Point(nx, ny);
                if (MobQueries.mobAt(level, p) != null) continue;
                int d = Math.max(Math.abs(nx - tx), Math.abs(ny - ty));
                if (d > bestDist) { bestDist = d; best = p; }
            }
        }
        return best;
    }

    /** Pick a random free floor tile farther from the nearest threat than the mob's
     *  current position. Returns null when no improvement is possible. */
    private static com.bjsp123.rl2.model.Point pickTeleportDestination(Mob mob, Level level) {
        if (mob.position == null) return null;
        Mob threat = MobSystem.nearestAttackTarget(mob, level);
        int currentDist = threat != null
                ? LevelFactoryUtils.chebyshev(mob.position, threat.position) : 0;
        java.util.List<com.bjsp123.rl2.model.Point> candidates = new java.util.ArrayList<>();
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (level.tiles[x][y].blocksMovement()) continue;
                com.bjsp123.rl2.model.Point p = new com.bjsp123.rl2.model.Point(x, y);
                if (MobQueries.mobAt(level, p) != null) continue;
                if (threat != null
                        && LevelFactoryUtils.chebyshev(p, threat.position) <= currentDist) continue;
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(MobSystem.RANDOM.nextInt(candidates.size()));
    }
}
