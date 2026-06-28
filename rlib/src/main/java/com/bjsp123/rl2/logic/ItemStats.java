package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.StatBlock;

/**
 * Per-item stat calculations.
 *
 * <h3>Scaling rules</h3>
 * <ul>
 *   <li><b>amount stats</b> (damage, armor, apDamage, magicResist, accuracy,
 *       evasion, knockbackSquares, lightRadius, effectDuration,
 *       effectRange):
 *       {@code scaled = base + N × max(1, base/AMOUNT_LEVEL_SCALE_FACTOR)}.
 *       Every {@code +1} adds at least 1, larger bases grow proportionally
 *       faster. Default factor 3.</li>
 *   <li><b>tile-count stats</b> (effectSize):
 *       {@code scaled = base + N × max(1, base/TILECOUNT_LEVEL_SCALE_FACTOR)}.
 *       Default factor 3.</li>
 *   <li><b>charge-count stats</b> (baseChargeMax):
 *       {@code scaled = base + N × max(1, base/CHARGEMAX_LEVEL_SCALE_FACTOR)}.
 *       Default factor 2.</li>
 *   <li><b>speed multipliers</b> (attackSpeed, moveSpeed): do not scale with
 *       level. The CSV value is the live value.</li>
 *   <li><b>flat</b>: clusterSize, minPowerLevel, maxPowerLevel, chargeGain.</li>
 * </ul>
 *
 * <p>For amount stats stored as a single integer {@code N}, the live
 * damage / armor / etc. <em>range</em> is {@code [scaled/2, scaled]}.
 *
 * <h3>API shape</h3>
 * Only two methods take a {@link Mob} holder:
 * <ul>
 *   <li>{@link #effectiveLevel(Item, Mob)} — single source of truth for the
 *       item's effective level under this holder (item.level + perk / SORCERY
 *       / sorcery-brand bonuses).</li>
 *   <li>{@link #contributeInto(StatBlock, Item, Mob)} — holder-aware roll-up
 *       onto a wearer's stat block.</li>
 * </ul>
 * Every other {@code effective*} method takes a plain {@code int level}; the
 * {@code (Item)} convenience overload uses {@code item.level}.
 */
public final class ItemStats {
    private ItemStats() {}

    static int clampedLevel(Item item) {
        return item == null ? 0 : Math.max(0, item.level);
    }

    // -- Scaling primitives -------------------------------------------------

    /** Universal "amount" scaling: {@code base + N × max(1, base/factor)}.
     *  Increment is at least 1 per level so every {@code +1} bumps the stat
     *  visibly regardless of how small the base is. */
    public static int scaleAmount(int base, int level, int factor) {
        if (base <= 0) return 0;
        int inc = Math.max(1, base / Math.max(1, factor));
        return base + Math.max(0, level) * inc;
    }

    /** Convenience: scale using {@link GameBalance#AMOUNT_LEVEL_SCALE_FACTOR}. */
    public static int scaleAmount(int base, int level) {
        return scaleAmount(base, level, GameBalance.AMOUNT_LEVEL_SCALE_FACTOR);
    }

    /** Per-level increment for an amount stat with the given base and factor.
     *  Mirrors the {@code inc} term in {@link #scaleAmount}: every {@code +1}
     *  of item level adds this much to the scaled value. Returns 0 when the
     *  base stat is unset so callers can suppress the per-level suffix on
     *  stats the item doesn't contribute. */
    public static int scaleIncrement(int base, int factor) {
        if (base <= 0) return 0;
        return Math.max(1, base / Math.max(1, factor));
    }

    /** Convenience: scaleIncrement using {@link GameBalance#AMOUNT_LEVEL_SCALE_FACTOR}. */
    public static int scaleIncrement(int base) {
        return scaleIncrement(base, GameBalance.AMOUNT_LEVEL_SCALE_FACTOR);
    }

