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

    private static final java.util.Random RANDOM = new java.util.Random();

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
        com.bjsp123.rl2.util.ActionTracker.bumpEat(eater);
        eater.satiety = Math.min(GameBalance.STARTING_SATIETY, eater.satiety + item.foodValue);
        applyConsumableBuff(level, eater, item);
        applyManaFountRecharge(level, eater);
        MobSystem.removeFromInventory(eater, item);
        if (eater.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerEats(actorName(eater), item.name));
        } else if (eater.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(eater.name, item.name, false));
        }
    }

    /** MANA_FOUNT perk hook - on food / potion consumption, every wand in the
     *  user's bag + equipped slots gains {@code perkLvl / 2} charges,
     *  clamped at each wand's effective max charge. No-op when the user doesn't
     *  carry the perk. Called from {@link #eat} and {@link #drinkPotion}.
     *
     *  <p>Half-charges are resolved probabilistically: odd levels grant a
     *  50% chance of one extra charge on top of the integer half. So L1 is
     *  a coinflip for +1 charge, L2 = +1 always, L3 = +1 always plus a
     *  coinflip for +2, ..., L10 = +5 always. */
    private static void applyManaFountRecharge(Level level, Mob user) {
        if (user == null || user.perks == null || user.inventory == null) return;
        int lvl = user.perks.getOrDefault(com.bjsp123.rl2.model.Perk.MANA_FOUNT, 0);
        if (lvl <= 0) return;
        int recharge = lvl / 2;
        if ((lvl & 1) == 1 && RANDOM.nextBoolean()) recharge++;
        if (recharge <= 0) return;
        boolean topped = false;
        if (user.inventory.bag != null) {
            for (Item bagItem : user.inventory.bag) {
                if (bagItem == null) continue;
                if (bagItem.useBehavior != Item.UseBehavior.WAND) continue;
                if (bagItem.baseChargeMax <= 0) continue;
                float max = ItemStats.effectiveMaxCharge(bagItem, ItemStats.effectiveLevel(bagItem, user));
                float before = bagItem.charge;
                bagItem.charge = Math.min(max, bagItem.charge + recharge);
                if (bagItem.charge > before) topped = true;
            }
        }
        for (Item eq : user.inventory.allEquipped()) {
            if (eq == null) continue;
            if (eq.useBehavior != Item.UseBehavior.WAND) continue;
            if (eq.baseChargeMax <= 0) continue;
            float max = ItemStats.effectiveMaxCharge(eq, ItemStats.effectiveLevel(eq, user));
            float before = eq.charge;
            eq.charge = Math.min(max, eq.charge + recharge);
            if (eq.charge > before) topped = true;
        }
        if (topped && level != null && level.events != null && user.position != null) {
            // Reuse the heal-applied floater as a "+N mana" visual cue.
            level.events.add(new com.bjsp123.rl2.event.GameEvent.HealApplied(user, recharge));
        }
    }

    /**
     * Apply a {@link Item.UseBehavior#POWERUP} pickup to {@code picker}.
     * Dispatches on {@link Item#wandEffect}:
     * <ul>
     *   <li>{@code LEVEL_UP} - bumps the picker's character level by one
     *       (delegated to {@link MobProgression#applyLevelUp}).</li>
     *   <li>{@code HP_UP} - restores {@code maxHp * effectPower} HP,
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
                if(item.effectPower >0){
                    com.bjsp123.rl2.logic.MobProgression.awardXp(level, picker,
                        (int) (item.effectPower * GameBalance.XP_PER_POWER_ORB));
                } else {
                    com.bjsp123.rl2.logic.MobProgression.awardXp(level, picker,
                        GameBalance.XP_PER_POWER_ORB);
                }
                // XPGainBurst drives both the orange/yellow/white pickup
                // sparkles AND the xppill pickup sound in the Animator;
                // without this event the absorb is silent (which is what
                // an XPPILL / POWER_ORB pickup sounded like before).
                if (level != null && level.events != null && picker.position != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.XPGainBurst(picker.position));
                }
            }
            case HP_UP -> {
                double maxHp = picker.effectiveStats().maxHp;
                double healed = Math.max(1.0, maxHp * item.effectPower);
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
                    bagItem.charge = Math.min(
                            ItemStats.effectiveMaxCharge(bagItem, ItemStats.effectiveLevel(bagItem, picker)),
                            bagItem.charge + (bagItem.chargeGain*GameBalance.MANA_PER_PILL));
                }
                for (Item eq : picker.inventory.allEquipped()) {
                    if (eq == null || eq.baseChargeMax <= 0) continue;
                    eq.charge = Math.min(
                            ItemStats.effectiveMaxCharge(eq, ItemStats.effectiveLevel(eq, picker)),
                            eq.charge + (eq.chargeGain*GameBalance.MANA_PER_PILL));
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
        com.bjsp123.rl2.util.ActionTracker.bumpPotion(drinker);
        applyPotionEffect(level, drinker, item, drinker);
        applyManaFountRecharge(level, drinker);
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
        if (item.damage > 0) {
            int dmg = MobSystem.rollRange(ItemStats.effectiveDamageRange(item));
            if (dmg > 0) {
                MobSystem.processAttack(level, source, mob, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.POISON,
                        null, new MobSystem.DamageCause(source, item, "potion"));
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
     *  item carries no buff. Uses the holder-aware {@code effectiveLevel} so
     *  perks / SORCERY / sorcery brands shape the applied buff. */
    private static void applyConsumableBuff(Level level, Mob user, Item item) {
        if (item == null || item.appliesBuff == null
                || item.appliesBuff.isEmpty()) return;
        int effLvl = ItemStats.effectiveLevel(item, user);
        int lvl = ItemStats.effectiveBuffLevel(item, effLvl);
        int dur = ItemStats.effectiveBuffDuration(item, effLvl);
        for (com.bjsp123.rl2.model.Buff.BuffType b : item.appliesBuff) {
            BuffSystem.apply(level, user, b, lvl, dur, user, item);
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
     * the wand's own per-item columns ({@link Item#effectSize},
     * {@link Item#damage}, plus their per-level increments).
     *
     * <p><b>Must run synchronously from {@link #fireWand}, not from an
     * Animator PendingImpact callback.</b> See the fireWand and
     * {@link com.bjsp123.rl2.logic.MobSystem#throwItem} javadocs for the
     * core invariant: a ranged attack must complete and deal damage before
     * the defender gets to move. Letting the Animator drive impact gives
     * the target ticks to escape the missile / ray's path - the wand-fizzle
     * regression. Visual delay of damage popups / death fades is a
     * renderer-side concern, decoupled from this mutation.
     *
     * <p>{@code wand} may be {@code null} (defensive); elements that need
     * its damage / AOE columns then short-circuit.
     */
    public static void applyWandImpact(Level level, Mob caster, Point target,
                                       Item.ItemEffect element, Item wand,
                                       int effectiveLevel) {
        if (level == null || target == null || element == null) return;
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
        int targetTiles = ItemStats.effectiveSize(wand, effectiveLevel);
        switch (element) {
            case WATER -> {
                for (int i = 0; i < targetTiles; i++)
                    SurfaceSystem.addSurface(level, target, Level.Surface.WATER);
            }
            case OIL -> {
                for (int i = 0; i < targetTiles; i++)
                    SurfaceSystem.addSurface(level, target, Level.Surface.OIL);
            }
            case GRASS -> {
                // Mix of grass and trees - per-drop coin flip; cascade outward
                // from the target as each drop fills its cell.
                for (int i = 0; i < targetTiles; i++) {
                    Level.Vegetation v = RANDOM.nextDouble() < 0.4
                            ? Level.Vegetation.TREES
                            : Level.Vegetation.GRASS;
                    VegetationSystem.addVegetation(level, target, v);
                }
            }
            case FUNGUS -> {
                for (int i = 0; i < targetTiles; i++)
                    VegetationSystem.addVegetation(level, target, Level.Vegetation.MUSHROOMS);
            }
            case FIRE -> {
                // Bomb-style packing: ignite the closest-to-target unblocked
                // tiles, capped at effectSize.
                for (Point p : packTilesAround(level, target, targetTiles)) {
                    FireSystem.ignite(level, p.tileX(), p.tileY());
                }
            }
            case DETONATION -> {
                java.util.List<Point> disc = packTilesAround(level, target, targetTiles);
                // Concussive blast - only sets fire to tiles that are
                // intrinsically flammable (grass / mushrooms / trees /
                // oil). Bare stone is left intact.
                for (Point p : disc) {
                    MobSystem.igniteIfFlammable(level, p.tileX(), p.tileY());
                }
                if (level.events != null) {
                    int radius = radiusForTileCount(targetTiles);
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.ExplosionEffect(target, radius));
                }
                // 1. Damage first. Roll once and apply to every mob in the
                //    disc; collect survivors so we don't knockback corpses.
                java.util.List<Mob> survivors = new java.util.ArrayList<>();
                if (wand != null && wand.damage > 0) {
                    int dmg = MobSystem.rollRange(
                            ItemStats.effectiveDamageRange(wand, effectiveLevel));
                    for (Point p : disc) {
                        Mob m = MobQueries.mobAt(level, p);
                        if (m == null || m == caster) continue;
                        MobSystem.processAttack(level, caster, m, dmg,
                                MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC, null,
                                new MobSystem.DamageCause(caster, wand, "magic"));
                        BrandSystem.applyBrandOnHit(level, caster, m, wand);
                        if (m.hp > 0) survivors.add(m);
                    }
                } else {
                    for (Point p : disc) {
                        Mob m = MobQueries.mobAt(level, p);
                        if (m != null && m != caster && m.hp > 0) survivors.add(m);
                    }
                }
                // 2. Knockback survivors only.
                int kb = wand != null ? wand.knockbackSquares : 0;
                if (kb > 0) {
                    MobSystem.DamageCause kbCause = new MobSystem.DamageCause(caster, wand, "wall-slam");
                    for (Mob m : survivors) {
                        MobSystem.knockBack(level, m, kb, target, 0, kbCause);
                    }
                }
            }
            case BANISHMENT -> {
                Mob victim = MobQueries.mobAt(level, target);
                if (victim != null && victim.banishable) {
                    MobSystem.killMob(level, victim, caster);
                }
            }
            case BLAST -> {
                // Mirror of the bomb BLAST branch in MobSystem.applyThrowImpact,
                // adapted as a wand: damage to every mob in the disc, blast
                // particle effect on each tile. No knockback, no smoke clouds
                // (this is the lighter wand variant).
                java.util.List<Point> blastDisc = packTilesAround(level, target, targetTiles);
                int dmg = wand != null && wand.damage > 0
                        ? MobSystem.rollRange(
                                ItemStats.effectiveDamageRange(wand, effectiveLevel))
                        : 0;
                for (Point p : blastDisc) {
                    if (level.events != null) {
                        level.events.add(
                                new com.bjsp123.rl2.event.GameEvent.BlastEffect(p));
                    }
                    if (dmg <= 0) continue;
                    Mob m = MobQueries.mobAt(level, p);
                    if (m == null || m == caster) continue;
                    MobSystem.processAttack(level, caster, m, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC, null,
                            new MobSystem.DamageCause(caster, wand, "blast"));
                    BrandSystem.applyBrandOnHit(level, caster, m, wand);
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
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC, null,
                            new MobSystem.DamageCause(caster, wand, "missile"));
                    BrandSystem.applyBrandOnHit(level, caster, victim, wand);
                }
            }
            case VOID -> applyVoidImpact(level, target, effectiveLevel);
            case POLYMORPH -> applyPolymorphImpact(level, target, radiusForTileCount(targetTiles));
        }
        if (element != Item.ItemEffect.DETONATION
                && element != Item.ItemEffect.BANISHMENT
                && element != Item.ItemEffect.MISSILE
                && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(target, element));
        }
        // Step 4 complete - clear the pending-impact gate so step 5 begins.
        if (level.pendingImpactCount > 0) level.pendingImpactCount--;
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
                    // Floor under any mob / item just vanished - send them
                    // down. Non-flying mobs fall via the standard
                    // fall-to-next-level path; items relocate to the same
                    // destination. Flying mobs hover and are unaffected.
                    MobSystem.applyChasmFallToTile(level, x, y);
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
                // Floor became CHASM in the reroll - send anything on it
                // down (or to depth 1 as the fallback).
                if (next == com.bjsp123.rl2.model.Tile.CHASM) {
                    MobSystem.applyChasmFallToTile(level, x, y);
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
        // Attitude survives the shape change. AI mode (behavior), HP, stats,
        // inventory and perks all reset to the new species' defaults, but
        // every field read by MobSystem.getAttitudeToMob is inherited - so a
        // tamed dog stays loyal, a kobold-vs-orc faction war keeps warring,
        // and a flee-from-cats kitten still flees cats even as a bat.
        fresh.owner         = old.owner;
        fresh.faction       = old.faction;
        fresh.enemyFactions = old.enemyFactions != null
                ? new java.util.HashSet<>(old.enemyFactions)
                : null;
        fresh.attackTypes   = old.attackTypes != null
                ? new java.util.HashSet<>(old.attackTypes)
                : null;
        fresh.fleeTypes     = old.fleeTypes != null
                ? new java.util.HashSet<>(old.fleeTypes)
                : null;
        level.mobs.set(idx, fresh);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(fresh, pos));
        }
    }

    private static void applyLightningChain(Level level, Mob caster, Point target,
                                            Item wand, int effectiveLevel) {
        // Capture the primary target before the chain runs - it may die mid-
        // chain, after which BrandSystem can't apply ON_FIRE / POISONCLOUD
        // etc. to its tile. The brand fires only on the FIRST hit (the wand
        // launches at one mob; the lightning JUMPS to others, but the brand
        // tag is conceptually carried by the launch).
        Mob primary = MobQueries.mobAt(level, target);
        applyLightningChain(level, caster, target, ItemStats.effectiveDamageRange(wand, effectiveLevel),
                wand, null);
        if (primary != null) BrandSystem.applyBrandOnHit(level, caster, primary, wand);
    }

    /** Package-private overload for brand-triggered lightning - caller supplies
     *  the damage range directly (no wand item required) and the originating
     *  item (the brand-carrying weapon, or the wand for direct wand fire).
     *  {@code excluded}, when non-null, is added to the hit-set before the
     *  chain starts so it can never be targeted (used by brands to protect
     *  the attacker from self-damage). */
    static void applyLightningChain(Level level, Mob caster, Point target,
                                    MinMax dmgRange, Item originItem, Mob excluded) {
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
                    MobSystem.AttackType.MAGIC, MobSystem.DamageElement.SHOCK, null,
                    new MobSystem.DamageCause(caster, originItem, "lightning"));
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
     *
     * <p><b>DO NOT DEFER THE IMPACT.</b> This function MUST call
     * {@link #applyWandImpact} synchronously before returning - same invariant
     * as {@link com.bjsp123.rl2.logic.MobSystem#throwItem}. A ranged attack
     * in this game must complete and deal damage BEFORE the defender gets a
     * chance to move; if the rgame Animator's PendingImpact / arc-completion
     * callback drives wand impact, the target steps out of the missile / ray
     * during the visual flight and the wand silently misses. This regression
     * has been introduced and re-fixed multiple times - if you see this
     * function emit {@code WandMissileFired} / {@code WandRayFired} but NOT
     * call {@code applyWandImpact}, you're looking at the regression.
     *
     * <p>The rgame Animator may delay damage popups / impact flashes / death
     * fades to align with the missile / ray visual arrival - that's a
     * renderer-side concern. World state is mutated synchronously here.
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
        // Counted past the fizzle gate so only successful zaps tally.
        com.bjsp123.rl2.util.ActionTracker.bumpWand(caster);
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
        // ANIMATION-GATED LIFECYCLE: defer world-state mutation to step 4 of
        // the lifecycle - fires when the missile / ray visual lands. The
        // pending-impact gate prevents any other mob from acting until the
        // resolve callback runs. See MobSystem.throwItem javadoc for the
        // full 5-step model.
        final Mob casterFinal = caster;
        final Point impactFinal = impact;
        final Item.ItemEffect element = wand.wandEffect;
        final Item wandFinal = wand;
        final int effLvlFinal = effLvl;
        MobSystem.queuePendingImpact(level,
                () -> applyWandImpact(level, casterFinal, impactFinal, element, wandFinal, effLvlFinal));
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
            case WAND, GRAPPLE, JUMP, CHARGE, TELEPORT, NONE -> { return; } // need a target or a specialized caller
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
        com.bjsp123.rl2.util.ActionTracker.bumpTool(user);
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
     *       {@code effectPower} (max-size cap for GRAPPLE items) the rope
     *       flashes and fades - emit a {@code GrappleFired}
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
            int maxSize = Math.max(0, (int) ItemStats.effectivePower(item));
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

    /** CHARGE-behavior use: dash adjacent to {@code target} mob and deliver a
     *  free melee swing followed by a knockback. Targeting overlay (in
     *  {@code PlayController}) has already vetted that the target is a visible
     *  hostile mob within Chebyshev range. Returns silently when the dash is
     *  invalid (no mob, no adjacent landing, out of charges, etc.). One
     *  charge consumed; costs one {@code moveCost}. */
    public static void castCharge(Level level, Mob user, Item item, Point target) {
        if (level == null || user == null || item == null || target == null) return;
        if (user.position == null) return;
        if (item.baseChargeMax > 0 && item.charge < 1f) {
            if (user.behavior == Behavior.PLAYER) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.format("eventlog.item.noCharge",
                                TextCatalog.vars("item",
                                        itemName(item, "eventlog.item.itemFallback"))),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return;
        }
        Mob victim = MobQueries.mobAt(level, target);
        if (victim == null || victim == user || victim.hp <= 0) return;

        int effLvl = ItemStats.effectiveLevel(item, user);
        int dashRange = Math.max(1, ItemStats.effectiveRange(item, effLvl));
        int dx = Math.abs(target.tileX() - user.position.tileX());
        int dy = Math.abs(target.tileY() - user.position.tileY());
        if (Math.max(dx, dy) > dashRange) return;

        Point arrival = pickChargeArrival(level, user.position, target);
        if (arrival == null) return;

        // Dash visual + position update.
        Point from = user.position;
        user.position = arrival;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobJumped(user, from, arrival));
        }

        // Free melee swing - mirrors {@code MobSystem.attack}'s damage roll
        // but bypasses the to-hit roll (the dash strike auto-connects) and
        // skips the standard attack-time knockback so we can apply the
        // jade-bull's own knockback below.
        int rawAtk = MobSystem.rollRange(MobSystem.rawDamageRange(user));
        int armor  = MobSystem.rollRange(MobSystem.resistRange(victim));
        int physical = Math.max(0, rawAtk - armor);
        int ap = MobSystem.rollRange(MobSystem.apDamageRange(user));
        int total = physical + ap;
        MobSystem.processAttack(level, user, victim, total,
                MobSystem.AttackType.MELEE, MobSystem.DamageElement.PHYSICAL);

        // Knockback - jade bull's intrinsic knockbackSquares scaled by Rule A
        // (additive half-level) then the standard KNOCKBACK-perk contribution
        // (capped at 5 tiles; levels 6-10 add wall-slam damage instead).
        if (victim.hp > 0) {
            int kb = ItemStats.effectiveKnockback(item);
            int perkLvl = user.perks != null
                    ? user.perks.getOrDefault(com.bjsp123.rl2.model.Perk.KNOCKBACK, 0)
                    : 0;
            kb += Math.min(5, perkLvl);
            int wallSlam = Math.max(0, perkLvl - 5);
            if (kb > 0) {
                MobSystem.knockBack(level, victim, kb, arrival, wallSlam,
                        new MobSystem.DamageCause(user, item, "wall-slam"));
            }
        }

        if (item.baseChargeMax > 0) item.charge = Math.max(0f, item.charge - 1f);
        if (user.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerUses(actorName(user),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        }
        TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
    }

    /** Pick the dash arrival tile: an 8-neighbor of {@code target} that is
     *  walkable, unoccupied, and as close as possible to {@code userPos} (so
     *  the dash lands on the natural side of the victim). Returns null when
     *  no neighbour is free. */
    private static Point pickChargeArrival(Level level, Point userPos, Point target) {
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        int tx = target.tileX(), ty = target.tileY();
        int ux = userPos.tileX(), uy = userPos.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = tx + dx, ny = ty + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny].blocksMovement()) continue;
                if (MobQueries.mobAt(level, new Point(nx, ny)) != null) continue;
                int d = Math.max(Math.abs(nx - ux), Math.abs(ny - uy));
                if (d < bestDist) {
                    bestDist = d;
                    best = new Point(nx, ny);
                }
            }
        }
        return best;
    }

    /** JUMP-behavior use: teleport the jumper to {@code target} within Chebyshev
     *  radius {@link ItemStats#effectiveRange}. The target must be a passable,
     *  unoccupied tile. Costs one {@code moveCost}. Emits
     *  {@link com.bjsp123.rl2.event.GameEvent.MobJumped}. */
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
        // JUMP perk scales the item's effective level via effectiveRange,
        // so the perk improves jump distance and charge regen / max charge
        // (the latter via ItemStats.effectiveMaxCharge).
        int effLvl = ItemStats.effectiveLevel(item, jumper);
        int radius = Math.max(0, ItemStats.effectiveRange(item, effLvl));
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
        boolean landingVisible = landingIsChasm
                && MobSystem.tileVisibleToPlayer(level, landing);
        for (Item it : moved) {
            if (landingIsChasm) {
                level.items.remove(it);
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                            it, landing));
                }
                if (landingVisible) {
                    com.bjsp123.rl2.logic.EventLog.add(
                            com.bjsp123.rl2.logic.Messages.itemFellInChasm(it.name, true));
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
        boolean tileVisible = MobSystem.tileVisibleToPlayer(level, mob.position);
        boolean involvesPlayer = mob.behavior == Mob.Behavior.PLAYER;
        if (level.events != null) {
            for (Item it : falling) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                        it, mob.position));
                if (tileVisible) {
                    com.bjsp123.rl2.logic.EventLog.add(
                            com.bjsp123.rl2.logic.Messages.itemFellInChasm(it.name, involvesPlayer));
                }
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
        // Pet spawn level = ceil(effLvl / 2). Effective level includes
        // WANDMASTER perk + sorcery brand / buff bonuses, so a Mage's pet
        // strengthens with their casting build.
        int effLvl = ItemStats.effectiveLevel(wand, caster);
        int petLevel = (Math.max(0, effLvl) + 1) / 2;
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

    /** Pack {@code count} tiles around {@code centre}, "closest first" by
     *  (Chebyshev distance, then Manhattan distance, then random). Only
     *  returns tiles that have a clear projectile path from the centre
     *  (no walls or closed doors in the way) — tiles in line-of-sight
     *  shadow are skipped, so a bomb behind a corner doesn't wrap around.
     *
     *  <p>Centre tile itself is always first if it's in-bounds. Each
     *  Chebyshev ring fills cardinal-extreme tiles first (manhattan = R),
     *  then intermediate edges, then pure diagonals (manhattan = 2R);
     *  within each band the order is randomised. If a ring can't fully
     *  fill from the remaining count budget, that ring's selection is
     *  randomly truncated — so a partial outer ring reads as scattered
     *  affected tiles rather than a clean arc.
     *
     *  <p>Hard cap of 16 rings (a 33×33 area, ~1000 tiles) so a malformed
     *  effectSize doesn't iterate forever. Returns fewer than
     *  {@code count} tiles when the reachable area can't supply that many. */

    /**
     * Tiles a thrown {@code item} will affect when it lands on {@code impact}.
     * Mirrors the per-category branches in {@link MobSystem#applyThrowImpact}
     * so AI prediction and any UI preview agree with the actual resolution.
     *
     * <ul>
     *   <li>{@link com.bjsp123.rl2.model.Item.UseBehavior#DRINK} potions
     *       splash onto a chebyshev-1 disc (3x3).</li>
     *   <li>Items with positive {@link ItemStats#effectiveSize} (bombs) pack
     *       that many tiles outward via {@link #packTilesAround}.</li>
     *   <li>Single-target throws (effectSize 0, non-potion) hit only the
     *       impact tile.</li>
     * </ul>
     */
    public static java.util.List<Point> tilesAffectedByThrow(
            Level level, Point impact, com.bjsp123.rl2.model.Item item, com.bjsp123.rl2.model.Mob thrower) {
        if (level == null || impact == null || item == null) return java.util.Collections.emptyList();
        if (item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.DRINK) {
            java.util.List<Point> out = new java.util.ArrayList<>(9);
            int cx = impact.tileX(), cy = impact.tileY();
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    out.add(new Point(x, y));
                }
            }
            return out;
        }
        int effLvl = ItemStats.effectiveLevel(item, thrower);
        int size = ItemStats.effectiveSize(item, effLvl);
        if (size >= 1) return packTilesAround(level, impact, size);
        return java.util.Collections.singletonList(impact);
    }

    public static java.util.List<Point> packTilesAround(Level level, Point centre, int count) {
        return packTilesAround(level, centre, count, RANDOM);
    }

    /** Variant that takes an explicit Random source. Used by render-frame
     *  previews (targeting AoE disc) which need a deterministic, stable
     *  layout - reseeding from the centre tile each frame keeps the preview
     *  from shimmering between frames AND from consuming the global RNG. */
    public static java.util.List<Point> packTilesAround(Level level, Point centre, int count, java.util.Random rng) {
        java.util.List<Point> out = new java.util.ArrayList<>();
        if (level == null || centre == null || count <= 0) return out;
        int cx = centre.tileX(), cy = centre.tileY();
        if (cx < 0 || cy < 0 || cx >= level.width || cy >= level.height) return out;
        out.add(centre);
        if (out.size() >= count) return out;
        for (int r = 1; r <= 16 && out.size() < count; r++) {
            java.util.List<Point> cardinals = new java.util.ArrayList<>();
            java.util.List<Point> edges     = new java.util.ArrayList<>();
            java.util.List<Point> diagonals = new java.util.ArrayList<>();
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                    Point p = new Point(nx, ny);
                    if (!MobVisibility.jumpPathClear(level, centre, p)) continue;
                    int m = Math.abs(dx) + Math.abs(dy);
                    if (m == r)            cardinals.add(p);
                    else if (m == 2 * r)   diagonals.add(p);
                    else                   edges.add(p);
                }
            }
            java.util.Collections.shuffle(cardinals, rng);
            java.util.Collections.shuffle(edges,     rng);
            java.util.Collections.shuffle(diagonals, rng);
            for (Point p : cardinals) { if (out.size() >= count) return out; out.add(p); }
            for (Point p : edges)     { if (out.size() >= count) return out; out.add(p); }
            for (Point p : diagonals) { if (out.size() >= count) return out; out.add(p); }
        }
        return out;
    }

}
