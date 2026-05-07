package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.StatBlock;

/**
 * Single source of truth for "what does this item actually do at its current level?".
 * The {@link Item} class stores the base values; every consumer that needs the
 * effective-with-level-increment number routes through here, so level math lives in
 * exactly one place.
 *
 * <p>The {@code bonusToXxx} methods are designed to return a contribution from
 * <em>any</em> equipped item — most items return {@link MinMax#ZERO} for stat slots
 * they don't carry. Mob-side stat helpers ({@code MobSystem.rawDamageRange} etc.)
 * iterate every equipped slot and sum the per-stat bonus, so any future "ring of
 * fire damage" or "amulet of evasion" plugs in by setting the corresponding base
 * field on the item without any additional dispatch.
 */
public final class ItemSystem {

    private ItemSystem() {}

    // ── Single contributor entry-point for the StatBlock pipeline ────────────

    /**
     * Add {@code item}'s contribution to {@code dst}. {@link MobSystem#writeEffectiveStats}
     * calls this once per equipped slot during the rollup, so all per-item level-scaling
     * lives here in one method instead of being smeared across {@code bonusToDamage} /
     * {@code bonusToArmor} / etc.
     *
     * <p>Null items (empty slots) are a no-op. Items that don't carry a given stat
     * naturally contribute {@code MinMax.ZERO} / {@code 0} to that stat, so the rollup
     * doesn't need item-type branching.
     */
    public static void contributeInto(StatBlock dst, Item item) {
        if (item == null || dst == null) return;
        if (item.damage.max() > 0) dst.damage = dst.damage.plus(effectiveDamageRange(item));
        if (item.armor.max()  > 0) dst.armor  = dst.armor .plus(effectiveArmorRange(item));
        if (item.lightRadius > dst.lightRadius) dst.lightRadius = item.lightRadius;
        dst.knockbackSquares += item.knockbackSquares;
    }

    // ── Level-scaling primitives ─────────────────────────────────────────────

    /** Min-max linear level scale: {@code [baseMin + lvl*incMin, baseMax + lvl*incMax]}.
     *  Null items / negative levels clamp the level to 0. Used by every per-item
     *  stat that scales linearly per plus (damage, armor). */
    private static MinMax levelScaledRange(Item item,
                                           int baseMin, int baseMax,
                                           int incMin, int incMax) {
        int lvl = clampedLevel(item);
        int min = baseMin + lvl * incMin;
        int max = baseMax + lvl * incMax;
        return new MinMax(Math.max(0, min), Math.max(min, max));
    }

    private static int clampedLevel(Item item) {
        return item == null ? 0 : Math.max(0, item.level);
    }

    // ── Use-time effects ─────────────────────────────────────────────────────

    /** Effective damage range on this item, level-scaled by the item's own
     *  {@code damagePerLevel} column. Used unchanged for melee weapons (via
     *  {@link #contributeInto}), thrown weapons (via {@code MobSystem.throwItem}),
     *  damage-dealing wands (MISSILE, LIGHTNING — via {@code applyWandImpact}),
     *  and damage-dealing bombs (via the bomb impact path). Items with no
     *  damage range return {@link MinMax#ZERO}. */
    public static MinMax effectiveDamageRange(Item item) {
        return effectiveDamageRange(item, clampedLevel(item));
    }

    /** Level-explicit overload — used by paths where the effective level
     *  differs from {@code item.level} (currently the WANDMASTER perk bumps
     *  the wand by +1 at fire time). */
    public static MinMax effectiveDamageRange(Item item, int level) {
        if (item == null || item.damage.max() <= 0) return MinMax.ZERO;
        return levelScaledRangeAt(level,
                item.damage.min(),         item.damage.max(),
                item.damagePerLevel.min(), item.damagePerLevel.max());
    }

    /** Effective armor range on this item, level-scaled by the item's own
     *  {@code armorPerLevel}. Items with no armor value return
     *  {@link MinMax#ZERO}. */
    public static MinMax effectiveArmorRange(Item item) {
        if (item == null || item.armor.max() <= 0) return MinMax.ZERO;
        return levelScaledRange(item,
                item.armor.min(),         item.armor.max(),
                item.armorPerLevel.min(), item.armorPerLevel.max());
    }

