package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;

import java.util.Random;

/**
 * Single source of truth for every tunable game-balance number: player base stats by class,
 * per-level progression, action costs, shared defaults. Anything a designer might want to
 * tweak without touching logic code lives here.
 *
 * <p>Non-balance constants (e.g. atlas offsets, rendering padding) do NOT belong here — this
 * class is reserved for <i>gameplay</i> knobs so they can be scanned and adjusted together.
 *
 * <p>This class is intentionally final + no-construct — all fields are {@code public static
 * final}. Grouping is by section header; keep new constants inside the right section.
 */
public final class GameBalance {

    private GameBalance() {}

    // ───────────────────────── Combat simulation ─────────────────────────────

    /** Default number of duels {@link #mobfight} runs to estimate win rate. 10k gives ~±1%
     *  on the percentage it returns; bumping higher costs linearly more time. */
    public static final int MOBFIGHT_DEFAULT_TRIALS = 10_000;

    /**
     * Simulate {@link #MOBFIGHT_DEFAULT_TRIALS} headless 1-on-1 melee duels between
     * {@code a} and {@code b} and return the fraction (0..1) of fights {@code a} wins. Each
     * duel resets HP and runs until one combatant drops to zero, with attacker turn order
     * driven by {@link Mob#attackCost} (lower-cost mob acts more often). No items, terrain,
     * or AI — just accuracy/evasion to-hit and damage/armor rolls.
     *
     * <p>Does not mutate the input mobs.
     */
    public static double mobfight(Mob a, Mob b) {
        return mobfight(a, b, MOBFIGHT_DEFAULT_TRIALS);
    }

    /** {@link #mobfight(Mob, Mob)} with an explicit trial count. */
    public static double mobfight(Mob a, Mob b, int trials) {
        if (a == null || b == null || trials <= 0) return 0.0;
        Random rng = new Random();
        // Snapshot effective stats so the sim sees the same values combat code would.
        com.bjsp123.rl2.model.StatBlock as = a.effectiveStats();
        com.bjsp123.rl2.model.StatBlock bs = b.effectiveStats();
        int aMax = (int) Math.round(as.maxHp), bMax = (int) Math.round(bs.maxHp);
        int aAcc = as.accuracy, aEva = as.evasion, aArm = as.armor.min(), aCost = Math.max(1, as.attackCost);
        int bAcc = bs.accuracy, bEva = bs.evasion, bArm = bs.armor.min(), bCost = Math.max(1, bs.attackCost);
        com.bjsp123.rl2.model.MinMax aDmg = MobSystem.rawDamageRange(a);
        com.bjsp123.rl2.model.MinMax bDmg = MobSystem.rawDamageRange(b);
        // Armor range bypasses the equipment lookup — mob-only base armor + nothing else,
        // matching the input snapshot.
        int aResLo = aArm, aResHi = aArm;
        int bResLo = bArm, bResHi = bArm;

        int aWins = 0;
        for (int t = 0; t < trials; t++) {
            int aHp = aMax, bHp = bMax;
            // "Time-to-next-attack" counters; lower = acts sooner. Tied counters advance both
            // simultaneously, but combat damage is applied in A→B order to keep the result
            // reproducible and break the tie deterministically.
            int aReady = 0, bReady = 0;
            while (aHp > 0 && bHp > 0) {
                if (aReady <= bReady) {
                    bHp -= rollHit(rng, aAcc, bEva, aDmg, bResLo, bResHi);
                    aReady += aCost;
                    if (bHp <= 0) break;
                }
                if (bReady <= aReady) {
                    aHp -= rollHit(rng, bAcc, aEva, bDmg, aResLo, aResHi);
                    bReady += bCost;
                }
            }
            if (aHp > 0 && bHp <= 0) aWins++;
        }
        return aWins / (double) trials;
    }

    /** Single attack roll: returns damage dealt, or 0 on a miss. */
    private static int rollHit(Random rng, int acc, int eva,
                               com.bjsp123.rl2.model.MinMax rawDmg, int resLo, int resHi) {
        int denom = acc + eva;
        if (denom <= 0 || rng.nextInt(denom) >= acc) return 0;
        int raw = rawDmg.max() > rawDmg.min()
                ? rawDmg.min() + rng.nextInt(rawDmg.max() - rawDmg.min() + 1)
                : rawDmg.min();
        int res = resHi > resLo ? resLo + rng.nextInt(resHi - resLo + 1) : resLo;
        return Math.max(0, raw - res);
    }

    // ───────────────────────── Character progression ─────────────────────────
    /** Hard cap — characters stop leveling at this level even if they accrue more XP. */
    public static final int MAX_CHARACTER_LEVEL   = 32;
    /** XP cost to advance from level {@code N} to {@code N+1} = {@code N × XP_PER_LEVEL_STEP}. */
    public static final int XP_PER_LEVEL_STEP     = 10;
    /** Stat bumps applied each time a character levels up. */
    public static final int ATTACK_PER_LEVEL      = 1;
    public static final int DEFENSE_PER_LEVEL     = 1;
    public static final int HP_PER_LEVEL          = 2;
    public static final int PERK_POINTS_PER_LEVEL = 1;

