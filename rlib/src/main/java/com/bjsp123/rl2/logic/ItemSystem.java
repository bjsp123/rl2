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

    // Package-private so GemSystem (gem-scroll effects, moved out of this class)
    // can share the same registered RNG stream.
    static final java.util.Random RANDOM =
            com.bjsp123.rl2.util.SimRng.register("ItemSystem", new java.util.Random());

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
     * Consume a food item: apply any buff carried by the item
     * ({@link Item#appliesBuff} - silvery pear's HOPE, conference pear's ESP, etc.),
     * then remove it from the eater's inventory. Edibility is the EAT use-behavior;
     * some foods confer no buff (plain rations) and are simply consumed.
     */
    public static void eat(Level level, Mob eater, Item item) {
        if (eater == null || item == null
                || item.useBehavior != Item.UseBehavior.EAT) return;
        com.bjsp123.rl2.util.ActionTracker.bumpEat(eater);
        if (eater.isPlayer) eater.runStats.foodEaten++;
        applyConsumableBuff(level, eater, item);
        // Charge-restoring food (e.g. blue pear) refills wands like manapills.
        if (item.wandEffect == Item.ItemEffect.MANA_UP) {
            absorbManaPills(eater, manaPillStrength(item));
        }
        applyManaFountRecharge(level, eater);
        MobSystem.removeFromInventory(eater, item);
        if (eater.isPlayer) {
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
        // Procs only half the time - consuming food / a potion has a 50% chance to recharge.
        if (!RANDOM.nextBoolean()) return;
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
     * effect. Returns {@code true} when the powerup actually did something
     * (so the caller should consume it) and {@code false} when it was a no-op
     * - a HP pill at full health or a charge pill with no wand to refill -
     * so the pill stays on the floor for when it's useful.
     */
    public static boolean applyPowerup(Level level, Mob picker, Item item) {
        // Same predicate the AI / auto-explore planners consult, so "would it
        // apply" and "did it apply" can never drift apart.
        if (!powerupWouldApply(picker, item)) return false;
        Item.ItemEffect eff = item.wandEffect;
        boolean consumed;
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
                consumed = true;   // XP is always worth taking
            }
            case HP_UP -> {
                double maxHp = picker.effectiveStats().maxHp;
                double healed = Math.max(1.0, maxHp * item.effectPower);
                double newHp = Math.min(maxHp, picker.hp + healed);
                int delta = (int) Math.round(newHp - picker.hp);
                // Already at (or within rounding of) full HP - leave the pill.
                if (delta <= 0) {
                    consumed = false;
                } else {
                    picker.hp = newHp;
                    if (level != null && level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.HealApplied(
                                picker, delta));
                    }
                    consumed = true;
                }
            }
            case MANA_UP -> consumed = absorbManaPills(picker, manaPillStrength(item));
            default -> consumed = true;   // unknown POWERUP effect: consume so it can't stick
        }
        if (!consumed) return false;
        // Floating-text feedback so the player sees the absorption land.
        if (level != null && level.events != null && picker.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(
                    picker.position, eff));
        }
        if (picker.isPlayer) {
            EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                    TextCatalog.format("eventlog.item.powerupAbsorb",
                            TextCatalog.vars("actor", actorName(picker),
                                    "item", itemName(item, "eventlog.item.powerupFallback"))),
                    com.bjsp123.rl2.model.LogEvent.EventPriority.LOW, true));
        }
        return true;
    }

    /**
     * Pure predicate mirror of {@link #applyPowerup}: would the powerup have any
     * effect on {@code picker} right now? {@code applyPowerup} gates on this, and
     * the AI / auto-explore planners consult it before walking to a pill - a pill
     * that would refuse to consume stays on the floor and would otherwise be
     * re-targeted forever.
     * <ul>
     *   <li>{@code LEVEL_UP} - always (XP is always worth taking);</li>
     *   <li>{@code HP_UP} - only when the rounded heal is at least 1 HP;</li>
     *   <li>{@code MANA_UP} - only when some bag or equipped item would actually
     *       gain charge (positive {@code chargeGain}, below its effective max).</li>
     * </ul>
     */
    public static boolean powerupWouldApply(Mob picker, Item item) {
        if (picker == null || item == null || item.wandEffect == null) return false;
        switch (item.wandEffect) {
            case HP_UP -> {
                double maxHp = picker.effectiveStats().maxHp;
                double healed = Math.max(1.0, maxHp * item.effectPower);
                return (int) Math.round(Math.min(maxHp, picker.hp + healed) - picker.hp) > 0;
            }
            case MANA_UP -> {
                return manaPillsWouldAdd(picker, manaPillStrength(item));
            }
            default -> {
                return true;   // LEVEL_UP and unknown effects always apply
            }
        }
    }

    /** Charge-gain mirror of {@link #absorbManaPills}: true iff at least one bag
     *  or equipped item would gain charge from {@code pills} manapills. Iteration
     *  and clamp rules must stay in lockstep with absorbManaPills. */
    private static boolean manaPillsWouldAdd(Mob picker, int pills) {
        if (picker == null || picker.inventory == null || pills <= 0) return false;
        int mult = GameBalance.MANA_PER_PILL * pills;
        if (picker.inventory.bag != null) {
            for (Item bagItem : picker.inventory.bag) {
                if (itemWouldGainCharge(bagItem, picker, mult)) return true;
            }
        }
        for (Item eq : picker.inventory.allEquipped()) {
            if (itemWouldGainCharge(eq, picker, mult)) return true;
        }
        return false;
    }

    private static boolean itemWouldGainCharge(Item it, Mob holder, int mult) {
        if (it == null || it.baseChargeMax <= 0 || it.chargeGain * mult <= 0) return false;
        float max = ItemStats.effectiveMaxCharge(it, ItemStats.effectiveLevel(it, holder));
        return it.charge < max;
    }

    /** How many manapills' worth of charge a consumable restores. A plain
     *  chargepill (or any MANA_UP item) defaults to 1; an item with a higher
     *  {@code effectPower} restores that many pills' worth (blue pear = 2),
     *  so the magnitude is data-driven from items.csv. */
    private static int manaPillStrength(Item item) {
        if (item == null) return 1;
        return Math.max(1, (int) Math.round(item.effectPower));
    }

    /**
     * Refill every wand-bearing item in {@code picker}'s bag and equipped slots
     * as if {@code pills} manapills were absorbed: each item gains
     * {@code chargeGain * MANA_PER_PILL * pills} charge, clamped at its
     * effective maximum. Shared by the MANA_UP powerup ({@link #applyPowerup})
     * and charge-restoring foods ({@link #eat}).
     */
    private static boolean absorbManaPills(Mob picker, int pills) {
        if (picker == null || picker.inventory == null || pills <= 0) return false;
        int mult = GameBalance.MANA_PER_PILL * pills;
        boolean added = false;
        if (picker.inventory.bag != null) {
            for (Item bagItem : picker.inventory.bag) {
                if (bagItem == null || bagItem.baseChargeMax <= 0) continue;
                float max = ItemStats.effectiveMaxCharge(bagItem, ItemStats.effectiveLevel(bagItem, picker));
                float before = bagItem.charge;
                bagItem.charge = Math.min(max, bagItem.charge + (bagItem.chargeGain * mult));
                if (bagItem.charge > before) added = true;
            }
        }
        for (Item eq : picker.inventory.allEquipped()) {
            if (eq == null || eq.baseChargeMax <= 0) continue;
            float max = ItemStats.effectiveMaxCharge(eq, ItemStats.effectiveLevel(eq, picker));
            float before = eq.charge;
            eq.charge = Math.min(max, eq.charge + (eq.chargeGain * mult));
            if (eq.charge > before) added = true;
        }
        return added;
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
        if (drinker.isPlayer) {
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
        // A thrown poison potion shatters into a lingering POISON cloud over the splash
        // disc (the cloud re-applies POISONED to anything standing in it each turn) rather
        // than a one-shot splash. Classified by the buff it carries, not its name.
        if (item.appliesBuff != null
                && item.appliesBuff.contains(com.bjsp123.rl2.model.Buff.BuffType.POISONED)) {
            int dur = Math.max(1, ItemStats.effectiveDuration(item));
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (level.tiles[x][y].blocksMovement()) continue;
                    CloudSystem.addCloud(level, x, y,
                            com.bjsp123.rl2.model.Level.Cloud.POISON, dur);
                }
            }
            emitPotionBurst(level, at, item);
            return;
        }
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
                MobCombat.processAttack(level, source, mob, dmg,
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
        // Stacks = the item's effect duration in turns (scaled by item level). The buff's
        // magnitude derives from its current stacks and fades as they count down (RL-43).
        int stacks = ItemStats.effectiveDuration(item, effLvl);
        for (com.bjsp123.rl2.model.Buff.BuffType b : item.appliesBuff) {
            BuffSystem.apply(level, user, b, stacks, user, item);
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
        if (user.isPlayer) {
            EventLog.add(Messages.playerUses(actorName(user),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        }
    }
    /**
     * Apply a wand's element to a target tile. Effect strength scales with
     * the wand's own per-item columns ({@link Item#effectSize},
     * {@link Item#damage}, plus their per-level increments).
     *
     * <p><b>Step 4 of the animation-gated lifecycle:</b> queued by
     * {@link #fireWand} via
     * {@link com.bjsp123.rl2.logic.MobSystem#queuePendingImpact} with the
     * impact tile locked at fire time. The core invariant - a ranged attack
     * must complete and deal damage before the defender gets to move - is
     * enforced by the pending-impact freeze (no game tick runs while the
     * queue is non-empty; headless drains it before the next mob brain),
     * not by synchronous mutation. If this method ever becomes reachable
     * only from the rgame Animator without that freeze holding, that's the
     * historical wand-fizzle regression - see the
     * {@link com.bjsp123.rl2.logic.MobSystem#throwItem} javadoc.
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
                // Per-drop roll for tree vs grass - the tree chance scales
                // linearly with the wand's effective level (5% per level,
                // capped at 100% so a L20 wand from stacked perks doesn't
                // overflow). Grass otherwise. Cascades outward from the
                // target as each drop fills its cell.
                double treeChance = Math.min(1.0, Math.max(0.0, effectiveLevel * 0.05));
                for (int i = 0; i < targetTiles; i++) {
                    Level.Vegetation v = RANDOM.nextDouble() < treeChance
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
                        MobCombat.processAttack(level, caster, m, dmg,
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
                        MobLifecycle.knockBack(level, m, kb, target, 0, kbCause);
                    }
                }
            }
            case BANISHMENT -> {
                Mob victim = MobQueries.mobAt(level, target);
                if (victim != null && victim.banishable) {
                    MobLifecycle.killMob(level, victim, caster);
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
                    MobCombat.processAttack(level, caster, m, dmg,
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
                    dmg = MobCombat.applySurpriseIfNeeded(level, caster, victim, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC);
                    MobCombat.processAttack(level, caster, victim, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC, null,
                            new MobSystem.DamageCause(caster, wand, "missile"));
                    BrandSystem.applyBrandOnHit(level, caster, victim, wand);
                }
            }
            case VOID -> applyVoidImpact(level, target, Math.max(1, (effectiveLevel / 2) + 1));
            case POLYMORPH -> applyPolymorphImpact(level, target, radiusForTileCount(targetTiles));
        }
        // A damage-dealing wand strike is loud - wake nearby sleepers within their wake
        // radius. Terrain / utility wands (water, oil, grass, fungus, etc.) stay silent.
        switch (element) {
            case FIRE, DETONATION, BLAST, LIGHTNING, MISSILE ->
                MobSystem.wakeMobsNear(level, target, "a nearby spell");
            default -> { }
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

    /** Wand-of-void impact. Tears a chasm at the target tile and pulls
     *  every mob within Chebyshev radius {@code (effectiveLevel / 2) + 1}
     *  toward the centre. Floor-like tiles in the disc convert to
     *  CHASM; small statues are obliterated into chasm too (large
     *  statues survive - they're too anchored). Pulled mobs play the
     *  standard knockback-slide animation; non-flying mobs that land on
     *  a fresh chasm tile fall through via
     *  {@link com.bjsp123.rl2.logic.MobSystem#fallToNextLevel}. */
    static void applyVoidImpact(Level level, com.bjsp123.rl2.model.Point target,
                                int requestedRadius) {
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
        int radius = Math.max(1, requestedRadius);
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
                    MobLifecycle.applyChasmFallToTile(level, x, y);
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
            com.bjsp123.rl2.logic.MobLifecycle.fallToNextLevel(level, mob);
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
                    MobLifecycle.applyChasmFallToTile(level, x, y);
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
            if (m.isPlayer) continue;
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
    private static final java.util.Random POLY_RNG =
            com.bjsp123.rl2.util.SimRng.register("ItemSystem.poly", new java.util.Random());

    /** Pick a random non-unique, non-player mob type whose intrinsic
     *  size lies in {@code [oldSize-1, oldSize+1]} and whose type
     *  string differs from {@code excludeType}. Pass
     *  {@link Integer#MIN_VALUE} as {@code oldSize} to drop the size
     *  filter entirely (used as a last-resort fallback when the strict
     *  band has no candidates). Returns {@code null} when no such
     *  species exists in the registry. */
    static String pickPolymorphReplacement(int oldSize, String excludeType) {
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
    static void polymorphMob(Level level, Mob old, String newType) {
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

    /**
     * Wand-of-lightning chain. Lightning hits the mob on {@code target} (if any),
     * then jumps to any other mob within Chebyshev range {@code jumpRadius}
     * that hasn't already been hit, repeating until the chain runs out of
     * eligible neighbours. Each victim takes the wand's rolled SHOCK damage;
     * wetness (the {@link Buff.BuffType#WET} buff or standing on a
     * {@link Level.Surface#WATER} / {@link Level.Surface#ICE} tile) doubles it
     * via the central wet-vulnerability rule in {@code MobSystem.processAttack}.
     *
     * <p>Jump radius is normally {@code 2} tiles, but bumps to {@code 4} when
     * the impact tile carries a {@link Level.Surface#WATER} or
     * {@link Level.Surface#BLOOD} surface - those puddles act as electrical
     * conductors so the arc carries further. The {@code caster} itself is an
     * eligible chain target, so a careless lightning shot in a puddled room
     * can fry the wand-user along with everyone else.
     */
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

    /** Predict the ordered chain of mobs a lightning bolt fired at {@code target}
     *  would strike - the same traversal {@link #applyLightningChain} runs, but
     *  with NO damage applied. The caster is a candidate (backfire), so the
     *  player can appear in the list. Used by the targeting overlay to label
     *  every mob the bolts will arc to. Empty when the target tile has no mob. */
    public static java.util.List<Mob> lightningChainTargets(Level level, Point target) {
        java.util.List<Mob> out = new java.util.ArrayList<>();
        if (level == null || target == null || level.surface == null) return out;
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return out;
        Mob first = MobQueries.mobAt(level, target);
        if (first == null) return out;
        Level.Surface impactSurf = level.surface[tx][ty];
        int jumpRadius = (impactSurf == Level.Surface.WATER
                       || impactSurf == Level.Surface.BLOOD) ? 4 : 2;
        java.util.Set<Mob> hit = new java.util.HashSet<>();
        java.util.ArrayDeque<Mob> frontier = new java.util.ArrayDeque<>();
        frontier.add(first);
        hit.add(first);
        out.add(first);
        while (!frontier.isEmpty()) {
            Mob v = frontier.poll();
            Mob next = nearestChainCandidate(level, v, hit, jumpRadius);
            if (next != null) {
                hit.add(next);
                frontier.add(next);
                out.add(next);
            }
        }
        return out;
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
            // Wet x2 is now applied centrally in MobSystem.processAttack (SHOCK + wet).
            MobCombat.processAttack(level, caster, v, dmg,
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
     * <p>Trajectory is clipped to the first blocker at fire time (locking the
     * impact tile), and the world-state mutation is deferred to
     * {@link #applyWandImpact} via
     * {@link com.bjsp123.rl2.logic.MobSystem#queuePendingImpact} - the
     * ANIMATION-GATED LIFECYCLE, same as
     * {@link com.bjsp123.rl2.logic.MobSystem#throwItem}. The invariant that a
     * ranged attack must complete before the defender can move is enforced by
     * the pending-impact freeze (no game tick runs while the queue is
     * non-empty; headless drains it before the next mob brain), NOT by
     * synchronous mutation - so the on-screen impact lands exactly when the
     * missile / ray visual arrives. See the throwItem javadoc for the full
     * model and the regression this guards against.
     */
    public static void fireWand(Level level, Mob caster, Item wand, Point target) {
        if (level == null || caster == null || wand == null) return;
        // Charge gate - wands refuse to fire when current charge is < 1.
        // The summoning branch shares the same gate so a depleted wand of
        // dog won't yip out a free puppy on use.
        if (wand.useBehavior == Item.UseBehavior.WAND && wand.charge < 1f) {
            if (caster.isPlayer) {
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
        if (caster.isPlayer) caster.runStats.recordWandUse(wand.type);
        if (wand.summonsWhenUsed != null) {
            castSummonWand(level, caster, wand);
            wand.charge = Math.max(0f, wand.charge - 1f);
            if (caster.isPlayer) {
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
            if (caster.isPlayer) {
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
        if (caster.isPlayer) {
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
    public static boolean useItem(Level level, Mob user, Item item) {
        if (level == null || user == null || item == null || item.useBehavior == null) return false;
        boolean acted = true;
        switch (item.useBehavior) {
            case EAT         -> eat(level, user, item);
            case DRINK       -> drinkPotion(level, user, item);
            case GRANT_PERK  -> grantXP(level, user, item);
            case APPLYBUFF   -> acted = useChargedBuffTool(level, user, item);
            case TELEPORT    -> {
                // Teleport orb (used, not thrown): self-teleport to a random tile
                // on this level, avoiding landing within 10 Chebyshev tiles of a
                // hostile unless 100 draws all fail. Consumes the orb on success.
                // Suppressed in the sealed arenas (mirror match, final boss) so
                // the player can't blink out of the fight - orb kept, no turn.
                if (level.suppressTeleport) {
                    if (user.isPlayer) EventLog.add(Messages.teleportSuppressed(item.name != null ? item.name : item.type));
                    return false;
                }
                acted = MobSystem.teleportRandomlyOnLevel(level, user, 10, 100);
                if (acted) MobSystem.removeFromInventory(user, item);
            }
            case WAND, GRAPPLE, JUMP, CHARGE, NONE -> { return false; } // need a target or a specialized caller
        }
        if (acted) TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
        return acted;
    }


    /** Log a "the scroll fizzles" line for a read that found nothing to act on;
     *  the caller returns false so the scroll is NOT consumed. */
    static void gemFizzle(Mob user, Item gem, String why) {
        if (user.isPlayer) {
            EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                    "The " + (gem.name != null ? gem.name : gem.type) + " " + why + ".",
                    com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
        }
    }

    /** Power-level (0..1) for an item "found {@code deeper} levels below" this one. */
    static double gemPowerForDepth(Level level, int deeper) {
        int total = Math.max(2, GameBalance.DUNGEON_DEPTH);
        int itemLevel = (level != null ? level.depth : 1) + deeper;
        double f = (itemLevel - 1) / (double) (total - 1);
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    /** Conjure freshly-created items onto the floor as close to the player as
     *  possible (RL-50 creation scrolls): nearest free floor tile first,
     *  spiralling outward, one item per tile so a multi-item scroll lands a
     *  small visible cluster the player can walk onto. Each item emits an
     *  {@link GameEvent.ItemCreated} burst so the renderer plays a glow + spark
     *  "birth" behind it - dropping them silently into the bag read as "the
     *  scroll did nothing", and far-flung placement (the previous bug) left a
     *  3-item scroll looking like it never fired. Falls back to the player's
     *  own tile when no nearby floor is free. */
    /** Drop {@code it} out of {@code user}'s inventory onto the nearest free floor
     *  tile (the user's own tile only as a last resort). Removes the whole
     *  stack/instance - equipped items are pulled straight out of their slot.
     *  Always succeeds when the item is actually carried; returns {@code false}
     *  only on bad input or if the item isn't in the inventory. */
    public static boolean dropItem(Level level, Mob user, Item it) {
        if (level == null || user == null || it == null
                || user.inventory == null || user.position == null || level.items == null) {
            return false;
        }
        boolean wasEquipped = InventorySystem.isEquipped(user.inventory, it);
        if (!InventorySystem.removeEntirely(user.inventory, it)) return false;
        if (wasEquipped) user.statsDirty = true;
        Point spot = nearestFreeDropTile(level, user.position.tileX(), user.position.tileY());
        if (spot == null) spot = user.position;
        it.location = spot;
        level.items.add(it);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemCreated(it, spot, false));
        }
        String who  = user.name != null ? user.name : "You";
        String what = it.name != null ? it.name : it.type;
        EventLog.add(Messages.itemDropped(who, what));
        TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
        return true;
    }

    static void dropItemsNearPlayer(Level level, Mob user, java.util.List<Item> items) {
        if (user == null || user.position == null) return;
        dropItemsNear(level, user.position.tileX(), user.position.tileY(), items,
                /*filterConjure*/ true, /*showcase*/ true);
    }

    /** Drop {@code items} on the floor as near as possible to the gem hearth the
     *  {@code user} is standing beside (used for forge output + recycled gems, so
     *  they land at the forge rather than wherever the player happens to be).
     *  Falls back to the user's tile if no hearth is found. No conjure filter -
     *  the forge's own output is always dropped. */
    public static void dropItemsNearForge(Level level, Mob user, java.util.List<Item> items) {
        if (level == null || user == null) return;
        Point hearth = nearestGemHearth(level, user);
        int ax = hearth != null ? hearth.tileX()
                : (user.position != null ? user.position.tileX() : 0);
        int ay = hearth != null ? hearth.tileY()
                : (user.position != null ? user.position.tileY() : 0);
        dropItemsNear(level, ax, ay, items, /*filterConjure*/ false, /*showcase*/ false);
    }

    /** Core drop: place each item on the nearest free floor tile to anchor
     *  ({@code ax},{@code ay}), spreading across expanding rings (each placed
     *  item marks its tile occupied so the next lands one ring out). When
     *  {@code filterConjure} is set, jade companions and scatter-on-throw items
     *  are skipped (creation-scroll safety). {@code showcase} flags the ItemCreated
     *  events so the renderer plays the centre-screen glow-showcase (scroll
     *  creations) rather than only the floor birth burst (forge / recycle). */
    private static void dropItemsNear(Level level, int ax, int ay,
                                      java.util.List<Item> items, boolean filterConjure,
                                      boolean showcase) {
        if (level == null || items == null || items.isEmpty() || level.items == null) return;
        for (Item it : items) {
            if (it == null) continue;
            if (filterConjure && (isJadeItem(it) || it.scattersOnThrow())) continue;
            Point spot = nearestFreeDropTile(level, ax, ay);
            if (spot == null) spot = new Point(ax, ay);
            it.location = spot;
            level.items.add(it);
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemCreated(it, spot, showcase));
            }
        }
    }

    /** Tile of the gem hearth ({@link com.bjsp123.rl2.model.Tile#GEM_HEARTH_L})
     *  nearest the {@code user}, or {@code null} if the level has none. The user
     *  forges while standing beside it, so this resolves to that hearth. */
    private static Point nearestGemHearth(Level level, Mob user) {
        if (level == null || level.tiles == null || user == null || user.position == null) {
            return null;
        }
        int px = user.position.tileX(), py = user.position.tileY();
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (level.tiles[x][y] == com.bjsp123.rl2.model.Tile.GEM_HEARTH_L) {
                    int d = Math.max(Math.abs(x - px), Math.abs(y - py));
                    if (d < bestD) { bestD = d; best = new Point(x, y); }
                }
            }
        }
        return best;
    }

    /** Gemforge "recycle": destroy {@code item} and return a handful of gems
     *  scaled by the item's power level (tier + enchant). Higher-power items
     *  yield more gems and a better chance of metal / exotic species. Gems land
     *  in the bag. Returns false on a null/missing item. */
    public static boolean recycleIntoGems(Level level, Mob user, Item item) {
        if (user == null || user.inventory == null || item == null) return false;
        double power = GemSystem.recyclePower(item);
        int count = GemSystem.recycleGemCount(power, RANDOM);
        var theme = level != null ? level.theme : null;
        MobSystem.removeFromInventory(user, item);
        java.util.List<Item> gems = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            com.bjsp123.rl2.model.GemSpecies.GemClass cls = GemSystem.rollRecycleClass(power, RANDOM);
            com.bjsp123.rl2.model.GemSpecies sp = GemSystem.rollSpeciesOfClass(cls, theme, RANDOM);
            if (sp == null) sp = GemSystem.rollSpeciesWeighted(theme, RANDOM);
            if (sp != null) gems.add(GemSystem.createGem(sp));
        }
        // Gems land on the floor beside the hearth, not in the bag.
        dropItemsNearForge(level, user, gems);
        int made = gems.size();
        if (user.isPlayer) {
            String name = item.name != null ? item.name : item.type;
            String msg = made == 0
                    ? "You recycle the " + name + ", but it yields no gems."
                    : "You recycle the " + name + " into " + made
                            + (made == 1 ? " gem, scattered by the hearth."
                                         : " gems, scattered by the hearth.");
            EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                    msg, com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
        }
        return true;
    }

    /** Creation scrolls must never conjure a Jade companion item (RL-50). For
     *  now this is a simple name/type match on "jade" - every jade item carries
     *  it in both. */
    static boolean isJadeItem(Item it) {
        if (it == null) return false;
        if (it.type != null && it.type.toUpperCase(java.util.Locale.ROOT).contains("JADE")) return true;
        return it.name != null && it.name.toLowerCase(java.util.Locale.ROOT).contains("jade");
    }

    /** Spend one charge on a charge-gated tool, unless it has none or the
     *  SUPEREASY difficulty grants jade items free charges. Single source of
     *  truth for tool charge consumption (jade fish/crab/bull, blink, frog, ...). */
    private static void spendChargeIfAny(Item item) {
        if (item == null || item.baseChargeMax <= 0) return;
        if (GameBalance.tuning().jadeItemsFreeCharges() && isJadeItem(item)) return;
        item.charge = Math.max(0f, item.charge - 1f);
    }

    /** Nearest floor-like tile to ({@code px},{@code py}) not already holding an
     *  item, searched in expanding Chebyshev rings (adjacent tiles first, the
     *  player's own tile only as a last resort) out to a small radius. Returns
     *  {@code null} if every nearby tile is occupied or non-floor. */
    private static Point nearestFreeDropTile(Level level, int px, int py) {
        for (int r = 1; r <= 8; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;   // ring edge only
                    int x = px + dx, y = py + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (!level.tiles[x][y].canHoldItem()) continue;
                    if (itemAtTile(level, x, y)) continue;
                    return new Point(x, y);
                }
            }
        }
        // Last resort: the player's own tile if it's clear. (canHoldItem, not
        // isFloorLike: never anchor onto the hearth/lamp/stairs - those block
        // movement or can't hold loot, leaving the drop unreachable.)
        if (level.tiles[px][py].canHoldItem() && !itemAtTile(level, px, py)) {
            return new Point(px, py);
        }
        return null;
    }

    /** True if any item already rests on tile ({@code x},{@code y}). */
    private static boolean itemAtTile(Level level, int x, int y) {
        if (level.items == null) return false;
        for (Item it : level.items) {
            if (it == null || it.location == null) continue;
            if (it.location.tileX() == x && it.location.tileY() == y) return true;
        }
        return false;
    }

    /** Hostile mobs (per {@link #enemiesOnLevel}) that are currently in the
     *  player's field of view. */
    static java.util.List<Mob> visibleEnemies(Level level, Mob user) {
        java.util.List<Mob> out = new java.util.ArrayList<>();
        for (Mob m : enemiesOnLevel(level, user)) {
            if (m.position != null && MobSystem.tileVisibleToPlayer(level, m.position)) out.add(m);
        }
        return out;
    }

    /** True if a living mob already stands on tile (x, y). */
    static boolean occupied(Level level, int x, int y) {
        if (level.mobs == null) return false;
        for (Mob m : level.mobs) {
            if (m == null || m.position == null || m.hp <= 0) continue;
            if (m.position.tileX() == x && m.position.tileY() == y) return true;
        }
        return false;
    }

    /** Replace every closed/open wooden door on the level with {@code into}. */
    static int convertDoors(Level level, com.bjsp123.rl2.model.Tile into) {
        int n = 0;
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                com.bjsp123.rl2.model.Tile t = level.tiles[x][y];
                if (t == com.bjsp123.rl2.model.Tile.DOOR || t == com.bjsp123.rl2.model.Tile.DOOR_OPEN) {
                    level.tiles[x][y] = into;
                    n++;
                }
            }
        }
        return n;
    }

    /** Recharge every charge-bearing item the player carries or wears to full. */
    static void rechargeAllItems(Mob user) {
        if (user == null || user.inventory == null) return;
        if (user.inventory.bag != null) {
            for (Item it : user.inventory.bag) {
                if (it == null || it.baseChargeMax <= 0) continue;
                it.charge = ItemStats.effectiveMaxCharge(it, ItemStats.effectiveLevel(it, user));
            }
        }
        for (Item it : user.inventory.allEquipped()) {
            if (it == null || it.baseChargeMax <= 0) continue;
            it.charge = ItemStats.effectiveMaxCharge(it, ItemStats.effectiveLevel(it, user));
        }
    }

    /** Statue / lamp scenery tiles, used by the Scroll of Widespread Immolation. */
    static boolean isStatueOrLamp(com.bjsp123.rl2.model.Tile t) {
        return t == com.bjsp123.rl2.model.Tile.STATUE_SMALL_L
                || t == com.bjsp123.rl2.model.Tile.STATUE_SMALL_R
                || t == com.bjsp123.rl2.model.Tile.STATUE_LARGE_L
                || t == com.bjsp123.rl2.model.Tile.STATUE_LARGE_R
                || t == com.bjsp123.rl2.model.Tile.LAMP;
    }

    /**
     * For a scroll that enchants an existing inventory item, the predicate of
     * which items are valid targets - the controller opens the inventory in
     * picker mode with eligible items lit and the rest greyed out. Returns
     * {@code null} for scrolls that don't target an inventory item (those fire
     * directly through {@link #triggerGem}).
     */
    public static java.util.function.Predicate<Item> gemItemTarget(Item gem) {
        if (gem == null || gem.type == null) return null;
        return switch (gem.type) {
            case "SC_BRAND_WEAPON" -> BrandSystem::isBrandable;
            case "SC_UPGRADE_WEAPON"   -> i -> i != null && (i.isEquippable() || isJadeItem(i));
            default -> null;
        };
    }


    /** Hostile, untamed, living mobs on the level (excludes {@code user},
     *  the player, allies, and inanimate scenery). Used by level-wide gem
     *  effects. */
    static java.util.List<Mob> enemiesOnLevel(Level level, Mob user) {
        java.util.List<Mob> out = new java.util.ArrayList<>();
        if (level == null || level.mobs == null) return out;
        for (Mob m : level.mobs) {
            if (m == null || m == user || m.hp <= 0) continue;
            if (m.owner != null) continue;
            if (m.isPlayer
                    || m.behavior == Behavior.INANIMATE) continue;
            out.add(m);
        }
        return out;
    }

    /** Standard "player invokes the gem" log line. */
    static void announceGemUse(Mob user, Item gem) {
        if (user.isPlayer) {
            EventLog.add(Messages.playerUses(actorName(user),
                    useVerb(gem, "eventlog.item.verb.use"), gem.name));
        }
    }

    private static boolean useChargedBuffTool(Level level, Mob user, Item item) {
        if (item.baseChargeMax > 0 && item.charge < 1f) {
            if (user.isPlayer) {
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
        if (user.isPlayer) user.runStats.recordToolUse(item.type);
        spendChargeIfAny(item);
        if (level != null && level.events != null && user.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.PotionBurst(user.position, item));
        }
        if (user.isPlayer) {
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
            if (caster.isPlayer) {
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
                if (caster.isPlayer) {
                    EventLog.add(Messages.grappleBlocked(
                            targetMob.name != null ? targetMob.name
                                    : TextCatalog.get("eventlog.fallback.creature")));
                }
                spendChargeIfAny(item);
                TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
                return;
            }
        }
        if (!MobVisibility.projectileLineReaches(level, caster.position, target, caster)) {
            if (level.events != null)
                level.events.add(new com.bjsp123.rl2.event.GameEvent.GrappleFired(
                        caster, caster.position, target, false));
            spendChargeIfAny(item);
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
            if (caster.isPlayer) {
                EventLog.add(Messages.playerUses(actorName(caster),
                        useVerb(item, "eventlog.item.verb.use"), item.name));
            } else if (caster.name != null && item.name != null) {
                EventLog.add(Messages.mobUsesItem(caster.name, item.name, false));
            }
            spendChargeIfAny(item);
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
        if (caster.isPlayer) {
            EventLog.add(Messages.playerUses(actorName(caster),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        } else if (caster.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(caster.name, item.name, false));
        }
        spendChargeIfAny(item);
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
            if (user.isPlayer) {
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
        int cheb = Math.max(dx, dy);
        if (cheb > dashRange) return;
        // Charge needs a runway - refuse to fire against adjacent enemies.
        // The targeting overlay (chargeGrid) also gates this, but enforcing
        // it here keeps the AI path and any future caller honest.
        if (cheb < 2) return;
        // A charge is a ground dash, not a blink: an impassable square (wall,
        // closed door, statue, beacon, ...) between the charger and the target
        // stops it cold. Fizzles without spending the charge or the turn.
        if (!MobSystem.chargePathClear(level, user.position, target)) {
            if (user.isPlayer) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.get("eventlog.charge.blocked"),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return;
        }

        Point arrival = pickChargeArrival(level, user.position, target);
        if (arrival == null) {
            // No solid floor beside the target to land on - the charge fizzles without
            // spending the charge or the turn (never strand the charger on a chasm).
            if (user.isPlayer) {
                EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                        TextCatalog.get("eventlog.charge.noRoom"),
                        com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            }
            return;
        }

        // Narrative lead-in - replaces the generic "uses the jade bull"
        // line. Emitted BEFORE the swing so the log reads "charges at X"
        // then "hits X for N". HIGH priority since it flags a deliberate
        // commitment of a turn.
        if (user.isPlayer) {
            String targetName = victim.name != null
                    ? victim.name
                    : (victim.mobType != null ? victim.mobType.toLowerCase() : "?");
            EventLog.add(Messages.playerCharges(actorName(user),
                    targetName, victim.isPlayer));
        }

        // Dash visual + position update.
        Point from = user.position;
        user.position = arrival;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobJumped(user, from, arrival, item));
        }

        // Free melee swing - mirrors {@code MobSystem.attack}'s damage roll
        // but bypasses the to-hit roll (the dash strike auto-connects) and
        // skips the standard attack-time knockback so we can apply the
        // jade-bull's own knockback below.
        int rawAtk = MobSystem.rollRange(MobCombat.rawDamageRange(user));
        int armor  = MobSystem.rollRange(MobCombat.resistRange(victim));
        int physical = Math.max(0, rawAtk - armor);
        int ap = MobSystem.rollRange(MobCombat.apDamageRange(user));
        int total = physical + ap;
        MobCombat.processAttack(level, user, victim, total,
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
                MobLifecycle.knockBack(level, victim, kb, arrival, wallSlam,
                        new MobSystem.DamageCause(user, item, "wall-slam"));
            }
        }

        spendChargeIfAny(item);
        // No trailing "uses the jade bull" log - the leading playerCharges
        // line above already framed the action.
        TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
    }

    /** Pick the dash arrival tile: an 8-neighbor of {@code target} that is solid
     *  floor, unoccupied, and as close as possible to {@code userPos} (so the dash
     *  lands on the natural side of the victim). Returns null when no floor neighbour
     *  is free - the charge must land the charger on solid ground, never into a chasm
     *  (the tile is floor-like AND non-blocking, so lamps and chasms are both excluded). */
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
                com.bjsp123.rl2.model.Tile at = level.tiles[nx][ny];
                if (!at.isFloorLike() || at.blocksMovement()) continue;
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
            if (jumper.isPlayer) {
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
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobJumped(jumper, from, target, item));
        }
        if (jumper.isPlayer) {
            EventLog.add(Messages.playerUses(actorName(jumper),
                    useVerb(item, "eventlog.item.verb.use"), item.name));
        } else if (jumper.name != null && item.name != null) {
            EventLog.add(Messages.mobUsesItem(jumper.name, item.name, false));
        }
        spendChargeIfAny(item);
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
            MobLifecycle.fallToNextLevel(level, mob);
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

    /**
     * Generic summon-wand path. If the wand-of-X has a non-null
     * {@link Item#summonsWhenUsed}, this spawns one of that mob type on a free
     * floor tile adjacent to {@code caster}, sets ownership, and scales the
     * summon to the wand's level. Returns {@code true} if a summon happened -
     * callers always pay the move cost regardless, so the wand burns a turn
     * even when the spawn is gated out by population caps or no-room.
     */
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

    /**
     * Tiles a thrown {@code item} will affect when it lands on {@code impact}.
     * Mirrors the per-category branches in {@link MobThrowing#applyThrowImpact}
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