    /** Derive the [min, max] damage / armor / etc. range from a scaled int N.
     *  Range is {@code [N/2, N]}; floored at 0. */
    private static MinMax rangeFrom(int scaled) {
        if (scaled <= 0) return MinMax.ZERO;
        return new MinMax(Math.max(0, scaled / 2), scaled);
    }

    // -- effectiveLevel: the single source of truth -------------------------

    public static int effectiveLevel(Item item) {
        return clampedLevel(item);
    }

    public static int effectiveLevel(Item item, Mob holder) {
        if (item == null) return 0;
        int lvl = clampedLevel(item);
        if (holder == null) return lvl;
        if (item.useBehavior == Item.UseBehavior.POWERUP) return 0;
        if (holder.perks != null) {
            if (item.inventoryCategory == Item.InventoryCategory.WAND) {
                lvl += holder.perks.getOrDefault(Perk.WANDMASTER, 0);
            }
            if (item.inventoryCategory == Item.InventoryCategory.BOMB) {
                lvl += holder.perks.getOrDefault(Perk.BOMB_JACK, 0);
            }
            if (item.useBehavior == Item.UseBehavior.JUMP) {
                lvl += holder.perks.getOrDefault(Perk.JUMP, 0);
            }
        }
        if (item.inventoryCategory == Item.InventoryCategory.WAND
                || item.inventoryCategory == Item.InventoryCategory.POTION) {
            lvl += BuffSystem.sorceryBonus(holder);
            if (holder.inventory != null) {
                if (hasSorceryBrand(holder.inventory.weapon))  lvl++;
                if (hasSorceryBrand(holder.inventory.offhand)) lvl++;
                if (hasSorceryBrand(holder.inventory.armor))   lvl++;
                for (Item eq : holder.inventory.amulets) if (hasSorceryBrand(eq)) lvl++;
                for (Item eq : holder.inventory.gems)    if (hasSorceryBrand(eq)) lvl++;
            }
        }
        return lvl;
    }

    private static boolean hasSorceryBrand(Item item) {
        return item != null && item.brand != null && item.brand.sorcery;
    }

    // -- amount stats -------------------------------------------------------

    public static MinMax effectiveDamageRange(Item item) {
        return effectiveDamageRange(item, clampedLevel(item));
    }
    public static MinMax effectiveDamageRange(Item item, int level) {
        if (item == null) return MinMax.ZERO;
        int factor = isBombOrWand(item)
                ? GameBalance.BOMB_WAND_DAMAGE_FACTOR
                : GameBalance.AMOUNT_LEVEL_SCALE_FACTOR;
        return rangeFrom(scaleAmount(item.damage, level, factor));
    }

    /** True for BOMB and WAND inventory categories - these scale damage via
     *  the dedicated {@link GameBalance#BOMB_WAND_DAMAGE_FACTOR} rather than
     *  the universal {@link GameBalance#AMOUNT_LEVEL_SCALE_FACTOR}. */
    private static boolean isBombOrWand(Item item) {
        if (item == null || item.inventoryCategory == null) return false;
        return item.inventoryCategory == Item.InventoryCategory.BOMB
                || item.inventoryCategory == Item.InventoryCategory.WAND;
    }

    public static MinMax effectiveArmorRange(Item item) {
        return effectiveArmorRange(item, clampedLevel(item));
    }
    public static MinMax effectiveArmorRange(Item item, int level) {
        if (item == null) return MinMax.ZERO;
        return rangeFrom(scaleAmount(item.armor, level));
    }

    public static MinMax effectiveApDamageRange(Item item) {
        return effectiveApDamageRange(item, clampedLevel(item));
    }
    public static MinMax effectiveApDamageRange(Item item, int level) {
        if (item == null) return MinMax.ZERO;
        return rangeFrom(scaleAmount(item.apDamage, level));
    }

    public static MinMax effectiveMagicResistRange(Item item) {
        return effectiveMagicResistRange(item, clampedLevel(item));
    }
    public static MinMax effectiveMagicResistRange(Item item, int level) {
        if (item == null) return MinMax.ZERO;
        return rangeFrom(scaleAmount(item.magicResist, level));
    }

