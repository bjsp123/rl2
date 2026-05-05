package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
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
        int lvl = Math.max(0, item.level);
        if (item.damageMax > 0) {
            int min = item.damageMin + lvl * GameBalance.WEAPON_INCREMENT_MIN;
            int max = item.damageMax + lvl * GameBalance.WEAPON_INCREMENT_MAX;
            dst.damage = dst.damage.plus(new MinMax(Math.max(0, min), Math.max(0, max)));
        }
        if (item.armorMax > 0) {
            int min = item.armorMin + lvl * GameBalance.ARMOR_INCREMENT_MIN;
            int max = item.armorMax + lvl * GameBalance.ARMOR_INCREMENT_MAX;
            dst.armor = dst.armor.plus(new MinMax(Math.max(0, min), Math.max(0, max)));
        }
        if (item.lightRadius > dst.lightRadius) dst.lightRadius = item.lightRadius;
        // Future per-species gem effects, AP/magic-resist on rings/amulets, accuracy
        // bonuses, etc. all plug in here — they touch dst directly with the same shape.
    }

    // ── Damage / armour / AP / magic-resist contributions ────────────────────

    // ── Use-time effects ─────────────────────────────────────────────────────

    /** Effective melee damage range of a thrown weapon — the contribution it would make
     *  to a wielder's melee, applied as the standalone roll for thrown impact. Used by
     *  {@code MobSystem.throwItem}. Items without a damage range return {@link MinMax#ZERO}. */
    public static MinMax effectiveDamageRange(Item item) {
        if (item == null || item.damageMax <= 0) return MinMax.ZERO;
        int lvl = Math.max(0, item.level);
        int min = item.damageMin + lvl * GameBalance.WEAPON_INCREMENT_MIN;
        int max = item.damageMax + lvl * GameBalance.WEAPON_INCREMENT_MAX;
        return new MinMax(Math.max(0, min), Math.max(0, max));
    }

    /** Effective HP restored when this item is drunk / used as a heal. Returns 0 for
     *  items that don't heal so the call is unconditional from drink-paths. */
    public static int effectiveHealAmount(Item item) {
        if (item == null || item.healAmount <= 0) return 0;
        int lvl = Math.max(0, item.level);
        return item.healAmount + lvl * GameBalance.HEAL_VALUE_INCREMENT;
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
        if (item == null) return 1;
        return 1 + Math.max(0, item.level);
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

    /** Effective self-damage on use (poison potion). Returns 0 for items that
     *  don't carry a self-damage base. */
    public static int effectiveSelfDamage(Item item) {
        if (item == null || item.selfDamageBase <= 0) return 0;
        return item.selfDamageBase + Math.max(0, item.level);
    }

    /** Effective wand impact damage range. Used by the wand of magic missile and any
     *  future damage-on-impact wand. */
    public static MinMax effectiveWandDamageRange(Item item) {
        return effectiveWandDamageRange(item == null ? 0 : item.level);
    }

    /** Int-level overload — used by impact callbacks (wand of lightning) that
     *  carry the wand level forward without a reference to the source item. */
    public static MinMax effectiveWandDamageRange(int wandLevel) {
        int lvl = Math.max(0, wandLevel);
        int min = GameBalance.BASIC_WAND_DAMAGE_MIN + lvl * GameBalance.WAND_DAMAGE_INCREMENT_MIN;
        int max = GameBalance.BASIC_WAND_DAMAGE_MAX + lvl * GameBalance.WAND_DAMAGE_INCREMENT_MAX;
        return new MinMax(min, Math.max(min, max));
    }

    /** Effective tile count for a wand area effect. Converted to a Euclidean disc
     *  radius via {@link MobSystem#radiusForTileCount} at the use-site. */
    public static int effectiveWandEffectTiles(Item item) {
        return effectiveWandEffectTiles(item == null ? 0 : item.level);
    }

    /** Int-level overload for the wand-effect-tiles helper. Used by the missile
     *  pipeline, which carries the wand's level forward as an int on the in-flight
     *  Effect and no longer has a reference to the source Item by impact time. */
    public static int effectiveWandEffectTiles(int wandLevel) {
        int lvl = Math.max(0, wandLevel);
        return GameBalance.WAND_EFFECT_TILES + lvl * GameBalance.WAND_EFFECT_TILE_INCREMENT;
    }

    /** Effective bomb impact damage. */
    public static int effectiveBombDamage(Item item) {
        int lvl = item == null ? 0 : Math.max(0, item.level);
        return GameBalance.BOMB_DAMAGE_BASE + lvl * GameBalance.BOMB_DAMAGE_INCREMENT;
    }

    /** Effective bomb area-of-effect tile count. */
    public static int effectiveBombEffectTiles(Item item) {
        int lvl = item == null ? 0 : Math.max(0, item.level);
        return GameBalance.BOMB_EFFECT_TILES + lvl * GameBalance.BOMB_EFFECT_TILE_INCREMENT;
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

    /** Backwards-compat shim — older callers without a Level handle. */
    public static void eat(Mob eater, Item item) { eat(null, eater, item); }

    /**
     * Drink a potion. Applies the item's {@link Item#appliesBuff} (level and
     * duration from the item-level helpers) and any {@link Item#selfDamageBase}
     * self-damage (poison potion). Item is consumed.
     */
    public static void drinkPotion(Level level, Mob drinker, Item item) {
        if (drinker == null || item == null) return;
        applyConsumableBuff(level, drinker, item);
        int selfDmg = effectiveSelfDamage(item);
        if (selfDmg > 0) {
            MobSystem.processAttack(level, null, drinker, selfDmg,
                    MobSystem.AttackType.ENVIRONMENTAL);
        }
        MobSystem.removeFromInventory(drinker, item);
        if (drinker.behavior == Behavior.PLAYER) {
            String name = drinker.name != null ? drinker.name : "Adventurer";
            EventLog.add(Messages.playerUses(name,
                    item.useVerb != null ? item.useVerb : "drink", item.name));
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
        if (user.behavior == Behavior.PLAYER) {
            String name = user.name != null ? user.name : "Adventurer";
            EventLog.add(Messages.playerUses(name,
                    item.useVerb != null ? item.useVerb : "use", item.name));
        }
    }

    /**
     * Apply a wand's element to a target tile. Effect strength scales with
     * {@code wandLevel} via {@link #effectiveWandEffectTiles}. Called from rgame's
     * {@code Animator} as the {@code PendingImpact} callback once the wand-missile
     * or ray visual completes.
     */
    public static void applyWandImpact(Level level, Mob caster, Point target,
                                       Item.WandElement element, int wandLevel) {
        if (level == null || target == null || element == null) return;
        int tx = target.tileX(), ty = target.tileY();
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return;
        int targetTiles = effectiveWandEffectTiles(wandLevel);
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
            }
            case BANISHMENT -> {
                Mob victim = MobSystem.mobAt(level, target);
                if (victim != null && victim.banishable) {
                    MobSystem.killMob(level, victim, caster);
                }
            }
            case LIGHTNING -> {
                Mob victim = MobSystem.mobAt(level, target);
                if (victim != null) {
                    int dmg = MobSystem.rollRange(effectiveWandDamageRange(wandLevel));
                    Level.Surface here = level.surface[tx][ty];
                    boolean conductive = BuffSystem.hasBuff(victim, Buff.BuffType.WET)
                            || here == Level.Surface.WATER
                            || here == Level.Surface.ICE;
                    if (conductive) dmg *= 2;
                    MobSystem.processAttack(level, caster, victim, dmg,
                            MobSystem.AttackType.MAGIC);
                }
            }
        }
        if (element != Item.WandElement.DETONATION
                && element != Item.WandElement.BANISHMENT
                && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(target, element));
        }
    }

    /**
     * Generic summon-wand path. If the wand-of-X has a non-null
     * {@link Item#summonsWhenUsed}, this spawns one of that mob type on a free
     * floor tile adjacent to {@code caster}, sets ownership, and scales the
     * summon to the wand's level. Returns {@code true} if a summon happened —
     * callers always pay the move cost regardless, so the wand burns a turn
     * even when the spawn is gated out by population caps or no-room.
     */
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