    // ───────────────────────── Player shared costs ───────────────────────────
    public static final int    PLAYER_MOVE_COST     = 100;
    public static final int    PLAYER_ATTACK_COST   = 100;
    public static final double PLAYER_VISION_RADIUS = 16.0;
    public static final double PLAYER_HEAL_RATE     = 0.01;

    // ───────────────────────── Warrior class ─────────────────────────────────
    public static final int WARRIOR_START_HP    = 25;
    public static final int WARRIOR_BASE_ATTACK = 12;
    public static final int WARRIOR_BASE_DEFENSE = 5;
    public static final int WARRIOR_BASE_DAMAGE = 2;

    // ───────────────────────── Rogue class ───────────────────────────────────
    public static final int ROGUE_START_HP    = 15;
    public static final int ROGUE_BASE_ATTACK = 14;
    public static final int ROGUE_BASE_DEFENSE = 10;
    public static final int ROGUE_BASE_DAMAGE = 1;

    // ───────────────────────── Mage class ────────────────────────────────────
    public static final int MAGE_START_HP    = 12;
    public static final int MAGE_BASE_ATTACK = 10;
    public static final int MAGE_BASE_DEFENSE = 6;
    public static final int MAGE_BASE_DAMAGE = 1;

    // ───────────────────────── Combat effects ────────────────────────────────
    /** Damage dealt by a magic missile hit. Legacy fallback for the staff's vanilla
     *  missile path; the wand-of-magic-missile and other level-scaling sources use
     *  {@link #BASIC_WAND_DAMAGE_MIN} / {@link #BASIC_WAND_DAMAGE_MAX} instead. */
    public static final int MAGIC_MISSILE_DAMAGE = 3;

    // ───────────────────────── Item-level scaling ────────────────────────────
    // Items carry a {@code level} field. Level 0 is baseline; every level above adds
    // a fixed increment to the relevant stat. Player starter items are always level 0.
    // Dungeon-generated items roll a random level in {@code [0, dungeonDepth]}. Food
    // is always level 0.

    /** Baseline damage range for a level-0 wand attack (wand of magic missile, etc.). */
    public static final int BASIC_WAND_DAMAGE_MIN = 2;
    public static final int BASIC_WAND_DAMAGE_MAX = 4;
    /** Per-level damage increment for wand attacks. */
    public static final int WAND_DAMAGE_INCREMENT_MIN = 1;
    public static final int WAND_DAMAGE_INCREMENT_MAX = 2;

    /** Baseline area-of-effect (in tiles affected) for level-0 wand spells with an
     *  area component (vegetation / fungus / fire / oil / water). */
    public static final int WAND_EFFECT_TILES = 5;
    /** Extra tiles affected per wand level. */
    public static final int WAND_EFFECT_TILE_INCREMENT = 4;

    /** Baseline food value of a level-0 food item. Food doesn't scale with level — it's
     *  always level 0 — so this is the only food number that matters. */
    public static final int BASIC_FOOD_VALUE = 10_000;

    /** Per-level armour-range bump applied to any armour-slot or shield-slot item that
     *  has a non-zero level. Stacks additively on top of the item's base armour range. */
    public static final int ARMOR_INCREMENT_MIN = 1;
    public static final int ARMOR_INCREMENT_MAX = 1;

    /** Per-level damage-range bump for any weapon-slot item with a non-zero level. */
    public static final int WEAPON_INCREMENT_MIN = 1;
    public static final int WEAPON_INCREMENT_MAX = 2;

    /** Per-level accuracy / evasion bump for mobs (and the player) when their character
     *  level rises. Aliases of {@link #ATTACK_PER_LEVEL} and {@link #DEFENSE_PER_LEVEL}
     *  — kept for clarity in mob-spawn code that wants to read "what does each mob
     *  level grant?" rather than the player-progression-flavoured names above. */
    public static final int MOB_ACCURACY_INCREMENT = ATTACK_PER_LEVEL;
    public static final int MOB_EVASION_INCREMENT  = DEFENSE_PER_LEVEL;

    /** Healing amount on a level-0 healing potion. Higher-level potions add
     *  {@link #HEAL_VALUE_INCREMENT} per level. */
    public static final int BASIC_HEAL_VALUE     = 20;
    public static final int HEAL_VALUE_INCREMENT = 10;

    /** Bomb base damage (level 0) and per-level increment. Used by the fire / oil /
     *  blast / freeze bomb thrown effects. */
    public static final int BOMB_DAMAGE_BASE      = 3;
    public static final int BOMB_DAMAGE_INCREMENT = 2;
    /** Bomb base AOE (tiles affected at level 0) and per-level increment. */
    public static final int BOMB_EFFECT_TILES          = 5;
    public static final int BOMB_EFFECT_TILE_INCREMENT = 4;

    // ───────────────────────── Hunger / satiety ──────────────────────────────
    /** Starting satiety for a fresh mob. Counts down by one per passing tick. */
    public static final int STARTING_SATIETY       = 10000;
    /** Once satiety is exhausted, the player loses 1 HP per this many ticks. */
    public static final int STARVATION_TICKS_PER_HP = 100;
}