    public static int effectiveAccuracy(Item item) {
        return effectiveAccuracy(item, clampedLevel(item));
    }
    public static int effectiveAccuracy(Item item, int level) {
        if (item == null) return 0;
        return scaleAmount(item.accuracy, level);
    }

    public static int effectiveEvasion(Item item) {
        return effectiveEvasion(item, clampedLevel(item));
    }
    public static int effectiveEvasion(Item item, int level) {
        if (item == null) return 0;
        return scaleAmount(item.evasion, level);
    }

    public static int effectiveKnockback(Item item) {
        return effectiveKnockback(item, clampedLevel(item));
    }
    public static int effectiveKnockback(Item item, int level) {
        if (item == null) return 0;
        // Knockback is intrinsic to the weapon and does NOT scale with level.
        return item.knockbackSquares;
    }

    public static double effectiveLightRadius(Item item) {
        return effectiveLightRadius(item, clampedLevel(item));
    }
    public static double effectiveLightRadius(Item item, int level) {
        if (item == null || item.lightRadius <= 0) return 0;
        // Light radius is a float in CSV; treat the integer part as the base.
        int base = Math.max(1, (int) item.lightRadius);
        return scaleAmount(base, level);
    }

    /** Scaled buff / cloud duration in <b>standard turns</b>. Amount rule.
     *  Zero for items with no duration component. */
    public static int effectiveDuration(Item item) {
        return effectiveDuration(item, clampedLevel(item));
    }
    public static int effectiveDuration(Item item, int level) {
        if (item == null || item.effectDuration <= 0) return 0;
        return scaleAmount(item.effectDuration, level);
    }

    /** Scaled tile-movement range for JUMP / CHARGE tools. Amount rule. */
    public static int effectiveRange(Item item) {
        return effectiveRange(item, clampedLevel(item));
    }
    public static int effectiveRange(Item item, int level) {
        if (item == null || item.effectRange <= 0) return 0;
        return scaleAmount(item.effectRange, level);
    }

    /** Flat magnitude knob (GRAPPLE max-size cap, POWERUP heal / XP
     *  fractions). Does NOT scale with item level. */
    public static float effectivePower(Item item) {
        return item == null ? 0f : item.effectPower;
    }
    public static float effectivePower(Item item, int level) {
        return effectivePower(item);
    }

    // -- tile-count stats ---------------------------------------------------

    /** Effect-size in literal tile count for area-of-effect items (bombs,
     *  AoE wands, cascade wands). Scales by the TILECOUNT factor — every
     *  level adds {@code max(1, base/factor)} tiles. */
    public static int effectiveSize(Item item) {
        return effectiveSize(item, clampedLevel(item));
    }
    public static int effectiveSize(Item item, int level) {
        if (item == null || item.effectSize <= 0) return 0;
        int factor = isCascadeWand(item)
                ? GameBalance.TILECOUNT_CASCADE_FACTOR
                : GameBalance.TILECOUNT_LEVEL_SCALE_FACTOR;
        return scaleAmount(item.effectSize, level, factor);
    }

    /** True for surface / vegetation cascade wands whose effectSize is the
     *  drop count for {@code SurfaceSystem.addSurface} / {@code
     *  VegetationSystem.addVegetation}. Cascade wands scale tile count
     *  more sharply than bomb / disc AoE — see
     *  {@link GameBalance#TILECOUNT_CASCADE_FACTOR}. */
    private static boolean isCascadeWand(Item item) {
        if (item.useBehavior != Item.UseBehavior.WAND) return false;
        if (item.wandEffect == null) return false;
        return switch (item.wandEffect) {
            case WATER, OIL, GRASS, FUNGUS -> true;
            default -> false;
        };
    }

    // -- charge-count stats -------------------------------------------------

