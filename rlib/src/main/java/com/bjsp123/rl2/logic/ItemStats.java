package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.StatBlock;

/** Effective item stat and level calculations. */
public final class ItemStats {
    private ItemStats() {}

    public static void contributeInto(StatBlock dst, Item item) {
        if (item == null || dst == null) return;
        if (item.damage.max() > 0) dst.damage = dst.damage.plus(effectiveDamageRange(item));
        if (item.armor.max()  > 0) dst.armor  = dst.armor .plus(effectiveArmorRange(item));
        if (item.apDamage.max() > 0) dst.apDamage = dst.apDamage.plus(effectiveApDamageRange(item));
        if (item.magicResist.max() > 0) {
            dst.magicResist = dst.magicResist.plus(effectiveMagicResistRange(item));
        }
        dst.accuracy += item.accuracy;
        dst.evasion  += item.evasion;
        if (item.attackSpeed != Item.ATTACK_SPEED_DEFAULT) dst.attackCost *= item.attackSpeed;
        if (item.moveSpeed != Item.MOVE_SPEED_DEFAULT) dst.moveCost *= item.moveSpeed;
        if (item.lightRadius > dst.lightRadius) dst.lightRadius = item.lightRadius;
        dst.knockbackSquares += item.knockbackSquares;

        if (item.brand != null) {
            BrandDefinition b = item.brand;
            if (b.damage    > 0) dst.damage      = dst.damage.plus(new MinMax(b.damage, b.damage));
            if (b.armor     > 0) dst.armor       = dst.armor.plus(new MinMax(b.armor, b.armor));
            if (b.antimagic > 0) dst.magicResist = dst.magicResist.plus(new MinMax(b.antimagic, b.antimagic));
            dst.accuracy += b.accuracy;
            dst.evasion  += b.evasion;
            if (b.attackSpeed != 1.0) dst.attackCost = (int) (dst.attackCost * b.attackSpeed);
            if (b.moveSpeed   != 1.0) dst.moveCost   = (int) (dst.moveCost   * b.moveSpeed);
            dst.knockbackSquares += b.knockback;
        }
    }

    public static MinMax effectiveApDamageRange(Item item) {
        if (item == null || item.apDamage.max() <= 0) return MinMax.ZERO;
        int lvl = clampedLevel(item);
        MinMax inc = item.apDamagePerLevel == null ? MinMax.ZERO : item.apDamagePerLevel;
        return new MinMax(item.apDamage.min() + lvl * inc.min(),
                item.apDamage.max() + lvl * inc.max());
    }

    public static MinMax effectiveMagicResistRange(Item item) {
        if (item == null || item.magicResist.max() <= 0) return MinMax.ZERO;
        int lvl = clampedLevel(item);
        MinMax inc = item.magicResistPerLevel == null ? MinMax.ZERO : item.magicResistPerLevel;
        return new MinMax(item.magicResist.min() + lvl * inc.min(),
                item.magicResist.max() + lvl * inc.max());
    }

    public static MinMax effectiveDamageRange(Item item) {
        return effectiveDamageRange(item, clampedLevel(item));
    }

    public static MinMax effectiveDamageRange(Item item, Mob holder) {
        return effectiveDamageRange(item, effectiveLevel(item, holder));
    }

    public static MinMax effectiveDamageRange(Item item, int level) {
        if (item == null || item.damage.max() <= 0) return MinMax.ZERO;
        return levelScaledRangeAt(level,
                item.damage.min(), item.damage.max(),
                item.damagePerLevel.min(), item.damagePerLevel.max());
    }

    public static MinMax effectiveArmorRange(Item item) {
        if (item == null || item.armor.max() <= 0) return MinMax.ZERO;
        return levelScaledRange(item,
                item.armor.min(), item.armor.max(),
                item.armorPerLevel.min(), item.armorPerLevel.max());
    }

    public static int effectiveFoodValue(Item item) {
        if (item == null || item.foodValue <= 0) return 0;
        return item.foodValue;
    }

