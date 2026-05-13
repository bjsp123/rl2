package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.StatBlock;

import java.util.Random;

/** Effective-stat rollup and combat-number helpers for mobs. */
public final class MobStats {

    private static final Random RANDOM = new Random();

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
        MinMax dmg = rawDamageRange(attacker);
        MinMax res = resistRange(target);
        MinMax ap  = apDamageRange(attacker);
        return new MinMax(Math.max(0, dmg.min() - res.max()) + ap.min(),
                          Math.max(0, dmg.max() - res.min()) + ap.max());
    }

    /** Roll a uniform integer in {@code [range.min, range.max]}. */
    public static int rollRange(MinMax range) {
        return range.max() > range.min()
                ? range.min() + RANDOM.nextInt(range.max() - range.min() + 1)
                : range.min();
    }

    /**
     * Single rollup for a mob's effective stats. Copies the intrinsic block, then folds
     * in every contributor in declaration order: character-level bonus, equipped items,
     * active buffs.
     */
    public static void writeEffectiveStats(Mob mob, StatBlock dst) {
        dst.copyFrom(mob.intrinsic);
        characterLevelBonusInto(dst, mob);
        for (Item eq : mob.inventory.allEquipped()) {
            ItemSystem.contributeInto(dst, eq);
        }
        BuffSystem.contributeInto(dst, mob);
    }

    /** Placeholder hook for future MobProgression stat contributors. */
    private static void characterLevelBonusInto(StatBlock dst, Mob mob) {
        // Intentionally empty.
    }
}