    public static int effectiveMaxCharge(Item item) {
        return effectiveMaxCharge(item, clampedLevel(item));
    }
    public static int effectiveMaxCharge(Item item, int level) {
        if (item == null || item.baseChargeMax <= 0) return 1;
        int lvl = Math.max(0, level);
        int cap = GameBalance.MAX_ITEM_CHARGES;   // no item exceeds this many charges
        if (item.useBehavior == Item.UseBehavior.WAND) {
            // Wand maxCharge: sqrt-based growth. base=3 → +6 at L5, +9 at L10.
            return Math.max(1, Math.min(cap, item.baseChargeMax
                    + (int) Math.floor(item.baseChargeMax * Math.sqrt(lvl))));
        }
        // Item / tool maxCharge: piecewise linear, knee at L5.
        // base=2: L5 → +4, L10 → +6. base=3: L5 → +5, L10 → +7.
        int lvlBefore5 = Math.min(lvl, 5);
        int lvlAfter5  = Math.max(0, lvl - 5);
        int inc = (item.baseChargeMax + 2) * lvlBefore5 / 5 + 2 * lvlAfter5 / 5;
        return Math.max(1, Math.min(cap, item.baseChargeMax + inc));
    }

    // -- speed stats (flat — do not scale) ----------------------------------

    public static double effectiveAttackSpeed(Item item) {
        return item == null ? Item.ATTACK_SPEED_DEFAULT : item.attackSpeed;
    }
    public static double effectiveAttackSpeed(Item item, int level) {
        return effectiveAttackSpeed(item);
    }

    public static double effectiveMoveSpeed(Item item) {
        return item == null ? Item.MOVE_SPEED_DEFAULT : item.moveSpeed;
    }
    public static double effectiveMoveSpeed(Item item, int level) {
        return effectiveMoveSpeed(item);
    }

    // -- buff application --------------------------------------------------

    /** Buff duration applied by this item, in <b>game ticks</b>. Derived from
     *  {@link Item#effectDuration} (standard turns) under the amount rule. */
    public static int effectiveBuffDuration(Item item) {
        return effectiveBuffDuration(item, clampedLevel(item));
    }
    public static int effectiveBuffDuration(Item item, int level) {
        if (item == null) return 1;
        int turns = effectiveDuration(item, level);
        if (turns <= 0) turns = 1;
        return Math.max(1, turns * TurnSystem.STANDARD_TURN_TICKS);
    }

    // -- contributeInto: the holder-aware roll-up ---------------------------

    public static void contributeInto(StatBlock dst, Item item, Mob holder) {
        if (item == null || dst == null) return;
        int effLvl = effectiveLevel(item, holder);
        if (item.damage > 0)      dst.damage      = dst.damage.plus(effectiveDamageRange(item, effLvl));
        if (item.armor  > 0)      dst.armor       = dst.armor.plus(effectiveArmorRange(item, effLvl));
        if (item.apDamage > 0)    dst.apDamage    = dst.apDamage.plus(effectiveApDamageRange(item, effLvl));
        if (item.magicResist > 0) dst.magicResist = dst.magicResist.plus(effectiveMagicResistRange(item, effLvl));
        dst.accuracy += effectiveAccuracy(item, effLvl);
        dst.evasion  += effectiveEvasion(item, effLvl);
        // Speed multipliers are flat per the new model.
        if (item.attackSpeed != Item.ATTACK_SPEED_DEFAULT) dst.attackCost *= item.attackSpeed;
        if (item.moveSpeed   != Item.MOVE_SPEED_DEFAULT)   dst.moveCost   *= item.moveSpeed;
        double lr = effectiveLightRadius(item, effLvl);
        if (lr > dst.lightRadius) dst.lightRadius = lr;
        dst.knockbackSquares += effectiveKnockback(item, effLvl);
        dst.xRayEyes += item.xRayEyes;   // flat - x-ray levels don't scale with item level

        if (item.brand != null) {
            BrandDefinition b = item.brand;
            // Brand magnitudes scale with the item's effective level via the
            // same AMOUNT rule as the item's own stats. Speed multipliers,
            // sorcery, and resistance flags stay flat (no magnitude).
            int bDmg = scaleAmount(b.damage,    effLvl);
            int bArm = scaleAmount(b.armor,     effLvl);
            int bAM  = scaleAmount(b.antimagic, effLvl);
            int bAcc = scaleAmount(b.accuracy,  effLvl);
            int bEva = scaleAmount(b.evasion,   effLvl);
            int bKb  = scaleAmount(b.knockback, effLvl,
                    GameBalance.KNOCKBACK_LEVEL_SCALE_FACTOR);
            if (bDmg > 0) dst.damage      = dst.damage.plus(new MinMax(bDmg, bDmg));
            if (bArm > 0) dst.armor       = dst.armor.plus(new MinMax(bArm, bArm));
            if (bAM  > 0) dst.magicResist = dst.magicResist.plus(new MinMax(bAM, bAM));
            dst.accuracy += bAcc;
            dst.evasion  += bEva;
            if (b.attackSpeed != 1.0) dst.attackCost = (int) (dst.attackCost * b.attackSpeed);
            if (b.moveSpeed   != 1.0) dst.moveCost   = (int) (dst.moveCost   * b.moveSpeed);
            dst.knockbackSquares += bKb;
        }
    }

