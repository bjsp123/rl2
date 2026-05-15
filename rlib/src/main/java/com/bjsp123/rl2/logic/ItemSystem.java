package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;

/** Item-use and item-impact behavior. Pure stat/name helpers live in
 *  {@link ItemStats} and {@link ItemNames}. */
public final class ItemSystem {

    private ItemSystem() {}

    private static String actorName(Mob mob) {
        return mob != null && mob.name != null
                ? mob.name
                : TextCatalog.get("eventlog.fallback.adventurer");
    }

    private static String itemName(Item item, String fallbackKey) {
        return item != null && item.name != null
                ? item.name
                : TextCatalog.get(fallbackKey);
    }

    private static String useVerb(Item item, String fallbackKey) {
        return item != null && item.useVerb != null
                ? item.useVerb
                : TextCatalog.get(fallbackKey);
    }

    // -- Use-time effect dispatchers ------------------------------------------

    /**
     * Consume a food item: raise {@code eater}'s satiety by {@code item.foodValue}
     * (capped at {@link GameBalance#STARTING_SATIETY}), apply any buff carried by
     * the item ({@link Item#appliesBuff} - silvery pear's HOPE, conference pear's
     * ESP, etc.), then remove the item from their inventory. No-op for items
     * without food value.
     */
    public static void eat(Level level, Mob eater, Item item) {
        if (eater == null || item == null || item.foodValue <= 0) return;
        eater.satiety = Math.min(GameBalance.STARTING_SATIETY, eater.satiety + item.foodValue);
        applyConsumableBuff(level, eater, item);
        MobSystem.removeFromInventory(eater, item);
        if (eater.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerEats(actorName(eater), item.name));
        } else if (eater.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(eater.name, item.name, false));
        }
    }

    /**
     * Apply a {@link Item.UseBehavior#POWERUP} pickup to {@code picker}.
     * Dispatches on {@link Item#wandEffect}:
     * <ul>
     *   <li>{@code LEVEL_UP} - bumps the picker's character level by one
     *       (delegated to {@link MobProgression#applyLevelUp}).</li>
     *   <li>{@code HP_UP} - restores {@code maxHp * abilityPower} HP,
     *       clamped at the picker's max.</li>
     *   <li>{@code MANA_UP} - every wand-bearing item in the picker's
     *       inventory gains its own {@code chargeGain} of charge,
     *       clamped at {@link Item#maxCharge()}.</li>
     * </ul>
     * Caller (currently {@link MobSystem}'s tile-step hook) is responsible
     * for removing the item from the level - this method only applies the
     * effect.
     */
    public static void applyPowerup(Level level, Mob picker, Item item) {
        if (picker == null || item == null) return;
        Item.ItemEffect eff = item.wandEffect;
        if (eff == null) return;
        switch (eff) {
            case LEVEL_UP -> {
                com.bjsp123.rl2.logic.MobProgression.awardXp(level, picker,
                        GameBalance.XP_PER_POWER_ORB);
            }
            case HP_UP -> {
                double maxHp = picker.effectiveStats().maxHp;
                double healed = Math.max(1.0, maxHp * item.abilityPower);
                double newHp = Math.min(maxHp, picker.hp + healed);
                int delta = (int) Math.round(newHp - picker.hp);
                picker.hp = newHp;
                if (level != null && level.events != null && delta > 0) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.HealApplied(
                            picker, delta));
                }
            }
            case MANA_UP -> {
                if (picker.inventory == null) break;
                for (Item bagItem : picker.inventory.bag) {
                    if (bagItem == null || bagItem.baseChargeMax <= 0) continue;
                    bagItem.charge = Math.min(ItemStats.effectiveMaxCharge(bagItem, picker),
                            bagItem.charge + bagItem.chargeGain);
                }
                for (Item eq : picker.inventory.allEquipped()) {
                    if (eq == null || eq.baseChargeMax <= 0) continue;
                    eq.charge = Math.min(ItemStats.effectiveMaxCharge(eq, picker),
                            eq.charge + eq.chargeGain);
                }
            }
            default -> { /* not a POWERUP effect - ignore */ }
        }
        // Floating-text feedback so the player sees the absorption land.
        if (level != null && level.events != null && picker.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(
                    picker.position, eff));
        }
        if (picker.behavior == Behavior.PLAYER) {
            EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                    TextCatalog.format("eventlog.item.powerupAbsorb",
                            TextCatalog.vars("actor", actorName(picker),
                                    "item", itemName(item, "eventlog.item.powerupFallback"))),
                    com.bjsp123.rl2.model.LogEvent.EventPriority.LOW, true));
        }
    }

    /**
     * Drink a potion. Applies the item's {@link Item#appliesBuff} (level and
     * duration from the item-level helpers). When the potion carries a
     * non-zero {@link Item#damage} range, the drinker takes that damage
     * (level-scaled via {@link #effectiveDamageRange}) - drinking a poison
     * potion now uses the same damage column as throwing it. Item is consumed.
     */
    public static void drinkPotion(Level level, Mob drinker, Item item) {
        if (drinker == null || item == null) return;
        applyPotionEffect(level, drinker, item, drinker);
        emitPotionBurst(level, drinker.position, item);
        MobSystem.removeFromInventory(drinker, item);
        if (drinker.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(drinker),
                    useVerb(item, "eventlog.item.verb.drink"), item.name));
        } else if (drinker.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(drinker.name, item.name, false));
        }
    }

    /**
     * Resolve a potion landing - the same effect that drinking the potion has
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

    /** Apply the potion's drink-time effect to a single mob: any buffs
     *  in {@code item.appliesBuff} plus the rolled damage range when the
     *  potion deals damage on impact. Player-only side-effects (like
     *  reveal-the-level) are now expressed as buff types whose per-turn
     *  handler does the work - see {@link com.bjsp123.rl2.model.Buff.BuffType#INSIGHT}. */
    private static void applyPotionEffect(Level level, Mob mob, Item item, Mob source) {
        if (mob == null || item == null) return;
        applyConsumableBuff(level, mob, item);
        if (item.damage.max() > 0) {
            int dmg = MobSystem.rollRange(ItemStats.effectiveDamageRange(item));
            if (dmg > 0) {
                MobSystem.processAttack(level, source, mob, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.POISON);
            }
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


    /** Apply the item's CSV-declared {@link Item#appliesBuff} to the user, with
     *  level / duration scaled by the standard item-level helpers. No-op when the
     *  item carries no buff. */
    private static void applyConsumableBuff(Level level, Mob user, Item item) {
        if (item == null || item.appliesBuff == null
                || item.appliesBuff.isEmpty()) return;
        int lvl = ItemStats.effectiveBuffLevel(item);
        int dur = ItemStats.effectiveBuffDuration(item);
        for (com.bjsp123.rl2.model.Buff.BuffType b : item.appliesBuff) {
            BuffSystem.apply(level, user, b, lvl, dur, user);
        }
    }

    /**
     * Consume a perk-granting item - currently just power orbs. Awards one perk
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
            EventLog.add(Messages.playerUses(actorName(user),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        }
    }

    public static void grantXP(Level level, Mob user, Item item) {
        if (user == null || item == null) return;
        int xp = GameBalance.XP_PER_POWER_ORB; // currently the only XP-granting item is the power orb, so hardcode the value here
        user.xp += xp;
        MobSystem.removeFromInventory(user, item);
        if (level != null && level.events != null && user.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.XPGainBurst(user.position));
        }
        if (user.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(user),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        }
    }
    /**
     * Apply a wand's element to a target tile. Effect strength scales with
     * the wand's own per-item columns ({@link Item#tilesAffected},
     * {@link Item#damage}, plus their per-level increments). Called from
     * rgame's {@code Animator} as the {@code PendingImpact} callback once the
     * wand-missile or ray visual completes - the wand instance is carried
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
        int targetTiles = ItemStats.effectiveTilesAffected(wand, effectiveLevel);
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
                // Concussive blast - only sets fire to tiles that are
                // intrinsically flammable (grass / mushrooms / trees /
                // oil). Bare stone is left intact.
                MobSystem.igniteFlammableDisc(level, tx, ty, radius);
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
                Mob victim = MobQueries.mobAt(level, target);
                if (victim != null && victim.banishable) {
                    MobSystem.killMob(level, victim, caster);
                }
            }
            case LIGHTNING -> applyLightningChain(level, caster, target, wand, effectiveLevel);
            case MISSILE -> {
                Mob victim = MobQueries.mobAt(level, target);
                if (victim != null) {
                    int dmg = MobSystem.rollRange(ItemStats.effectiveDamageRange(wand, effectiveLevel));
                    dmg = MobSystem.applySurpriseIfNeeded(level, caster, victim, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC);
                    MobSystem.processAttack(level, caster, victim, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC);
                }
            }
            case VOID -> applyVoidImpact(level, target, effectiveLevel);
            case POLYMORPH -> applyPolymorphImpact(level, target, areaRadius);
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
     * {@link Level.Surface#BLOOD} surface - those puddles act as electrical
     * conductors so the arc carries further. The {@code caster} itself is an
     * eligible chain target, so a careless lightning shot in a puddled room
     * can fry the wand-user along with everyone else.
     */
    /** Wand-of-void impact. Tears a chasm at the target tile and pulls
     *  every mob within Chebyshev radius {@code (effectiveLevel / 2) + 1}
     *  toward the centre. Floor-like tiles in the disc convert to
     *  CHASM; small statues are obliterated into chasm too (large
     *  statues survive - they're too anchored). Pulled mobs play the
     *  standard knockback-slide animation; non-flying mobs that land on
     *  a fresh chasm tile fall through via
     *  {@link com.bjsp123.rl2.logic.MobSystem#fallToNextLevel}. */
    static void applyVoidImpact(Level level, com.bjsp123.rl2.model.Point target,
                                int effectiveLevel) {
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
        int radius = Math.max(1, (effectiveLevel / 2) + 1);
        int r2 = radius * radius;

        // Tile conversion pass - floor-like + small statues become CHASM.
        // Walk the disc and switch tiles in place.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                int x = tx + dx, y = ty + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                com.bjsp123.rl2.model.Tile t = level.tiles[x][y];
                if (t == null) continue;
                if (t.isFloorLike()
                        || t == com.bjsp123.rl2.model.Tile.STATUE_SMALL_L
                        || t == com.bjsp123.rl2.model.Tile.STATUE_SMALL_R) {
                    level.tiles[x][y] = com.bjsp123.rl2.model.Tile.CHASM;
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(
                                new com.bjsp123.rl2.model.Point(x, y)));
                    }
                }
            }
        }

        // Mob pull pass - each mob in the disc is yanked one tile toward
        // the centre, riding the standard knockback slide. Iteration uses
        // a snapshot because MobSystem.fallToNextLevel can mutate
        // level.mobs (cross-level transfer when a non-flyer lands on a
        // fresh chasm).
        java.util.List<Mob> snapshot = new java.util.ArrayList<>(level.mobs);
        for (Mob m : snapshot) {
            if (m == null || m.position == null || m.hp <= 0) continue;
            int mdx = m.position.tileX() - tx;
            int mdy = m.position.tileY() - ty;
            if (mdx * mdx + mdy * mdy > r2) continue;
            if (mdx == 0 && mdy == 0) continue;          // mob at the centre
            int sx = -Integer.signum(mdx);
            int sy = -Integer.signum(mdy);
            voidPullMob(level, m, m.position, sx, sy);
        }

        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(
                    target, Item.ItemEffect.VOID));
        }
    }

    /** Slide {@code mob} one tile in direction ({@code sx}, {@code sy})
     *  toward the void centre, emitting a {@code MobKnockedBack} for the
     *  visual. If the destination is a (now-fresh) chasm and the mob
     *  isn't flying, route to the standard
     *  {@link com.bjsp123.rl2.logic.MobSystem#fallToNextLevel} so it
     *  falls through. Stops on out-of-bounds, walls, or other mobs -
     *  the slide just doesn't happen in those edge cases (the void is
     *  a terrain hazard, not a guaranteed teleport). */
    private static void voidPullMob(Level level, Mob mob,
                                    com.bjsp123.rl2.model.Point start,
                                    int sx, int sy) {
        if (sx == 0 && sy == 0) return;
        int nx = start.tileX() + sx;
        int ny = start.tileY() + sy;
        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) return;
        com.bjsp123.rl2.model.Tile t = level.tiles[nx][ny];
        if (t == null || t.blocksMovement()) return;
        // Block on another mob - pull stalls.
        for (Mob other : level.mobs) {
            if (other == mob) continue;
            if (other.position == null) continue;
            if (other.position.tileX() == nx && other.position.tileY() == ny) return;
        }
        com.bjsp123.rl2.model.Point dest = new com.bjsp123.rl2.model.Point(nx, ny);
        mob.position = dest;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobKnockedBack(
                    mob, start, dest, false));
        }
        // Chasm landing follows the standard fall-to-next-level path.
        if (t == com.bjsp123.rl2.model.Tile.CHASM
                && !mob.effectiveStats().flying) {
            com.bjsp123.rl2.logic.MobSystem.fallToNextLevel(level, mob);
        }
    }

    /** Reshape an area: every floor-like tile in the disc has a 50%
     *  chance to reroll to one of {FLOOR, FLOOR_WOOD, FLOOR_SPECIAL,
     *  CHASM}; every non-unique mob in the disc is replaced by a
     *  random species whose intrinsic size lies within +/-1 of the
     *  original's. The player is never polymorphed. */
    private static void applyPolymorphImpact(Level level, Point target, int areaRadius) {
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
        int radius = Math.max(1, areaRadius);
        int r2 = radius * radius;

        com.bjsp123.rl2.model.Tile[] floorRoll = {
                com.bjsp123.rl2.model.Tile.FLOOR,
                com.bjsp123.rl2.model.Tile.FLOOR_WOOD,
                com.bjsp123.rl2.model.Tile.FLOOR_SPECIAL,
                com.bjsp123.rl2.model.Tile.CHASM
        };

        // Tile reshape pass.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                int x = tx + dx, y = ty + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                com.bjsp123.rl2.model.Tile t = level.tiles[x][y];
                if (t == null || !t.isFloorLike()) continue;
                if (POLY_RNG.nextDouble() >= 0.5) continue;
                com.bjsp123.rl2.model.Tile next = floorRoll[POLY_RNG.nextInt(floorRoll.length)];
                level.tiles[x][y] = next;
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(
                            new com.bjsp123.rl2.model.Point(x, y)));
                }
            }
        }

        // Mob reshape pass - snapshot since we mutate level.mobs in place.
        // The disc check uses Chebyshev distance so a target at a corner
        // of the disc is included rather than missed by the Euclidean
        // gate (which clips diagonals at radius 1).
        java.util.List<Mob> snapshot = new java.util.ArrayList<>(level.mobs);
        for (Mob m : snapshot) {
            if (m == null || m.position == null || m.hp <= 0) continue;
            if (m.behavior == Behavior.PLAYER) continue;
            int mdx = Math.abs(m.position.tileX() - tx);
            int mdy = Math.abs(m.position.tileY() - ty);
            if (Math.max(mdx, mdy) > radius) continue;
            MobDefinition oldDef = m.mobType == null ? null : Registries.mob(m.mobType);
            if (oldDef == null || oldDef.unique) continue;
            int oldSize = oldDef.size;
            String pick = pickPolymorphReplacement(oldSize, m.mobType);
            if (pick == null) {
                // Strict size band turned up nothing - fall back to any
                // non-unique non-PLAYER species so the wand doesn't
                // silently no-op on small / large outliers.
                pick = pickPolymorphReplacement(Integer.MIN_VALUE, m.mobType);
                if (pick == null) continue;
            }
            polymorphMob(level, m, pick);
        }
    }

    /** Local RNG for polymorph rolls. Separate from MobSystem.RANDOM so
     *  visual / world-state side-effects don't desync the combat stream. */
    private static final java.util.Random POLY_RNG = new java.util.Random();

    /** Pick a random non-unique, non-player mob type whose intrinsic
     *  size lies in {@code [oldSize-1, oldSize+1]} and whose type
     *  string differs from {@code excludeType}. Pass
     *  {@link Integer#MIN_VALUE} as {@code oldSize} to drop the size
     *  filter entirely (used as a last-resort fallback when the strict
     *  band has no candidates). Returns {@code null} when no such
     *  species exists in the registry. */
    private static String pickPolymorphReplacement(int oldSize, String excludeType) {
        boolean ignoreSize = oldSize == Integer.MIN_VALUE;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String type : Registries.mobTypes()) {
            if (type.equals(excludeType)) continue;
            MobDefinition def = Registries.mob(type);
            if (def == null) continue;
            if (def.unique) continue;
            if (def.behavior == Behavior.PLAYER) continue;
            if (!ignoreSize
                    && (def.size < oldSize - 1 || def.size > oldSize + 1)) continue;
            candidates.add(type);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(POLY_RNG.nextInt(candidates.size()));
    }

    /** Replace {@code old} in place with a fresh mob of {@code newType}
     *  at the same tile. Position-tied state is dropped - the new mob
     *  starts at full HP with the species' default stats. The MobSpawned
     *  event drives the renderer's spawn-grow animation, which reads as
     *  a polymorph poof. */
    private static void polymorphMob(Level level, Mob old, String newType) {
        com.bjsp123.rl2.model.Point pos = old.position;
        Mob fresh = MobFactory.spawn(newType, pos);
        if (fresh == null) return;
        int idx = level.mobs.indexOf(old);
        if (idx < 0) return;
        level.mobs.set(idx, fresh);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(fresh, pos));
        }
    }

    private static void applyLightningChain(Level level, Mob caster, Point target,
                                            Item wand, int effectiveLevel) {
        applyLightningChain(level, caster, target, ItemStats.effectiveDamageRange(wand, effectiveLevel), null);
    }

    /** Package-private overload for brand-triggered lightning - caller supplies
     *  the damage range directly (no wand item required). {@code excluded}, when
     *  non-null, is added to the hit-set before the chain starts so it can never
     *  be targeted (used by brands to protect the attacker from self-damage). */
    static void applyLightningChain(Level level, Mob caster, Point target,
                                    MinMax dmgRange, Mob excluded) {
        int tx = target.tileX(), ty = target.tileY();
        Mob first = MobQueries.mobAt(level, target);
        if (first == null) return;

        Level.Surface impactSurf = level.surface[tx][ty];
        int jumpRadius = (impactSurf == Level.Surface.WATER
                       || impactSurf == Level.Surface.BLOOD) ? 4 : 2;

        java.util.Set<Mob> hit = new java.util.HashSet<>();
        java.util.ArrayDeque<Mob> frontier = new java.util.ArrayDeque<>();
        if (excluded != null) hit.add(excluded);
        frontier.add(first);
        hit.add(first);

        while (!frontier.isEmpty()) {
            Mob v = frontier.poll();
            int dmg = MobSystem.rollRange(dmgRange);
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

    /** True if {@code m} is electrically conductive - carries the WET buff or
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
     * summon to the wand's level. Returns {@code true} if a summon happened -
     * callers always pay the move cost regardless, so the wand burns a turn
     * even when the spawn is gated out by population caps or no-room.
     */
    /**
     * Single entry point for firing a wand, used by both the player (after the
     * targeting overlay confirms a tile) and AI (after picking a target). Handles
     * the three wand flavours uniformly:
     * <ul>
     *   <li>summon-style ({@code wand.summonsWhenUsed != null}) - {@code target}
     *       is ignored; defers to {@link #castSummonWand}.</li>
     *   <li>banishment ray - emits {@link GameEvent.WandRayFired}.</li>
     *   <li>everything else - emits {@link GameEvent.WandMissileFired}.</li>
     * </ul>
     * Trajectory is clipped to the first mob standing between caster and target so
     * a wand fired past an enemy resolves on that enemy. Caster always pays
     * {@code attackCost}, even if the summon spawn was gated out - keeps the move
     * cost identical between player and AI paths.
     */
    public static void fireWand(Level level, Mob caster, Item wand, Point target) {
        if (level == null || caster == null || wand == null) return;
        // Charge gate - wands refuse to fire when current charge is < 1.
        // The summoning branch shares the same gate so a depleted wand of
        // dog won't yip out a free puppy on use.
        if (wand.useBehavior == Item.UseBehavior.WAND && wand.charge < 1f) {
            if (caster.behavior == Behavior.PLAYER) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.format("eventlog.item.fizzleOutOfCharge",
                                TextCatalog.vars("item",
                                        itemName(wand, "eventlog.item.wandFallback"))),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return;
        }
        if (wand.summonsWhenUsed != null) {
            castSummonWand(level, caster, wand);
            wand.charge = Math.max(0f, wand.charge - 1f);
            if (caster.behavior == Behavior.PLAYER) {
                EventLog.add(Messages.playerUses(actorName(caster),
                        useVerb(wand, "eventlog.item.verb.use"), wand.name));
            } else if (caster.name != null && wand.name != null) {
                EventLog.add(Messages.mobUsesItem(caster.name, wand.name, false));
            }
            TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
            return;
        }
        if (target == null) return;
        if (wand.wandEffect == Item.ItemEffect.TELEPORT) {
            int tx = target.tileX(), ty = target.tileY();
            if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
            if (level.tiles[tx][ty].blocksMovement()) return;
            if (MobQueries.mobAt(level, target) != null) return;
            Point teleportOrigin = caster.position;
            caster.position = target;
            if (level.events != null) {
                level.events.add(new GameEvent.MobTeleported(
                        caster, teleportOrigin.tileX(), teleportOrigin.tileY(), tx, ty));
            }
            wand.charge = Math.max(0f, wand.charge - 1f);
            if (caster.behavior == Behavior.PLAYER) {
                EventLog.add(Messages.playerUses(actorName(caster),
                        useVerb(wand, "eventlog.item.verb.use"), wand.name));
            } else if (caster.name != null && wand.name != null) {
                EventLog.add(Messages.mobUsesItem(caster.name, wand.name, false));
            }
            TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
            return;
        }
        Point impact = MobSystem.firstMobBlocking(level, caster.position, target, caster);
        boolean trajectoryVisible =
                MobSystem.trajectoryTouchesVisible(level, caster.position, impact);
        int effLvl = ItemStats.effectiveLevel(wand, caster);
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
        // Charge consumed once the wand has actually fired the projectile.
        wand.charge = Math.max(0f, wand.charge - 1f);
        if (caster.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(caster),
                    useVerb(wand, "eventlog.item.verb.use"), wand.name));
        } else if (caster.name != null && wand.name != null) {
            EventLog.add(Messages.mobUsesItem(caster.name, wand.name, false));
        }
        TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
    }

    /**
     * Single entry point for the non-targeted use behaviours (eat / drink /
     * grant-perk / apply-buff tools). Both the player's inventory popup and the AI item-use path
     * route here so the move-cost (always {@code moveCost}) is identical and
     * the dispatch table lives in one place. WAND and throw require a target
     * and use {@link #fireWand} / {@link MobSystem#throwItem} instead.
     */
    public static void useItem(Level level, Mob user, Item item) {
        if (level == null || user == null || item == null || item.useBehavior == null) return;
        boolean acted = true;
        switch (item.useBehavior) {
            case EAT         -> eat(level, user, item);
            case DRINK       -> drinkPotion(level, user, item);
            case GRANT_PERK  -> grantXP(level, user, item);//grantPerk(level, user, item);
            case APPLYBUFF   -> acted = useChargedBuffTool(level, user, item);
            case WAND, GRAPPLE, JUMP, NONE -> { return; } // need a target or a specialized caller
        }
        if (acted) TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
    }

    private static boolean useChargedBuffTool(Level level, Mob user, Item item) {
        if (item.baseChargeMax > 0 && item.charge < 1f) {
            if (user.behavior == Behavior.PLAYER) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.format("eventlog.item.fizzleOutOfCharge",
                                TextCatalog.vars("item",
                                        itemName(item, "eventlog.item.wandFallback"))),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return false;
        }
        applyConsumableBuff(level, user, item);
        if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
        if (level != null && level.events != null && user.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.PotionBurst(user.position, item));
        }
        if (user.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(user),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        } else if (user.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(user.name, item.name, false));
        }
        return true;
    }

    /**
     * Resolve a grappling-rope use ({@link Item.UseBehavior#GRAPPLE}) targeted
     * at {@code target}. The flow:
     * <ol>
     *   <li>Pick the landing tile - the nearest non-wall 8-neighbour of
     *       {@code caster} (closest to {@code target} on Euclidean ties);
     *       chasms count as valid landings (the grappled subject just
     *       falls in).</li>
     *   <li>Mob on the target tile: if its {@code size} exceeds the item's
     *       {@code abilityPower} (overloaded as the max-size cap for GRAPPLE
     *       items) the rope flashes and fades - emit a {@code GrappleFired}
     *       with {@code success = false} and no movement events. Otherwise
     *       the mob is moved to the landing tile (chasm-fall handled).</li>
     *   <li>Floor items on the target tile: relocated to the landing tile,
     *       or emit {@link com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm}
     *       if the landing is a chasm.</li>
     *   <li>{@code attackCost} is always charged.</li>
     * </ol>
     */
    public static void castGrapple(Level level, Mob caster, Item item, Point target) {
        if (level == null || caster == null || item == null || target == null) return;
        if (caster.position == null) return;
        if (item.baseChargeMax > 0 && item.charge < 1f) {
            if (caster.behavior == Behavior.PLAYER) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.format("eventlog.item.noCharge",
                                TextCatalog.vars("item",
                                        itemName(item, "eventlog.item.itemFallback"))),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return;
        }
        Mob targetMob = MobQueries.mobAt(level,target);
        if (targetMob != null) {
            int maxSize = Math.max(0, (int) item.abilityPower);
            if (targetMob.effectiveStats().size > maxSize) {
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.GrappleFired(
                            caster, caster.position, target, false));
                }
                if (caster.behavior == Behavior.PLAYER) {
                    EventLog.add(Messages.grappleBlocked(
                            targetMob.name != null ? targetMob.name
                                    : TextCatalog.get("eventlog.fallback.creature")));
                }
                if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
                TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
                return;
            }
        }
        if (!MobVisibility.projectileLineReaches(level, caster.position, target, caster)) {
            if (level.events != null)
                level.events.add(new com.bjsp123.rl2.event.GameEvent.GrappleFired(
                        caster, caster.position, target, false));
            if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
            TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
            return;
        }
        Point landing = pickGrappleLanding(level, caster.position, target);
        if (landing == null || landing.equals(target)) {
            // No valid landing (caster boxed in by walls, or the target is
            // already adjacent) - the rope reaches but pulls no-one.
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.GrappleFired(
                        caster, caster.position, target, true));
            }
            if (caster.behavior == Behavior.PLAYER) {
                EventLog.add(Messages.playerUses(actorName(caster),
                        useVerb(item, "eventlog.item.verb.use"), item.name));
            } else if (caster.name != null && item.name != null) {
                EventLog.add(Messages.mobUsesItem(caster.name, item.name, false));
            }
            if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
            TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
            return;
        }
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.GrappleFired(
                    caster, caster.position, target, true));
        }
        boolean landingIsChasm = level.tiles[landing.tileX()][landing.tileY()]
                == com.bjsp123.rl2.model.Tile.CHASM;
        if (targetMob != null) {
            grapplePullMob(level, targetMob, landing, landingIsChasm);
        }
        grapplePullFloorItems(level, target, landing, landingIsChasm);
        if (caster.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(caster),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        } else if (caster.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(caster.name, item.name, false));
        }
        if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
        TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
    }

    /** JUMP-behavior use: teleport the jumper to {@code target} within Chebyshev
     *  radius {@code item.abilityPower}. The target must be a passable, unoccupied tile.
     *  Costs one {@code moveCost}. Emits {@link com.bjsp123.rl2.event.GameEvent.MobJumped}. */
    public static void castJump(Level level, Mob jumper, Item item, Point target) {
        if (level == null || jumper == null || item == null || target == null) return;
        if (jumper.position == null) return;
        if (item.baseChargeMax > 0 && item.charge < 1f) {
            if (jumper.behavior == Behavior.PLAYER) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.format("eventlog.item.noCharge",
                                TextCatalog.vars("item",
                                        itemName(item, "eventlog.item.itemFallback"))),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return;
        }
        int radius = Math.max(0, (int) item.abilityPower);
        int dx = Math.abs(target.tileX() - jumper.position.tileX());
        int dy = Math.abs(target.tileY() - jumper.position.tileY());
        if (Math.max(dx, dy) > radius) return;
        if (target.tileX() < 0 || target.tileY() < 0
                || target.tileX() >= level.width || target.tileY() >= level.height) return;
        com.bjsp123.rl2.model.Tile tile = level.tiles[target.tileX()][target.tileY()];
        if (tile.blocksMovement()) return;
        if (MobQueries.mobAt(level, target) != null) return;
        if (!MobVisibility.jumpPathClear(level, jumper.position, target)) return;
        Point from = jumper.position;
        jumper.position = target;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobJumped(jumper, from, target));
        }
        if (jumper.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(jumper),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        } else if (jumper.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(jumper.name, item.name, false));
        }
        if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
        TurnSystem.applyMoveCost(jumper, jumper.effectiveStats().moveCost);
    }

    /** Find the best grapple landing tile - nearest non-wall 8-neighbour of
     *  {@code casterPos}, preferring the one closest to {@code target} on
     *  Euclidean ties so the dragged subject lands on the natural side of
     *  the caster. Skips tiles already occupied by another mob (the
     *  grapple shouldn't pile two creatures into one tile). Returns
     *  {@code null} only when no valid neighbour exists. */
    private static Point pickGrappleLanding(Level level, Point casterPos, Point target) {
        Point best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        int cx = casterPos.tileX(), cy = casterPos.tileY();
        int tx = target.tileX(),    ty = target.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                com.bjsp123.rl2.model.Tile tile = level.tiles[nx][ny];
                if (tile.blocksMovement()) continue;
                if (MobQueries.mobAt(level,new Point(nx, ny)) != null) continue;
                double d = (nx - tx) * (double) (nx - tx)
                         + (ny - ty) * (double) (ny - ty);
                if (d < bestDist) {
                    bestDist = d;
                    best = new Point(nx, ny);
                }
            }
        }
        return best;
    }

    private static void grapplePullMob(Level level, Mob mob, Point landing,
                                       boolean landingIsChasm) {
        Point start = mob.position;
        if (start == null) return;
        boolean flying = mob.effectiveStats().flying;
        mob.position = landing;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobKnockedBack(
                    mob, start, landing, false));
        }
        if (landingIsChasm && !flying) {
            // Chasm landing - defer to the standard fall-through path so
            // the grapple-pulled mob either lands on the next level
            // (taking half-max-HP fall damage) or dies in the chasm if
            // there's no next level / the fall would kill it.
            MobSystem.fallToNextLevel(level, mob);
        }
    }

    private static void grapplePullFloorItems(Level level, Point target,
                                              Point landing, boolean landingIsChasm) {
        if (level == null || level.items == null) return;
        int tx = target.tileX(), ty = target.tileY();
        java.util.List<Item> moved = new java.util.ArrayList<>();
        for (Item it : level.items) {
            if (it == null || it.location == null) continue;
            if (it.location.tileX() == tx && it.location.tileY() == ty) {
                moved.add(it);
            }
        }
        for (Item it : moved) {
            if (landingIsChasm) {
                level.items.remove(it);
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                            it, landing));
                }
            } else {
                it.location = landing;
            }
        }
    }

    private static void dropInventoryIntoChasm(Level level, Mob mob) {
        if (mob == null || mob.inventory == null) return;
        java.util.List<Item> falling = new java.util.ArrayList<>();
        if (mob.inventory.bag != null) {
            falling.addAll(mob.inventory.bag);
            mob.inventory.bag.clear();
        }
        falling.addAll(mob.inventory.allEquipped());
        mob.inventory.weapon  = null;
        mob.inventory.offhand = null;
        mob.inventory.armor   = null;
        java.util.Arrays.fill(mob.inventory.amulets, null);
        java.util.Arrays.fill(mob.inventory.gems,    null);
        if (level.events != null) {
            for (Item it : falling) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                        it, mob.position));
            }
        }
    }

    public static boolean castSummonWand(Level level, Mob caster, Item wand) {
        if (level == null || caster == null || wand == null) return false;
        if (wand.summonsWhenUsed == null) return false;
        if (!MobQueries.levelHasRoomForSpawn(level)) return false;
        com.bjsp123.rl2.model.Point spot =
                MobHooks.freeAdjacentFloor(level, caster.position);
        if (spot == null) return false;
        Mob pet = MobFactory.spawn(wand.summonsWhenUsed, spot);
        if (pet == null) return false;
        pet.owner = caster;
        if (caster != null) caster.beastsTamed++;
        int petLevel = 1 + Math.max(0, wand.level);
        MobProgression.setSpawnLevel(pet, petLevel);
        level.mobs.add(pet);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(pet, spot));
        }
        return true;
    }

    /** Convert a target tile-count into the smallest Euclidean disc radius whose disc
     *  has at least that many tiles. The disc-area approximation {@code pir^2} would land
     *  short on small counts, so we ceil to the next radius. Result is always {@code >= 0}. */
    public static int radiusForTileCount(int tiles) {
        if (tiles <= 1) return 0;
        if (tiles <= 5) return 1;
        int r = (int) Math.ceil(Math.sqrt(tiles / Math.PI));
        return Math.max(1, r);
    }

}
