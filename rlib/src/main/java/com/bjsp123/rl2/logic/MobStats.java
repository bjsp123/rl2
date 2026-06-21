package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.StatBlock;

import java.util.Random;

/** Effective-stat rollup and combat-number helpers for mobs. */
public final class MobStats {

    private static final Random RANDOM =
            com.bjsp123.rl2.util.SimRng.register("MobStats", new Random());

    private MobStats() {}

    /** Probability that {@code attacker} lands a hit on {@code target}. */
    public static double hitChance(Mob attacker, Mob target) {
        int acc = attacker.effectiveStats().accuracy;
        int eva = target.effectiveStats().evasion;
        int denom = acc + eva;
        return denom <= 0 ? 0.0 : (double) acc / denom;
    }

    /** Min and max damage the attacker outputs before resistance. */
    public static MinMax rawDamageRange(Mob attacker) {
        return attacker.effectiveStats().damage;
    }

    /** Min and max damage the target resists. */
    public static MinMax resistRange(Mob target) {
        return target.effectiveStats().armor;
    }

    /** Min and max bonus damage the attacker lands ignoring armour. */
    public static MinMax apDamageRange(Mob attacker) {
        return attacker.effectiveStats().apDamage;
    }

    /** Min and max magic resistance the target rolls per non-physical hit. */
    public static MinMax magicResistRange(Mob target) {
        return target.effectiveStats().magicResist;
    }

    /** Min and max damage attacker can land on target after resistance, floored at 0,
     *  plus the AP bonus. */
    public static MinMax netDamageRange(Mob attacker, Mob target) {
        return netDamageRange(rawDamageRange(attacker), resistRange(target),
                apDamageRange(attacker));
    }

    /** Component form of {@link #netDamageRange(Mob, Mob)} - takes the raw,
     *  resistance, and AP ranges explicitly so chip / preview code can mix in
     *  an item-derived raw range (e.g. a bomb's {@code effectiveDamageRange})
     *  while still resolving through the canonical formula. */
    public static MinMax netDamageRange(MinMax raw, MinMax armor, MinMax ap) {
        return new MinMax(Math.max(0, raw.min() - armor.max()) + ap.min(),
                          Math.max(0, raw.max() - armor.min()) + ap.max());
    }

    /** Roll a uniform integer in {@code [range.min, range.max]}. */
    public static int rollRange(MinMax range) {
        return range.max() > range.min()
                ? range.min() + RANDOM.nextInt(range.max() - range.min() + 1)
                : range.min();
    }

    /**
     * Single rollup for a mob's effective stats. Copies the intrinsic block,
     * applies character-level scaling (AMOUNT rule for most stats; +1/3 levels
     * for knockback; proportional-to-maxHp for healRate), then folds in
     * equipped items and active buffs.
     */
    public static void writeEffectiveStats(Mob mob, StatBlock dst) {
        dst.copyFrom(mob.intrinsic);

        // Character-level baseline: charLvl=1 is the intrinsic ("L=0" in
        // scaleAmount terms). Everything past that grows via the AMOUNT rule.
        int L = Math.max(0, mob.characterLevel - 1);

        // Scaled scalars (AMOUNT rule via ItemStats.scaleAmount).
        double baseMaxHp = mob.intrinsic.maxHp;
        // HP per-level inc is capped at MOB_HP_INC_CAP so high-base bosses
        // (ORC_PRESIDENT etc) don't accumulate absurd HP at depth.
        int baseHp = (int) baseMaxHp;
        int incHp = baseHp > 0
                ? Math.min(GameBalance.MOB_HP_INC_CAP,
                        Math.max(1, baseHp / GameBalance.AMOUNT_LEVEL_SCALE_FACTOR))
                : 0;
        // Difficulty HP scaling (RL-: difficulty levels): the player and enemies
        // get separate max-HP multipliers, applied here so the proportional
        // healRate scaling below inherits the boosted pool.
        long levelMaxHp = baseHp + (long) L * incHp;
        double hpMult = mob.isPlayer
                ? GameBalance.PLAYER_HP_MULTIPLIER
                : GameBalance.ENEMY_HP_MULTIPLIER;
        dst.maxHp          = Math.max(1, Math.round(levelMaxHp * hpMult));
        dst.accuracy       = ItemStats.scaleAmount(mob.intrinsic.accuracy, L);
        dst.evasion        = ItemStats.scaleAmount(mob.intrinsic.evasion,  L);
        dst.rangedDistance = ItemStats.scaleAmount(mob.intrinsic.rangedDistance, L);

        // healRate scales proportionally to maxHp so %-healed-per-turn stays flat.
        dst.healRate = baseMaxHp > 0
                ? mob.intrinsic.healRate * (dst.maxHp / baseMaxHp)
                : mob.intrinsic.healRate;
        // Difficulty player regen: Easy heals a flat % of max HP per turn. healRate
        // is per-tick, so divide the per-turn fraction by the ticks/turn.
        if (mob.isPlayer && GameBalance.PLAYER_REGEN_FRAC_PER_TURN > 0) {
            dst.healRate += dst.maxHp * GameBalance.PLAYER_REGEN_FRAC_PER_TURN
                    / TurnSystem.STANDARD_TURN_TICKS;
        }

        // Knockback comes from intrinsic (mobs.csv), the equipped weapon
        // (scaled in ItemStats, added below), and the KNOCKBACK perk - never
        // from character level on its own.
        dst.knockbackSquares = mob.intrinsic.knockbackSquares;

        // Range stats: scale the single-int base, expose as [N/2, N] MinMax.
        dst.damage       = rangeFromBase(mob.baseDamage,       L);
        dst.armor        = rangeFromBase(mob.baseArmor,        L);
        dst.apDamage     = rangeFromBase(mob.baseApDamage,     L);
        dst.magicResist  = rangeFromBase(mob.baseMagicResist,  L);
        dst.rangedDamage = rangeFromBase(mob.baseRangedDamage, L);

        // Equipped items + active buffs add on top of the scaled baseline.
        ItemStats.contributeInto(dst, mob.inventory.weapon, mob);
        ItemStats.contributeInto(dst, mob.inventory.offhand, mob);
        ItemStats.contributeInto(dst, mob.inventory.armor, mob);
        for (Item eq : mob.inventory.amulets) ItemStats.contributeInto(dst, eq, mob);
        for (Item eq : mob.inventory.gems)    ItemStats.contributeInto(dst, eq, mob);
        BuffSystem.contributeInto(dst, mob);

        // Difficulty player-speed: a faster player has a lower move cost. Applied
        // last so it stacks on top of the buff (haste) multipliers.
        if (mob.isPlayer && GameBalance.PLAYER_SPEED_MULTIPLIER > 0
                && GameBalance.PLAYER_SPEED_MULTIPLIER != 1.0) {
            dst.moveCost = (int) Math.max(1,
                    Math.round(dst.moveCost / GameBalance.PLAYER_SPEED_MULTIPLIER));
        }
    }

    /** Derive a combat range [N/2, N] from a single-int base, scaled by
     *  character level under the AMOUNT rule. Zero base returns MinMax.ZERO. */
    private static MinMax rangeFromBase(int base, int level) {
        if (base <= 0) return MinMax.ZERO;
        int scaled = ItemStats.scaleAmount(base, level);
        return new MinMax(Math.max(0, scaled / 2), scaled);
    }
}