    public static void contributeInto(StatBlock dst, Item item) {
        contributeInto(dst, item, null);
    }

    // -- per-level increments ----------------------------------------------
    //
    // Each helper returns the per-item-level increment for a single stat,
    // computed from the item's base value and the correct GameBalance factor
    // for that stat. Callers (UI lore, balance tools) read these to render
    // "+N/lvl" hints without re-deriving the formula. Returns 0 when the
    // base stat is zero (so the lore can skip the suffix).

    public static int damagePerLevel(Item item) {
        if (item == null || item.damage <= 0) return 0;
        int factor = isBombOrWand(item)
                ? GameBalance.BOMB_WAND_DAMAGE_FACTOR
                : GameBalance.AMOUNT_LEVEL_SCALE_FACTOR;
        return scaleIncrement(item.damage, factor);
    }

    public static int armorPerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.armor);
    }

    public static int apDamagePerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.apDamage);
    }

    public static int magicResistPerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.magicResist);
    }

    public static int accuracyPerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.accuracy);
    }

    public static int evasionPerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.evasion);
    }


    public static int knockbackPerLevel(Item item) {
        // Knockback no longer scales with level - the per-level delta is always 0.
        return 0;
    }

    public static int lightRadiusPerLevel(Item item) {
        if (item == null || item.lightRadius <= 0) return 0;
        return scaleIncrement(Math.max(1, (int) item.lightRadius));
    }

    public static int effectSizePerLevel(Item item) {
        if (item == null || item.effectSize <= 0) return 0;
        int factor = isCascadeWand(item)
                ? GameBalance.TILECOUNT_CASCADE_FACTOR
                : GameBalance.TILECOUNT_LEVEL_SCALE_FACTOR;
        return scaleIncrement(item.effectSize, factor);
    }

    public static int effectDurationPerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.effectDuration);
    }

    public static int effectRangePerLevel(Item item) {
        return item == null ? 0 : scaleIncrement(item.effectRange);
    }

    /** Max-charge L0 → L1 delta. Both the wand sqrt curve and the tool
     *  piecewise curve are non-linear, so this is "what the first +1
     *  earns" rather than a constant per-level rate - good enough for a
     *  UI hint that the cap scales. Returns 0 when the item isn't a
     *  charged item. */
    public static int maxChargePerLevelHint(Item item) {
        if (item == null || item.baseChargeMax <= 0) return 0;
        return Math.max(0,
                effectiveMaxCharge(item, 1) - effectiveMaxCharge(item, 0));
    }
}