    /** Linear-scale primitive parameterised on level rather than item — used
     *  by callers that need to bump the level (perks, future buffs that
     *  amplify item effects). */
    private static MinMax levelScaledRangeAt(int level,
                                             int baseMin, int baseMax,
                                             int incMin, int incMax) {
        int lvl = Math.max(0, level);
        int min = baseMin + lvl * incMin;
        int max = baseMax + lvl * incMax;
        return new MinMax(Math.max(0, min), Math.max(min, max));
    }

    /** Effective food value when eaten. Food is always level 0, but this helper still
     *  respects {@code item.level} for any future scalable food. */
    public static int effectiveFoodValue(Item item) {
        if (item == null || item.foodValue <= 0) return 0;
        return item.foodValue;
    }

    /** Effective buff level applied by a buff-bestowing item. {@code 1 + item.level}
     *  so a level-0 starter potion still applies a level-1 buff. */
    public static int effectiveBuffLevel(Item item) {
        return 1 + clampedLevel(item);
    }

    /** Effective buff duration in turns: {@code (1 + item.level) * item.buffDuration}.
     *  Items that don't carry a base duration (data column blank) fall back to
     *  {@link #effectiveBuffLevel} so a level-N consumable still applies its buff
     *  for at least one tick per level. */
    public static int effectiveBuffDuration(Item item) {
        if (item == null) return 1;
        int base = item.buffDuration > 0 ? item.buffDuration : 1;
        return effectiveBuffLevel(item) * base;
    }

    /** Effective tile count for a wand or bomb area effect. Converted to a
     *  Euclidean disc radius via {@code MobSystem.radiusForTileCount} at the
     *  use-site. Reads {@link Item#tilesAffected} + {@code tilesAffectedPerLevel}
     *  off the source item; items with no AOE columns return 0. Replaces the
     *  separate wand-tiles / bomb-tiles helpers — the formula is the same and
     *  the per-item columns now carry the per-kind values. */
    public static int effectiveTilesAffected(Item item) {
        return effectiveTilesAffected(item, clampedLevel(item));
    }

    /** Level-explicit overload (WANDMASTER bumps wand level at fire time). */
    public static int effectiveTilesAffected(Item item, int level) {
        if (item == null || item.tilesAffected <= 0) return 0;
        return item.tilesAffected + Math.max(0, level) * item.tilesAffectedPerLevel;
    }

    // ── Use-time effect dispatchers ──────────────────────────────────────────

    /**
     * Consume a food item: raise {@code eater}'s satiety by {@code item.foodValue}
     * (capped at {@link GameBalance#STARTING_SATIETY}), apply any buff carried by
     * the item ({@link Item#appliesBuff} — silvery pear's HOPE, conference pear's
     * ESP, etc.), then remove the item from their inventory. No-op for items
     * without food value.
     */
    public static void eat(Level level, Mob eater, Item item) {
        if (eater == null || item == null || item.foodValue <= 0) return;
        eater.satiety = Math.min(GameBalance.STARTING_SATIETY, eater.satiety + item.foodValue);
        applyConsumableBuff(level, eater, item);
        MobSystem.removeFromInventory(eater, item);
        if (eater.behavior == Behavior.PLAYER) {
            String name = eater.name != null ? eater.name : "Adventurer";
            EventLog.add(Messages.playerUses(name,
                    item.useVerb != null ? item.useVerb : "eat", item.name));
        }
    }

    /**
     * Drink a potion. Applies the item's {@link Item#appliesBuff} (level and
     * duration from the item-level helpers). When the potion carries a
     * non-zero {@link Item#damage} range, the drinker takes that damage
     * (level-scaled via {@link #effectiveDamageRange}) — drinking a poison
     * potion now uses the same damage column as throwing it. Item is consumed.
     */
    public static void drinkPotion(Level level, Mob drinker, Item item) {
        if (drinker == null || item == null) return;
        applyPotionEffect(level, drinker, item, drinker);
        emitPotionBurst(level, drinker.position, item);
        MobSystem.removeFromInventory(drinker, item);
        if (drinker.behavior == Behavior.PLAYER) {
            String name = drinker.name != null ? drinker.name : "Adventurer";
            EventLog.add(Messages.playerUses(name,
                    item.useVerb != null ? item.useVerb : "drink", item.name));
        }
    }

