package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.util.CsvTable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
     * override them at startup from {@code assets/data/config.csv}. Each Java field
 * carries a baked-in baseline that takes effect if the properties file is missing or omits
 * a key. Grouping is by section header; keep new constants inside the right section.
 */
public final class GameBalance {

    private GameBalance() {}

    /** Read {@code assets/data/config.csv} rows with kind=gamebalance and override matching {@code public
     *  static} field on this class. Unknown keys are ignored; missing keys keep their
     *  Java-side baseline. Currently handles {@code int} and {@code double} fields - that
     *  covers every tunable we have today. Call once at startup, before gameplay code
     *  reads any of these fields. */
    public static void load(String text) {
        if (text == null || text.isEmpty()) return;
        CsvTable table = CsvTable.parse(text);
        for (java.util.Map<String, String> row : table.rows) {
            if (!"gamebalance".equals(CsvTable.str(row, "kind", ""))) continue;
            String key = CsvTable.str(row, "key", "");
            String value = CsvTable.str(row, "value", "").trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            try {
                Field f = fieldForKey(key);
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

    private static Field fieldForKey(String key) throws NoSuchFieldException {
        try {
            return GameBalance.class.getDeclaredField(key);
        } catch (NoSuchFieldException ex) {
            return GameBalance.class.getDeclaredField(normalizedFieldName(key));
        }
    }

    private static String normalizedFieldName(String key) {
        StringBuilder out = new StringBuilder();
        boolean prevWasUnderscore = true;
        boolean prevWasLowerOrDigit = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && prevWasLowerOrDigit && !prevWasUnderscore) {
                out.append('_');
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
                prevWasUnderscore = false;
                prevWasLowerOrDigit = Character.isLowerCase(c) || Character.isDigit(c);
            } else if (!prevWasUnderscore) {
                out.append('_');
                prevWasUnderscore = true;
                prevWasLowerOrDigit = false;
            }
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '_') out.setLength(len - 1);
        return out.toString();
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

    // ------------------------- Surprise attacks -----------------------------
    /** Damage multiplier applied after normal armour/resist rolls for a surprise hit. */
    public static double RULES_SURPRISE_DAMAGE_MULT = 1.5;
    /** If true, a currently obstructed target-to-attacker LOS can trigger surprise. */
    public static boolean RULES_SURPRISE_SURPRISE_IF_NO_LOS_NOW = true;
    /** If true, the target's turn-start visibility snapshot can trigger surprise. */
    public static boolean RULES_SURPRISE_SURPRISE_IF_NO_LOS_LAST_TURN = true;
    /** If true, physical single-target throws are eligible for surprise. */
    public static boolean RULES_SURPRISE_ALLOW_THROW = true;
    /** If true, every targeted attack type can surprise; otherwise only physical attacks can. */
    public static boolean RULES_SURPRISE_ALLOW_ALL_TARGETED_ATTACK_TYPES = false;

    // ------------------------- AI safety ------------------------------------
    /** Delay charged when an AI turn returns without paying any action or move cost. */
    public static int AI_GUARDRAIL_COST = 150;
    /** Maximum game-clock catch-up work to do in one render frame before yielding. */
    public static double TURN_LOOP_FRAME_BUDGET_MS = 8.0;
    /** Master switch for the SMART mob brain (registered by the {@code rai} module via
     *  {@link MobBrains}). When false, mobs tagged {@link Mob.Behavior#SMART} fall back
     *  to {@link Mob.Behavior#MOB} behaviour. Default true. */
    public static boolean SMART_AI_ENABLED = true;

    // ------------------------- Profiling ------------------------------------
    /** Emit one logcat line for slow PlayScreen render frames. */
    public static boolean PROFILING_ENABLED = true;
    /** Minimum total frame duration before the profiler logs a frame. */
    public static double PROFILING_SLOW_FRAME_MS = 34.0;
    /** Minimum time between profiler log lines, to keep logcat readable. */
    public static long PROFILING_MIN_LOG_GAP_MS = 250;

    // ------------------------- Character progression -------------------------
    /** Hard cap - characters stop leveling at this level even if they accrue more XP. */
    public static int MAX_CHARACTER_LEVEL   = 32;
    /** XP cost to advance from level {@code N} to {@code N+1} = {@code N x XP_PER_LEVEL_STEP}. */
    public static int XP_PER_LEVEL_STEP     = 7;
    /** Perk points granted on each level-up. (Per-stat level deltas are now
     *  per-mob - see the {@code *PerLevel} columns of {@code mobs.csv}.) */
    public static int PERK_POINTS_PER_LEVEL = 1;

    public static int XP_PER_POWER_ORB = 10;

    public static int MANA_PER_PILL = 2;

    // ------------------------- Item-stat scaling factors --------------------
    // Universal per-level increment formula for stats that grow with item
    // level: `scaled = base + N × max(1, base/FACTOR)`. The factor controls
    // how aggressively the increment scales with the base value. Smaller
    // factor → bigger per-level jumps for high-base items.

    /** Divisor for the per-level increment of "amount" stats (damage,
     *  armor, apDamage, magicResist, accuracy, evasion, foodValue,
     *  knockbackSquares, lightRadius, effectDuration, effectRange). At
     *  factor=3 a base-3 stat grows +1/level, a base-6 stat +2/level,
     *  a base-9 stat +3/level. */
    public static int AMOUNT_LEVEL_SCALE_FACTOR = 5;

    /** Divisor for wand / tool {@code baseChargeMax} per-level growth. */
    public static int CHARGEMAX_LEVEL_SCALE_FACTOR = 2;

    /** Divisor for {@code effectSize} per-level growth (bomb / wand AoE,
     *  cloud disc). At factor=3 an effectSize=9 bomb grows +3 tiles per
     *  item level. */
    public static int TILECOUNT_LEVEL_SCALE_FACTOR = 3;

    /** Divisor for damage on BOMB and WAND items: per-effective-level inc =
     *  {@code max(1, damage / BOMB_WAND_DAMAGE_FACTOR)}. Smaller than
     *  {@link #AMOUNT_LEVEL_SCALE_FACTOR} so wand / bomb damage scales harder
     *  with level - they need it to keep up with melee weapons at depth.
     *  Only the damage stat on these categories uses this factor; armor,
     *  accuracy, etc. still flow through the AMOUNT factor. */
    public static int BOMB_WAND_DAMAGE_FACTOR = 3;

    /** Fudge term added per dungeon depth to the cumulative XP estimate
     *  used by the arena gear-picker (and any future depth ↔ character-
     *  level calculator). Covers un-modelled sources: themed-room rewards,
     *  future drop tables, etc. Default 0 — bump if measured-XP per depth
     *  ends up too conservative versus typical playthroughs. */
    public static int MISC_XP_PER_DEPTH = 0;

    /** Maximum per-character-level HP increment for mobs. Caps the
     *  {@code base/AMOUNT_FACTOR} inc so a high-HP boss (e.g. ORC_PRESIDENT
     *  base 53, raw inc 10) doesn't gain absurd HP per level: actual
     *  per-level inc = {@code min(MOB_HP_INC_CAP, max(1, base/factor))}. */
    public static int MOB_HP_INC_CAP = 5;

    /** Sharper {@code effectSize} divisor for the cascade wands
     *  (WAND_WATER / WAND_OIL / WAND_GRASS / WAND_FUNGUS). Factor 2 gives
     *  base=5 → 5+2×lvl, so +0=5 / +5=15 / +10=25 cascade drops. */
    public static int TILECOUNT_CASCADE_FACTOR = 2;

    /** Divisor for {@code knockbackSquares} per-level growth. Larger value
     *  = slower knockback growth than damage / armor. */
    public static int KNOCKBACK_LEVEL_SCALE_FACTOR = 5;

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
    // a fixed increment to the relevant stat via the universal rules above
    // (AMOUNT / TILECOUNT / CHARGEMAX / KNOCKBACK scale factors).

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
     *  {@code DUNGEON_DEPTH} (bottom) are single SHINY CENTER-column levels;
     *  intermediate depths each carry one or two levels picked from the three
     *  themed columns (CONCRETE/west, CRYSTAL/center, GOTHIC/east). */
    public static int DUNGEON_DEPTH = 8;
    /** Per-depth probability that an intermediate depth carries two levels
     *  (any 2 of the 3 themed columns) instead of a single CRYSTAL/center
     *  level. */
    public static double TWO_LEVEL_PROBABILITY = 0.6;
    /** Per-level probability that a level gets a second "diagonal" downstair
     *  to a different-column level at depth+1, in addition to its primary
     *  (same-column-preferred) downstair. */
    public static double DIAGONAL_STAIR_PROBABILITY = 0.25;

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

    // ------------------------- Throw range -----------------------------------
    /** Default Chebyshev throw range for the player targeting overlay. */
    public static int DEFAULT_THROW_RANGE = 6;
    /** Throw range bonus granted per level of the HURLER perk. */
    public static int HURLER_RANGE_PER_LEVEL = 2;

    // ------------------------- Buff / perk tunables --------------------------
    // These knobs used to live as hardcoded constants inside MobSystem and
    // BuffSystem. Moved here so that designers can iterate on balance without
    // recompiling rlib (just edit config.csv). The Java field's baseline
    // value matches the previous hardcoded number exactly - changing the CSV
    // value is the only way to drift balance.

    /** ON_FIRE direct hp damage per standard turn (before magic-resist and
     *  OILY-doubling). Previously a private constant inside BuffSystem. */
    public static int FIRE_DAMAGE_PER_TURN = 8;

    /** BOMB_DODGER perk: incoming bomb damage is multiplied by
     *  {@code BOMB_DODGER_DAMAGE_BASE^perkLevel} (asymptotic - lvl=1 → 0.5,
     *  lvl=2 → 0.25, ... lvl=10 → ~0.001). 1.0 disables the reduction. */
    public static double BOMB_DODGER_DAMAGE_BASE = 0.5;

    /** KNOCKBACK perk: maximum direct tile contribution to the knockback
     *  distance. Perk levels at or below this value add 1 tile each; levels
     *  above add a wall-slam damage bonus instead. Previously a magic 5 in
     *  MobSystem.attack. */
    public static int KNOCKBACK_TILE_CAP = 5;
}