    public static int effectiveLevel(Item item, Mob holder) {
        if (item == null) return 0;
        if (item.useBehavior == Item.UseBehavior.POWERUP) return 0;
        int lvl = clampedLevel(item);
        if (holder != null && holder.perks != null) {
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
        if (holder != null
                && (item.inventoryCategory == Item.InventoryCategory.WAND
                || item.inventoryCategory == Item.InventoryCategory.POTION)) {
            lvl += BuffSystem.sorceryBonus(holder);
            if (holder.inventory != null) {
                if (hasSorceryBrand(holder.inventory.weapon))  lvl++;
                if (hasSorceryBrand(holder.inventory.offhand)) lvl++;
                if (hasSorceryBrand(holder.inventory.armor))   lvl++;
                for (Item eq : holder.inventory.amulets) if (hasSorceryBrand(eq)) lvl++;
                for (Item eq : holder.inventory.gems)    if (hasSorceryBrand(eq)) lvl++;
            }
        }
        // Signature-item scaling: jade items grow with the holder's
        // character level. Capped at +5 so a stack-of-jades doesn't
        // out-scale natural per-level item growth.
        if (item.scalesWithUser && holder != null) {
            int bonus = Math.min(5, Math.max(0, holder.characterLevel) / 4);
            lvl += bonus;
        }
        return lvl;
    }

    private static boolean hasSorceryBrand(Item item) {
        return item != null && item.brand != null && item.brand.sorcery;
    }

    public static int effectiveMaxCharge(Item it, Mob holder) {
        return Math.max(1, it.baseChargeMax + effectiveLevel(it, holder));
    }

    public static int effectiveBuffLevel(Item item) {
        return effectiveBuffLevel(item, null);
    }

    /** Holder-aware overload. When {@code holder} is non-null and {@code item}
     *  has {@link Item#scalesWithUser}, the buff level picks up the same
     *  character-level bonus that {@link #effectiveLevel(Item, Mob)} applies. */
    public static int effectiveBuffLevel(Item item, Mob holder) {
        int lvl = 1 + clampedLevel(item);
        if (item != null && item.scalesWithUser && holder != null) {
            lvl += Math.min(5, Math.max(0, holder.characterLevel) / 4);
        }
        return lvl;
    }

    public static int effectiveBuffDuration(Item item) {
        return effectiveBuffDuration(item, null);
    }

    /** Holder-aware buff duration, in <b>ticks</b>. Linear in character level
     *  thanks to integer math at tick granularity (no float).
     *
     *  <p>Formula:
     *  {@code dur_ticks = abilityPower * STANDARD_TURN_TICKS * (4 + min(20, max(0, characterLevel))) / 4}
     *  when {@link Item#scalesWithUser} is set; otherwise the
     *  {@code (4 + charLvl)} term collapses to {@code (4 + itemLevel*4) / 4 = 1 + itemLevel}.
     *
     *  <p>Worked example - JADE_CRAB (abilityPower=4, item.level=0, scalesWithUser=true):
     *  <ul>
     *    <li>char L1: 4 * 100 * 5 / 4  = 500  ticks (5 standard turns)</li>
     *    <li>char L3: 4 * 100 * 7 / 4  = 700  ticks (7 standard turns)</li>
     *    <li>char L10: 4 * 100 * 14 / 4 = 1400 ticks (14 standard turns)</li>
     *    <li>char L20+: 4 * 100 * 24 / 4 = 2400 ticks (cap)</li>
     *  </ul>
     */
    public static int effectiveBuffDuration(Item item, Mob holder) {
        if (item == null) return 1;
        int base = item.abilityPower > 0f ? Math.max(1, (int) item.abilityPower) : 1;
        int charBonusQuarters = 0;
        if (item.scalesWithUser && holder != null) {
            charBonusQuarters = Math.min(20, Math.max(0, holder.characterLevel));
        }
        int itemBonusQuarters = 4 * clampedLevel(item);
        int totalQuarters = 4 + itemBonusQuarters + charBonusQuarters;
        int numerator = base * com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS
                * totalQuarters;
        return Math.max(1, numerator / 4);
    }

    public static int effectiveTilesAffected(Item item) {
        return effectiveTilesAffected(item, clampedLevel(item));
    }

    public static int effectiveTilesAffected(Item item, Mob holder) {
        return effectiveTilesAffected(item, effectiveLevel(item, holder));
    }

    public static int effectiveTilesAffected(Item item, int level) {
        if (item == null || item.tilesAffected <= 0) return 0;
        return item.tilesAffected + Math.max(0, level) * item.tilesAffectedPerLevel;
    }

    static int clampedLevel(Item item) {
        return item == null ? 0 : Math.max(0, item.level);
    }

    private static MinMax levelScaledRange(Item item,
                                           int baseMin, int baseMax,
                                           int incMin, int incMax) {
        return levelScaledRangeAt(clampedLevel(item), baseMin, baseMax, incMin, incMax);
    }

    private static MinMax levelScaledRangeAt(int level,
                                             int baseMin, int baseMax,
                                             int incMin, int incMax) {
        int lvl = Math.max(0, level);
        int min = baseMin + lvl * incMin;
        int max = baseMax + lvl * incMax;
        return new MinMax(Math.max(0, min), Math.max(min, max));
    }
}