    /**
     * Resolve a potion landing — the same effect that drinking the potion has
     * is applied to every mob on or adjacent to {@code at} (Chebyshev distance
     * 1). Called from {@code MobSystem.throwItem} for items whose
     * {@link Item#useBehavior} is {@link Item.UseBehavior#DRINK}: drinking and
     * throwing are mirror operations, the only difference being who's affected
     * (drinker vs. mobs in the splash radius).
     *
     * <p>The {@link Item.UseBehavior#DRINK} preflight check in
     * {@code throwItem} routes potions here instead of the standard DAMAGE /
     * bomb-AOE branches, so a thrown POTION_POISON splashes its damage AND
     * applies the POISONED buff to every mob in the disc, not just the
     * single mob on the target tile.
     */
    public static void applyPotionImpact(Level level, Point at, Item item, Mob source) {
        if (level == null || at == null || item == null) return;
        int cx = at.tileX(), cy = at.tileY();
        // Snapshot the affected mobs so an in-loop death doesn't ConcurrentModification.
        java.util.List<Mob> victims = new java.util.ArrayList<>();
        if (level.mobs != null) {
            for (Mob m : level.mobs) {
                if (m == null || m.position == null || m.hp <= 0) continue;
                int d = Math.max(Math.abs(m.position.tileX() - cx),
                                 Math.abs(m.position.tileY() - cy));
                if (d <= 1) victims.add(m);
            }
        }
        for (Mob v : victims) {
            applyPotionEffect(level, v, item, source);
        }
        emitPotionBurst(level, at, item);
    }

    /** Apply the potion's drink-time effect to a single mob: appliesBuff plus
     *  the rolled damage range (if non-zero). POTION_INSIGHT additionally
     *  reveals the whole level when the affected mob is the player — that's
     *  the only player-only side-effect, and it's expressed as a hard-coded
     *  type check because revealing the explored map can't be modelled as a
     *  Buff. Other potion specials should add a similar branch here rather
     *  than reaching into drinkPotion / throwItem. */
    private static void applyPotionEffect(Level level, Mob mob, Item item, Mob source) {
        if (mob == null || item == null) return;
        applyConsumableBuff(level, mob, item);
        if (item.damage.max() > 0) {
            int dmg = MobSystem.rollRange(effectiveDamageRange(item));
            if (dmg > 0) {
                MobSystem.processAttack(level, source, mob, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.POISON);
            }
        }
        if ("POTION_INSIGHT".equals(item.type) && mob.behavior == Behavior.PLAYER) {
            revealLevel(level);
        }
    }

    /** Post a potion-burst visual at {@code at}. The renderer reads
     *  {@code item.appliesBuff} / {@code item.type} to pick the particle
     *  colour, so this method only needs to forward the location and item
     *  reference. Skipped silently when the level has no event sink. */
    private static void emitPotionBurst(Level level, Point at, Item item) {
        if (level == null || level.events == null || at == null || item == null) return;
        level.events.add(new com.bjsp123.rl2.event.GameEvent.PotionBurst(at, item));
    }

