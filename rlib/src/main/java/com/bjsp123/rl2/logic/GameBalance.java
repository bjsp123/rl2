package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.Random;

/**
 * Single source of truth for every tunable game-balance number: player base stats by class,
 * per-level progression, action costs, shared defaults. Anything a designer might want to
 * tweak without touching logic code lives here.
 *
 * <p>Non-balance constants (e.g. atlas offsets, rendering padding) do NOT belong here - this
 * class is reserved for <i>gameplay</i> knobs so they can be scanned and adjusted together.
 *
 * <p>Tunables are {@code public static} (not {@code final}) so {@link #load(String)} can
 * override them at startup from {@code assets/data/gamebalance.properties}. Each Java field
 * carries a baked-in baseline that takes effect if the properties file is missing or omits
 * a key. Grouping is by section header; keep new constants inside the right section.
 */
public final class GameBalance {

    private GameBalance() {}

    /** Read a {@code key=value} properties file and override any matching {@code public
     *  static} field on this class. Unknown keys are ignored; missing keys keep their
     *  Java-side baseline. Currently handles {@code int} and {@code double} fields - that
     *  covers every tunable we have today. Call once at startup, before gameplay code
     *  reads any of these fields. */
    public static void load(String text) {
        if (text == null || text.isEmpty()) return;
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse gamebalance.properties", e);
        }
        for (String key : props.stringPropertyNames()) {
            String raw = props.getProperty(key);
            if (raw == null) continue;
            String value = raw.trim();
            try {
                Field f = GameBalance.class.getDeclaredField(key);
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) continue;
                Class<?> t = f.getType();
                if (t == int.class)         f.setInt(null, Integer.parseInt(value));
                else if (t == double.class) f.setDouble(null, Double.parseDouble(value));
                else if (t == long.class)   f.setLong(null, Long.parseLong(value));
                else if (t == boolean.class) f.setBoolean(null, Boolean.parseBoolean(value));
            } catch (NoSuchFieldException | IllegalAccessException | NumberFormatException ignored) {
                // Unknown key, non-overridable field, or malformed value - keep the baseline.
            }
        }
    }

    // ------------------------- Combat simulation -----------------------------

    /** Default number of duels {@link #mobfight} runs to estimate win rate. 10k gives ~+/-1%
     *  on the percentage it returns; bumping higher costs linearly more time. */
    public static int MOBFIGHT_DEFAULT_TRIALS = 10_000;

    /**
     * Simulate {@link #MOBFIGHT_DEFAULT_TRIALS} headless 1-on-1 melee duels between
     * {@code a} and {@code b} and return the fraction (0..1) of fights {@code a} wins. Each
     * duel resets HP and runs until one combatant drops to zero, with attacker turn order
     * driven by {@link Mob#attackCost} (lower-cost mob acts more often). No items, terrain,
     * or AI - just accuracy/evasion to-hit and damage/armor rolls.
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
        // Armor range bypasses the equipment lookup - mob-only base armor + nothing else,
        // matching the input snapshot.
        int aResLo = aArm, aResHi = aArm;
        int bResLo = bArm, bResHi = bArm;

        int aWins = 0;
        for (int t = 0; t < trials; t++) {
            int aHp = aMax, bHp = bMax;
            // "Time-to-next-attack" counters; lower = acts sooner. Tied counters advance both
            // simultaneously, but combat damage is applied in A->B order to keep the result
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

    // ------------------------- Character progression -------------------------
    /** Hard cap - characters stop leveling at this level even if they accrue more XP. */
    public static int MAX_CHARACTER_LEVEL   = 32;
    /** XP cost to advance from level {@code N} to {@code N+1} = {@code N x XP_PER_LEVEL_STEP}. */
    public static int XP_PER_LEVEL_STEP     = 10;
    /** Perk points granted on each level-up. (Per-stat level deltas are now
     *  per-mob - see the {@code *PerLevel} columns of {@code mobs.csv}.) */
    public static int PERK_POINTS_PER_LEVEL = 1;

    public static int XP_PER_POWER_ORB = 10;

    /** Maximum bag slots for equipment items (WEAPON, OFFHAND, ARMOR, AMULET) not
     *  yet placed in an equipment slot. Each item occupies exactly one slot (equipment
     *  never stacks). */
    public static int BAG_EQUIPMENT_SIZE = 20;
    /** Maximum bag slots for gem items not yet socketed. */
    public static int BAG_GEMS_SIZE = 20;
    /** Maximum bag slots for food items (FOOD). Identical food items at the same
     *  level merge into a single counted stack. */
    public static int BAG_FOOD_SIZE = 20;
    /** Maximum bag slots for consumable / tool items (POTION, WAND, ORB, BOMB, ITEM).
     *  Potions and bombs at the same type + level merge into stacks; wands, orbs, and
     *  tools are always singletons. */
    public static int BAG_ITEMS_SIZE = 20;

    // ------------------------- Item-level scaling ----------------------------
    // Items carry a {@code level} field. Level 0 is baseline; every level above adds
    // a fixed increment to the relevant stat. Per-level scaling values are now per-item
    // ({@code damagePerLevel}, {@code armorPerLevel}, {@code tilesAffectedPerLevel} columns
    // on items.csv) - there are no kind-wide scaling globals here.

    /** Baseline food value of a level-0 food item. Food doesn't scale with level - it's
     *  always level 0 - so this is the only food number that matters. */
    public static int BASIC_FOOD_VALUE = 10_000;

    // ------------------------- Level dimensions / density -------------------
    /** Default base dimensions for every dungeon level. The {@code BIGLEVEL}
     *  flag scales these by 1.5x; otherwise every level is exactly this size. */
    public static int LEVEL_BASE_W = 48;
    public static int LEVEL_BASE_H = 48;

    /** Target number of rooms per level. Each {@code Layout} builder in
     *  {@link LevelFactory} is parameterised by this so different layouts in
     *  the same dungeon don't read as wildly different densities. */
    public static int LEVEL_TARGET_ROOMS    = 8;
    /** +/- tolerance around {@link #LEVEL_TARGET_ROOMS}. Builders that can produce
     *  more rooms than the cap (greedy growth in PACKED, BSP partition leaf
     *  variance) trim or stop at {@code LEVEL_TARGET_ROOMS + LEVEL_ROOM_TOLERANCE}. */
    public static int LEVEL_ROOM_TOLERANCE  = 2;

    /** Inclusive size range, in tiles, for a single room's side length. Applies
     *  uniformly to every layout's randomly-sized rooms. (VILLAGE buildings are
     *  thematically smaller and intentionally use their own narrower range.) */
    public static int ROOM_MIN_SIZE = 4;
    public static int ROOM_MAX_SIZE = 10;

    // ------------------------- World generation -----------------------------
    /** Number of dungeon depths the world generator builds. Depth 1 (top) and
     *  {@code DUNGEON_DEPTH} (bottom) are CENTER-side single-level rows; the
     *  intermediate depths each carry one WEST + one EAST level. */
    public static int DUNGEON_DEPTH = 5;
    /** Per-roll probability that a given E or W level grows a dead-end side
     *  branch (a single extra level at column +/-2 of the parent's depth). */
    public static double SIDE_BRANCH_PROBABILITY = 0.4;
    /** Per-roll probability that a given E or W level (where a sibling exists
     *  at depth+1) gets a second downstairs cross-linking it to the opposite
     *  side, in addition to its same-side downstairs. */
    public static double CROSSLINK_PROBABILITY = 0.4;

    // ------------------------- Level population ------------------------------
    /** Base hostile-mob target when populating a fresh level. Actual count is
     *  {@code STARTING_MOBS_PER_LEVEL + rng(4)}, so each level starts with
     *  this many to {@code this + 3} hostiles. */
    public static int STARTING_MOBS_PER_LEVEL = 7;
    /** Minimum random item clusters placed per level (on top of
     *  {@code guaranteedPerLevel} CSV drops). Actual clusters =
     *  {@code RANDOM_ITEMS_PER_LEVEL + rng(3)}, so each level gets this many
     *  to {@code this + 2} random clusters. */
    public static int RANDOM_ITEMS_PER_LEVEL = 1;
    /** Multiplier applied to every mob's pre-rolled loot count at spawn time.
     *  1.0 = baseline CSV quantities; 2.0 = twice as many drops per mob;
     *  0.5 = half. Fractional parts are probabilistic: a scaled count of 1.5
     *  gives each drop a 50 % chance of one extra copy. */
    public static double LOOT_DROP_FREQUENCY_COEFF = 1.0;

    // ------------------------- Mob population caps ---------------------------
    /** Hard cap on mobs alive on a level - magical / scripted spawn effects
     *  (summon wands, kissyblob eat-spawn, mouse mushroom-eat-spawn) skip
     *  their spawn when the level is at or above this. Initial level
     *  population (LevelFactoryPopulate) is NOT subject to this cap. */
    public static int MAX_MOBS_ON_LEVEL = 30;

    /** Per-species cap for spawner mobs (anthills today, future spawners by the
     *  same {@code turnSpawnType} flag). A spawner skips its budding roll when
     *  the level already holds this many of its {@code turnSpawnType}. Combined
     *  with {@link #MAX_MOBS_ON_LEVEL} - both must pass before the bud is
     *  created. */
    public static int MAX_MOBS_FROM_SPAWNER = 8;

    // ------------------------- Hunger / satiety ------------------------------
    /** Starting satiety for a fresh mob. Counts down by one per passing tick. */
    public static int STARTING_SATIETY       = 10000;
    /** Once satiety is exhausted, the player loses 1 HP per this many ticks. */
    public static int STARVATION_TICKS_PER_HP = 100;
}
