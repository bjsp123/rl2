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
 * carries a baked-in baseline that takes effect if the config.csv is missing or omits
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
    /** Dedicated RNG for the Monte-Carlo {@link #mobfight} estimator, registered
     *  so it reseeds deterministically per run. It was a fresh {@code new
     *  Random()} per call - and since the AI calls mobfight to compare threats,
     *  that leaked wall-clock randomness into decisions every tick. */
    private static final Random MOBFIGHT_RNG =
            com.bjsp123.rl2.util.SimRng.register("GameBalance.mobfight", new Random());

    public static double mobfight(Mob a, Mob b) {
        return mobfight(a, b, MOBFIGHT_DEFAULT_TRIALS);
    }

    /** {@link #mobfight(Mob, Mob)} with an explicit trial count. */
    public static double mobfight(Mob a, Mob b, int trials) {
        if (a == null || b == null || trials <= 0) return 0.0;
        Random rng = MOBFIGHT_RNG;
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
    /** If true, a mob standing in a smoke cloud is concealed from a viewer who
     *  can't peer into that tile with KEEN_SIGHT - so a smoke plume both blocks
     *  sight through it AND hides whoever is fighting inside it, enabling surprise
     *  attacks against (and from) foes blinded by the smoke. */
    public static boolean RULES_SURPRISE_SMOKE_CONCEALS = true;

    // ------------------------- Difficulty -----------------------------------
    /** Selected difficulty for the active run. Set via {@link #applyDifficulty}
     *  at run start (and reset to NORMAL when leaving a game). Game logic reads
     *  the "active" multiplier fields below, not this enum directly. */
    public static Difficulty difficulty = Difficulty.NORMAL;

    // Active multipliers the game logic reads (populated by applyDifficulty);
    // baked defaults equal NORMAL so non-game contexts (autoplay, menus) are unaffected.
    /** Player max-HP multiplier (applied in MobStats for the player mob). */
    public static double PLAYER_HP_MULTIPLIER = 1.0;
    /** Enemy max-HP multiplier (applied in MobStats for non-player mobs). */
    public static double ENEMY_HP_MULTIPLIER = 1.0;
    /** Fraction of the player's max HP regenerated per standard turn (MobStats). */
    public static double PLAYER_REGEN_FRAC_PER_TURN = 0.0;
    /** Renewing-spawn cadence multiplier; >1 = fresh enemies arrive less often (TurnSystem). */
    public static double SPAWN_CADENCE_MULTIPLIER = 1.0;
    /** Player movement-speed multiplier; >1 = faster (lower move cost) (MobStats). */
    public static double PLAYER_SPEED_MULTIPLIER = 1.0;
    /** When true, jade items (fish/crab/bull) don't consume charges on use
     *  (ItemSystem). Set only by the SUPEREASY tier. */
    public static boolean JADE_ITEMS_FREE_CHARGES = false;
    /** Jade Peach revive charms granted to the player at run start. */
    public static int STARTING_REVIVE_CHARMS = 0;
    /** Fraction of max HP the player is restored to when a revive charm fires. */
    public static double REVIVE_HP_RESTORE_FRAC = 1.0;
    /** Fraction of each hostile's max HP dealt by the revive shockwave. */
    public static double REVIVE_AOE_MAXHP_FRAC = 0.20;

    // Per-difficulty presets (config.csv-tunable). applyDifficulty copies the
    // chosen level's values into the active fields above.
    public static double DIFFICULTY_EASY_PLAYER_HP_MULT      = 2.0;
    public static double DIFFICULTY_EASY_ENEMY_HP_MULT       = 1.0;
    public static double DIFFICULTY_EASY_REGEN_FRAC          = 0.015;
    public static double DIFFICULTY_EASY_SPAWN_CADENCE_MULT  = 2.0;
    public static double DIFFICULTY_EASY_SPEED_MULT          = 1.2;
    public static int    DIFFICULTY_EASY_REVIVE_CHARMS       = 8;

    // Gentle sits midway between Easy and Normal on every axis.
    public static double DIFFICULTY_GENTLE_PLAYER_HP_MULT     = 1.5;
    public static double DIFFICULTY_GENTLE_ENEMY_HP_MULT      = 1.0;
    public static double DIFFICULTY_GENTLE_REGEN_FRAC         = 0.005;
    public static double DIFFICULTY_GENTLE_SPAWN_CADENCE_MULT = 1.5;
    public static double DIFFICULTY_GENTLE_SPEED_MULT         = 1.1;
    public static int    DIFFICULTY_GENTLE_REVIVE_CHARMS      = 4;

    public static double DIFFICULTY_NORMAL_PLAYER_HP_MULT     = 1.0;
    public static double DIFFICULTY_NORMAL_ENEMY_HP_MULT      = 1.0;
    public static double DIFFICULTY_NORMAL_REGEN_FRAC         = 0.0;
    public static double DIFFICULTY_NORMAL_SPAWN_CADENCE_MULT = 1.0;
    public static double DIFFICULTY_NORMAL_SPEED_MULT         = 1.0;
    public static int    DIFFICULTY_NORMAL_REVIVE_CHARMS      = 0;

    public static double DIFFICULTY_HARD_PLAYER_HP_MULT       = 1.0;
    public static double DIFFICULTY_HARD_ENEMY_HP_MULT        = 1.3;
    public static double DIFFICULTY_HARD_REGEN_FRAC           = 0.0;
    public static double DIFFICULTY_HARD_SPAWN_CADENCE_MULT   = 1.0;
    public static double DIFFICULTY_HARD_SPEED_MULT           = 1.0;
    public static int    DIFFICULTY_HARD_REVIVE_CHARMS        = 0;

    public static double DIFFICULTY_VERY_HARD_PLAYER_HP_MULT     = 1.0;
    public static double DIFFICULTY_VERY_HARD_ENEMY_HP_MULT      = 1.5;
    public static double DIFFICULTY_VERY_HARD_REGEN_FRAC         = 0.0;
    public static double DIFFICULTY_VERY_HARD_SPAWN_CADENCE_MULT = 0.5;
    public static double DIFFICULTY_VERY_HARD_SPEED_MULT         = 1.0;
    public static int    DIFFICULTY_VERY_HARD_REVIVE_CHARMS      = 0;

    // SuperEasy (debug): like Easy but triple HP and free jade charges.
    public static double DIFFICULTY_SUPEREASY_PLAYER_HP_MULT      = 3.0;
    public static double DIFFICULTY_SUPEREASY_ENEMY_HP_MULT       = 1.0;
    public static double DIFFICULTY_SUPEREASY_REGEN_FRAC          = 0.015;
    public static double DIFFICULTY_SUPEREASY_SPAWN_CADENCE_MULT  = 2.0;
    public static double DIFFICULTY_SUPEREASY_SPEED_MULT          = 1.2;
    public static int    DIFFICULTY_SUPEREASY_REVIVE_CHARMS       = 8;

    /** The five selectable difficulty levels. Display text comes from
     *  {@link TextCatalog} (keys {@code difficulty.<lower>.name} /
     *  {@code .description}); the numbers live in the {@code DIFFICULTY_*}
     *  fields above so they stay config-tunable. */
    public enum Difficulty {
        EASY, GENTLE, NORMAL, HARD, VERY_HARD,
        /** Debug / playtesting tier - NOT shown in the character-select cycle.
         *  Like Easy but triple HP and jade items don't consume charges. Listed
         *  last so the menu difficulties keep their ordinals (seed stability). */
        SUPEREASY;

        /** False for difficulties hidden from the character-select UI cycle. */
        public boolean menuSelectable() { return this != SUPEREASY; }

        public String displayName() {
            return TextCatalog.get("difficulty." + name().toLowerCase(java.util.Locale.ROOT) + ".name");
        }
        public String description() {
            return TextCatalog.get("difficulty." + name().toLowerCase(java.util.Locale.ROOT) + ".description");
        }
    }

    /** Copy {@code d}'s preset numbers into the active multiplier fields and set
     *  {@link #difficulty}. Call at run start (new and loaded games); call with
     *  {@link Difficulty#NORMAL} to reset when returning to menus so preview mobs
     *  aren't scaled by the last run's difficulty. */
    public static void applyDifficulty(Difficulty d) {
        if (d == null) d = Difficulty.NORMAL;
        difficulty = d;
        switch (d) {
            case EASY -> setActive(DIFFICULTY_EASY_PLAYER_HP_MULT, DIFFICULTY_EASY_ENEMY_HP_MULT,
                    DIFFICULTY_EASY_REGEN_FRAC, DIFFICULTY_EASY_SPAWN_CADENCE_MULT,
                    DIFFICULTY_EASY_SPEED_MULT, DIFFICULTY_EASY_REVIVE_CHARMS, false);
            case GENTLE -> setActive(DIFFICULTY_GENTLE_PLAYER_HP_MULT, DIFFICULTY_GENTLE_ENEMY_HP_MULT,
                    DIFFICULTY_GENTLE_REGEN_FRAC, DIFFICULTY_GENTLE_SPAWN_CADENCE_MULT,
                    DIFFICULTY_GENTLE_SPEED_MULT, DIFFICULTY_GENTLE_REVIVE_CHARMS, false);
            case NORMAL -> setActive(DIFFICULTY_NORMAL_PLAYER_HP_MULT, DIFFICULTY_NORMAL_ENEMY_HP_MULT,
                    DIFFICULTY_NORMAL_REGEN_FRAC, DIFFICULTY_NORMAL_SPAWN_CADENCE_MULT,
                    DIFFICULTY_NORMAL_SPEED_MULT, DIFFICULTY_NORMAL_REVIVE_CHARMS, false);
            case HARD -> setActive(DIFFICULTY_HARD_PLAYER_HP_MULT, DIFFICULTY_HARD_ENEMY_HP_MULT,
                    DIFFICULTY_HARD_REGEN_FRAC, DIFFICULTY_HARD_SPAWN_CADENCE_MULT,
                    DIFFICULTY_HARD_SPEED_MULT, DIFFICULTY_HARD_REVIVE_CHARMS, false);
            case VERY_HARD -> setActive(DIFFICULTY_VERY_HARD_PLAYER_HP_MULT, DIFFICULTY_VERY_HARD_ENEMY_HP_MULT,
                    DIFFICULTY_VERY_HARD_REGEN_FRAC, DIFFICULTY_VERY_HARD_SPAWN_CADENCE_MULT,
                    DIFFICULTY_VERY_HARD_SPEED_MULT, DIFFICULTY_VERY_HARD_REVIVE_CHARMS, false);
            case SUPEREASY -> setActive(DIFFICULTY_SUPEREASY_PLAYER_HP_MULT, DIFFICULTY_SUPEREASY_ENEMY_HP_MULT,
                    DIFFICULTY_SUPEREASY_REGEN_FRAC, DIFFICULTY_SUPEREASY_SPAWN_CADENCE_MULT,
                    DIFFICULTY_SUPEREASY_SPEED_MULT, DIFFICULTY_SUPEREASY_REVIVE_CHARMS, true);
        }
    }

    private static void setActive(double playerHp, double enemyHp, double regen,
                                  double cadence, double speed, int charms, boolean jadeFree) {
        PLAYER_HP_MULTIPLIER       = playerHp;
        ENEMY_HP_MULTIPLIER        = enemyHp;
        PLAYER_REGEN_FRAC_PER_TURN = regen;
        SPAWN_CADENCE_MULTIPLIER   = cadence;
        PLAYER_SPEED_MULTIPLIER    = speed;
        STARTING_REVIVE_CHARMS     = charms;
        JADE_ITEMS_FREE_CHARGES    = jadeFree;
    }

    // ------------------------- Low-HP warning thresholds --------------------
    /** HP fraction at which the HUD chrome starts tinting red. Linear ramp
     *  from this value down to 0.01 (fully red). Read by both rgame's HUD
     *  and the player-damage hook in Animator. */
    public static double LOW_HP_RAMP_START = 0.25;
    /** HP fraction at which a damaging hit on the player fires the
     *  full-screen flash + warning sfx. Computed POST-hit. */
    public static double LOW_HP_HIT_FLASH_THRESHOLD = 0.20;

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

    // ------------------------- Renewing enemies (RL-54) ----------------------
    /** Base standard turns between renewing-enemy spawns; each hazard point
     *  reduces it by 2 (floored at 1). */
    public static int RENEWING_SPAWN_CADENCE = 50;
    /** Base cap of living hostiles before renewing spawns pause; each hazard
     *  point raises it by 1. */
    public static int RENEWING_ENEMY_CAP = 8;
    /** Maximum hazard level a floor can reach. */
    public static int HAZARD_MAX = 5;
    /** Standard turns spent on a floor per +1 hazard. */
    public static int HAZARD_TURNS_PER_POINT = 2000;

    // ------------------------- Final boss (RL-19) ----------------------------
    /** Great Wraith spawn level with zero beacons lit. */
    public static int BOSS_BASE_LEVEL = 18;
    /** Extra boss spawn-level per beacon lit (capped at MAX_CHARACTER_LEVEL). */
    public static int BOSS_LEVEL_PER_BEACON = 1;
    /** The boss gains one extra ability per this many beacons lit. */
    public static int BOSS_ABILITY_PER_BEACONS = 3;
    /** Standard turns between revenant-add spawns on the boss floor. */
    public static int BOSS_ADD_SPAWN_CADENCE = 6;
    /** Max live revenant adds before the add-spawner pauses. */
    public static int BOSS_ADD_MAX_ALIVE = 8;
    /** Cap on the total reanimated kills reproduced over the fight (0 = all). */
    public static int BOSS_ADD_TOTAL_CAP = 0;

    // ------------------------- Victory score (RL-19) -------------------------
    /** Base score for any victory - far above any death (deaths score 0). */
    public static int VICTORY_SCORE_BASE = 10000;
    /** Score per beacon lit over the run. */
    public static int SCORE_PER_BEACON = 500;
    /** Bonus for a perfect victory (all beacons lit + boss slain). */
    public static int PERFECT_VICTORY_BONUS = 5000;

    // ------------------------- Gem recycle (RL-50) ---------------------------
    /** Expected gems from recycling an item = {@code RECYCLE_BASE_GEMS +
     *  power * RECYCLE_GEMS_PER_POWER}, rolled stochastically. Base is the
     *  power-0 yield (~0.25 = a 1-in-4 chance of a single gem). */
    public static double RECYCLE_BASE_GEMS = 0.25;
    /** Extra expected gems added at full item power. */
    public static double RECYCLE_GEMS_PER_POWER = 2.75;
    /** Hard cap on gems from a single recycle. */
    public static int RECYCLE_MAX_GEMS = 4;

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

    // ------------------------- Fire ------------------------------------------
    // Migrated from FireSystem. Tunables now live here / in config.csv.

    /** Initial fire lifetime range, in <b>game ticks</b>. ~300 ticks per the spec, with a
     *  small jitter so adjacent ignited tiles don't burn out in lockstep. */
    public static int FIRE_DURATION_MIN_TICKS = 250;
    public static int FIRE_DURATION_MAX_TICKS = 350;

    /** Fire lifetime when the cell being ignited holds a {@code Vegetation#TREES} tile -
     *  large trunks burn for a long time once they catch, so the duration is roughly 4x a
     *  standard tile fire. Also gives the fire enough time to walk a forest from one tree
     *  to the next at the standard spread rate. */
    public static int FIRE_DURATION_TREE_MIN_TICKS = 1000;
    public static int FIRE_DURATION_TREE_MAX_TICKS = 1400;

    /** A fire tile emits one ember every this many milliseconds of <b>real time</b>.
     *  Particle emission is decoupled from the game-tick clock so embers keep streaming
     *  while the player is sitting on a tile thinking. */
    public static int FIRE_PARTICLE_INTERVAL_MS = 500;

    /** Mobs on fire take {@link #FIRE_DAMAGE_PER_INTERVAL} damage every this many
     *  <b>game ticks</b>. */
    public static int FIRE_DAMAGE_INTERVAL_TICKS = 100;
    public static int FIRE_DAMAGE_PER_INTERVAL   = 8;

    /** Per-turn chance that a fire tile spreads to a chosen empty-floor neighbour. Bumped
     *  from 0.01 - the old rate was so slow fire effectively died out before reaching the
     *  next tile. 0.05 gives a modest creep across bare floor (~20 turns to traverse a
     *  cell on average); vegetation and oil paths are much faster. */
    public static double SPREAD_CHANCE_BARE       = 0.1;
    /** Per-turn chance that a fire tile spreads to a grass neighbour, replacing it. Grass
     *  is the most flammable kind of vegetation - bumped up alongside oil so wildfires
     *  through a meadow read as fast as wildfires through a slick. */
    public static double SPREAD_CHANCE_GRASS      = 1.00;
    /** Per-turn chance that a fire tile spreads to a non-grass vegetation neighbour
     *  (mushrooms, trees), replacing it. Slower than grass - trunks and damp fungus take
     *  longer to catch even though they burn well once lit. */
    public static double SPREAD_CHANCE_VEGETATION = 0.70;
    /** Per-turn chance that a fire tile spreads to an oil neighbour, removing the oil. */
    public static double SPREAD_CHANCE_OIL        = 1.00;

    // ------------------------- Clouds ----------------------------------------
    // Migrated from CloudSystem. Tunables now live here / in config.csv.

    /** Maximum cloud duration in standard turns. {@code CloudSystem.addCloud} clamps to
     *  this; saturation behaviour matches the engine's expectation that no
     *  cloud lingers beyond a dozen turns regardless of stacking. */
    public static int MAX_DURATION = 12;

    /** Per-tile per-turn chance a cloud with duration >= 2 spreads to a
     *  neighbour. */
    public static double SPREAD_CHANCE = 0.30;
    /** Per-fire-tile per-turn chance to emit a smoke cloud (duration 5). */
    public static double SMOKE_EMIT_CHANCE = 0.25;
    /** Smoke duration emitted by a fire tile. */
    public static int SMOKE_EMIT_DURATION = 5;
    /** Per-water-tile per-turn chance to emit a steam cloud (duration 3),
     *  conditional on adjacent fire. */
    public static double STEAM_EMIT_CHANCE = 0.50;
    /** Steam duration emitted by a water tile next to fire. */
    public static int STEAM_EMIT_DURATION = 3;

    /** POISONED stacks applied per poison-cloud tick. Small - the cloud re-applies it
     *  next turn if the mob is still standing in it. */
    public static int POISON_CLOUD_STACKS = 2;

    // ------------------------- Vegetation ------------------------------------
    // Migrated from VegetationSystem. Tunables now live here / in config.csv.

    /** Per-turn chance that a lit mushroom tile withers away and becomes bare floor. */
    public static double MUSHROOM_LIT_DECAY_CHANCE = 0.01;
    /** Per-turn chance that a grass/mushroom tile sitting on an OIL surface withers.
     *  Oil-doused vegetation rots fast - moderate per-tile chance, so a thrown
     *  oil-splash bomb visibly clears flora over the next handful of turns. */
    public static double OIL_DECAY_CHANCE = 0.05;

    // ------------------------- Gems (RL-47) ----------------------------------

    /** Average gems generated per level, by rarity class. A non-integer average rolls the
     *  fractional part as a chance of one extra (e.g. 0.75 -> 0 a quarter of the time, 1
     *  otherwise). Tunable in config.csv. */
    public static double GEMS_BASIC_AVG  = 2.0;
    public static double GEMS_METAL_AVG  = 0.75;
    public static double GEMS_EXOTIC_AVG = 0.3;

    /** Relative class weights for a generic (class-agnostic) gem roll - the
     *  {@code LootCategory.GEM} reference used by ANY scatter and themed-room GEM cells.
     *  Basic common, metal rare, exotic very rare. */
    public static int GEM_WEIGHT_BASIC  = 70;
    public static int GEM_WEIGHT_METAL  = 25;
    public static int GEM_WEIGHT_EXOTIC = 5;
}