    /** Stamp every tile of {@code level} as explored, so the renderer paints the
     *  whole map (in remembered-but-not-currently-visible state). Player still
     *  sees only currently-lit tiles in full colour — vision is unchanged. */
    private static void revealLevel(Level level) {
        if (level == null || level.explored == null) return;
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                level.explored[x][y] = true;
            }
        }
    }

    /** Apply the item's CSV-declared {@link Item#appliesBuff} to the user, with
     *  level / duration scaled by the standard item-level helpers. No-op when the
     *  item carries no buff. */
    private static void applyConsumableBuff(Level level, Mob user, Item item) {
        if (item == null || item.appliesBuff == null) return;
        BuffSystem.apply(level, user, item.appliesBuff,
                effectiveBuffLevel(item), effectiveBuffDuration(item), user);
    }

    /**
     * Consume a perk-granting item — currently just power orbs. Awards one perk
     * point to the user, removes the item from inventory, and logs the action.
     * Caller is responsible for the move-cost accounting.
     */
    public static void grantPerk(Level level, Mob user, Item item) {
        if (user == null || item == null) return;
        user.perkPoints++;
        MobSystem.removeFromInventory(user, item);
        if (level != null && level.events != null && user.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.RainbowBurst(user.position));
        }
        if (user.behavior == Behavior.PLAYER) {
            String name = user.name != null ? user.name : "Adventurer";
            EventLog.add(Messages.playerUses(name,
                    item.useVerb != null ? item.useVerb : "use", item.name));
        }
    }

    /**
     * Apply a wand's element to a target tile. Effect strength scales with
     * the wand's own per-item columns ({@link Item#tilesAffected},
     * {@link Item#damage}, plus their per-level increments). Called from
     * rgame's {@code Animator} as the {@code PendingImpact} callback once the
     * wand-missile or ray visual completes — the wand instance is carried
     * through the event so the impact site has access to its data-driven
     * scaling. {@code wand} may be {@code null} (defensive); element only
     * dispatches that need its damage / AOE columns then short-circuit.
     */
    public static void applyWandImpact(Level level, Mob caster, Point target,
                                       Item.ItemEffect element, Item wand,
                                       int effectiveLevel) {
        if (level == null || target == null || element == null) return;
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
        int targetTiles = effectiveTilesAffected(wand, effectiveLevel);
        int areaRadius  = radiusForTileCount(targetTiles);
        switch (element) {
            case WATER -> {
                for (int i = 0; i < targetTiles; i++)
                    SurfaceSystem.addSurface(level, target, Level.Surface.WATER);
            }
            case OIL -> {
                for (int i = 0; i < targetTiles; i++)
                    SurfaceSystem.addSurface(level, target, Level.Surface.OIL);
            }
            case GRASS    -> MobSystem.paintMixedFloraDisc(level, tx, ty, areaRadius);
            case FUNGUS   -> MobSystem.paintVegetationDisc(level, tx, ty, areaRadius, Level.Vegetation.MUSHROOMS);
            case FIRE     -> MobSystem.igniteDisc(level, tx, ty, areaRadius);
            case DETONATION -> {
                int radius = areaRadius + 1;
                MobSystem.igniteDisc(level, tx, ty, radius);
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.ExplosionEffect(target, radius));
                }
                int kb = wand != null ? wand.knockbackSquares : 0;
                if (kb > 0) {
                    int r2det = radius * radius;
                    java.util.List<com.bjsp123.rl2.model.Mob> inBlast =
                            new java.util.ArrayList<>(level.mobs);
                    for (com.bjsp123.rl2.model.Mob m : inBlast) {
                        if (m == caster || m.position == null) continue;
                        int mdx = m.position.tileX() - tx;
                        int mdy = m.position.tileY() - ty;
                        if (mdx * mdx + mdy * mdy <= r2det) {
                            MobSystem.knockBack(level, m, kb, target);
                        }
                    }
                }
            }
            case BANISHMENT -> {
                Mob victim = MobSystem.mobAt(level, target);
                if (victim != null && victim.banishable) {
                    MobSystem.killMob(level, victim, caster);
                }
            }
            case LIGHTNING -> applyLightningChain(level, caster, target, wand, effectiveLevel);
            case MISSILE -> {
                Mob victim = MobSystem.mobAt(level, target);
                if (victim != null) {
                    int dmg = MobSystem.rollRange(effectiveDamageRange(wand, effectiveLevel));
                    MobSystem.processAttack(level, caster, victim, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC);
                }
            }
        }
        if (element != Item.ItemEffect.DETONATION
                && element != Item.ItemEffect.BANISHMENT
                && element != Item.ItemEffect.MISSILE
                && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(target, element));
        }
    }

    /**
     * Wand-of-lightning chain. Lightning hits the mob on {@code target} (if any),
     * then jumps to any other mob within Chebyshev range {@code jumpRadius}
     * that hasn't already been hit, repeating until the chain runs out of
     * eligible neighbours. Each victim takes the wand's rolled damage,
     * doubled if the victim is WET (the {@link Buff.BuffType#WET} buff or
     * standing on a {@link Level.Surface#WATER} / {@link Level.Surface#ICE}
     * tile).
     *
     * <p>Jump radius is normally {@code 2} tiles, but bumps to {@code 4} when
     * the impact tile carries a {@link Level.Surface#WATER} or
     * {@link Level.Surface#BLOOD} surface — those puddles act as electrical
     * conductors so the arc carries further. The {@code caster} itself is an
     * eligible chain target, so a careless lightning shot in a puddled room
     * can fry the wand-user along with everyone else.
     */
    private static void applyLightningChain(Level level, Mob caster, Point target,
                                            Item wand, int effectiveLevel) {
        int tx = target.tileX(), ty = target.tileY();
        Mob first = MobSystem.mobAt(level, target);
        if (first == null) return;

        Level.Surface impactSurf = level.surface[tx][ty];
        int jumpRadius = (impactSurf == Level.Surface.WATER
                       || impactSurf == Level.Surface.BLOOD) ? 4 : 2;

        java.util.Set<Mob> hit = new java.util.HashSet<>();
        java.util.ArrayDeque<Mob> frontier = new java.util.ArrayDeque<>();
        frontier.add(first);
        hit.add(first);

        while (!frontier.isEmpty()) {
            Mob v = frontier.poll();
            int dmg = MobSystem.rollRange(effectiveDamageRange(wand, effectiveLevel));
            if (isWet(level, v)) dmg *= 2;
            MobSystem.processAttack(level, caster, v, dmg,
                    MobSystem.AttackType.MAGIC, MobSystem.DamageElement.SHOCK);
            // Per-victim arc burst so the renderer marks every hit, not just
            // the original target tile.
            if (level.events != null && v.position != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(
                        v.position, Item.ItemEffect.LIGHTNING));
            }

            // Find the next chain target: any unhit mob within jumpRadius of v
            // (Chebyshev). The caster is in the candidate pool, so a backfire
            // is possible. We pick a single nearest candidate per step so the
            // chain looks coherent rather than fanning out into every nearby
            // mob simultaneously.
            Mob next = nearestChainCandidate(level, v, hit, jumpRadius);
            if (next != null) {
                hit.add(next);
                frontier.add(next);
            }
        }
    }

    /** True if {@code m} is electrically conductive — carries the WET buff or
     *  stands on a water / ice tile. Pulled out of the lightning case so the
     *  chain step can reuse it without duplicating the surface read. */
    private static boolean isWet(Level level, Mob m) {
        if (m == null || m.position == null) return false;
        if (BuffSystem.hasBuff(m, Buff.BuffType.WET)) return true;
        int x = m.position.tileX(), y = m.position.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Level.Surface s = level.surface[x][y];
        return s == Level.Surface.WATER || s == Level.Surface.ICE;
    }

    /** Closest unhit mob within Chebyshev range {@code radius} of {@code from}.
     *  Returns null when no eligible candidate exists. */
    private static Mob nearestChainCandidate(Level level, Mob from,
                                             java.util.Set<Mob> hit, int radius) {
        if (level.mobs == null || from.position == null) return null;
        Mob best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Mob m : level.mobs) {
            if (m == from || m == null || m.position == null) continue;
            if (hit.contains(m)) continue;
            if (m.hp <= 0) continue;
            int d = com.bjsp123.rl2.logic.LevelFactoryUtils.chebyshev(from.position, m.position);
            if (d > radius) continue;
            if (d < bestDist) { best = m; bestDist = d; }
        }
        return best;
    }

    /**
     * Generic summon-wand path. If the wand-of-X has a non-null
     * {@link Item#summonsWhenUsed}, this spawns one of that mob type on a free
     * floor tile adjacent to {@code caster}, sets ownership, and scales the
     * summon to the wand's level. Returns {@code true} if a summon happened —
     * callers always pay the move cost regardless, so the wand burns a turn
     * even when the spawn is gated out by population caps or no-room.
     */
    /**
     * Single entry point for firing a wand, used by both the player (after the
     * targeting overlay confirms a tile) and AI (after picking a target). Handles
     * the three wand flavours uniformly:
     * <ul>
     *   <li>summon-style ({@code wand.summonsWhenUsed != null}) — {@code target}
     *       is ignored; defers to {@link #castSummonWand}.</li>
     *   <li>banishment ray — emits {@link GameEvent.WandRayFired}.</li>
     *   <li>everything else — emits {@link GameEvent.WandMissileFired}.</li>
     * </ul>
     * Trajectory is clipped to the first mob standing between caster and target so
     * a wand fired past an enemy resolves on that enemy. Caster always pays
     * {@code attackCost}, even if the summon spawn was gated out — keeps the move
     * cost identical between player and AI paths.
     */
    public static void fireWand(Level level, Mob caster, Item wand, Point target) {
        if (level == null || caster == null || wand == null) return;
        if (wand.summonsWhenUsed != null) {
            castSummonWand(level, caster, wand);
            TurnSystem.applyMoveCost(caster, caster.effectiveStats().attackCost);
            return;
        }
        if (target == null) return;
        Point impact = MobSystem.firstMobBlocking(level, caster.position, target, caster);
        boolean trajectoryVisible =
                MobSystem.trajectoryTouchesVisible(level, caster.position, impact);
        int effLvl = wand.level
                + (caster.perks != null
                        && caster.perks.getOrDefault(Perk.WANDMASTER, 0) > 0 ? 1 : 0);
        if (level.events != null) {
            if (wand.wandEffect == Item.ItemEffect.BANISHMENT) {
                level.events.add(new GameEvent.WandRayFired(
                        caster, caster.position, impact, wand.wandEffect, wand, effLvl,
                        trajectoryVisible));
            } else {
                level.events.add(new GameEvent.WandMissileFired(
                        caster, caster.position, impact, wand.wandEffect, wand, effLvl,
                        trajectoryVisible));
            }
        }
        TurnSystem.applyMoveCost(caster, caster.effectiveStats().attackCost);
    }

    /**
     * Single entry point for the non-targeted use behaviours (eat / drink /
     * grant-perk). Both the player's inventory popup and the AI item-use path
     * route here so the move-cost (always {@code moveCost}) is identical and
     * the dispatch table lives in one place. WAND and throw require a target
     * and use {@link #fireWand} / {@link MobSystem#throwItem} instead.
     */
    public static void useItem(Level level, Mob user, Item item) {
        if (level == null || user == null || item == null || item.useBehavior == null) return;
        switch (item.useBehavior) {
            case EAT         -> eat(level, user, item);
            case DRINK       -> drinkPotion(level, user, item);
            case GRANT_PERK  -> grantPerk(level, user, item);
            case WAND, NONE  -> { return; } // need a target — caller routes elsewhere
        }
        TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
    }

    public static boolean castSummonWand(Level level, Mob caster, Item wand) {
        if (level == null || caster == null || wand == null) return false;
        if (wand.summonsWhenUsed == null) return false;
        if (!MobSystem.levelHasRoomForSpawn(level)) return false;
        com.bjsp123.rl2.model.Point spot =
                MobHooks.freeAdjacentFloor(level, caster.position);
        if (spot == null) return false;
        Mob pet = MobFactory.spawn(wand.summonsWhenUsed, spot);
        if (pet == null) return false;
        pet.owner = caster;
        int petLevel = 1 + Math.max(0, wand.level);
        MobProgression.setSpawnLevel(pet, petLevel);
        level.mobs.add(pet);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(pet, spot));
        }
        return true;
    }

    /** Convert a target tile-count into the smallest Euclidean disc radius whose disc
     *  has at least that many tiles. The disc-area approximation {@code πr²} would land
     *  short on small counts, so we ceil to the next radius. Result is always {@code >= 0}. */
    public static int radiusForTileCount(int tiles) {
        if (tiles <= 1) return 0;
        if (tiles <= 5) return 1;
        int r = (int) Math.ceil(Math.sqrt(tiles / Math.PI));
        return Math.max(1, r);
    }

    /** Display name with level suffix when level > 0. Gems take a different naming scheme
     *  (size prefix + species, no "+N"); see {@link #gemDisplayName}. */
    public static String displayName(Item item) {
        if (item == null) return "";
        if (item.isGem()) return gemDisplayName(item);
        String name = item.name == null ? "" : item.name;
        if (item.level > 0) name = name + " +" + item.level;
        return name;
    }

    /** Size prefix for a gem of size 1–9 ("tiny" … "exquisite"). Caps at the last prefix
     *  if a recipe somehow yields a size higher than 9. */
    public static String gemSizePrefix(int size) {
        return switch (Math.max(1, Math.min(9, size))) {
            case 1 -> "tiny";
            case 2 -> "small";
            case 3 -> "medium";
            case 4 -> "large";
            case 5 -> "fine";
            case 6 -> "impressive";
            case 7 -> "mighty";
            case 8 -> "sublime";
            default -> "exquisite";
        };
    }

    /** "tiny blazingstar", "fine glittershard", … */
    public static String gemDisplayName(Item item) {
        if (item == null || item.gemSpecies == null) return "gem";
        return gemSizePrefix(item.gemSize) + " " + item.gemSpecies.name().toLowerCase();
    }
}
