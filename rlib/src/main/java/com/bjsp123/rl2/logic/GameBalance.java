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

    /** Read {@code assets/data/config.csv} rows with kind=gamebalance and override the
     *  matching {@code public static} field on this class. Each {@code key} must be the
     *  exact field name (canonical {@code UPPER_SNAKE}); unknown keys are ignored and
     *  missing keys keep their Java-side baseline. Handles {@code int}, {@code double},
     *  {@code long}, and {@code boolean} fields. Rows with kind=difficulty instead
     *  override one per-tier tuning value (see {@link #loadDifficultyRow}). Call once
     *  at startup, before gameplay code reads any of these fields. */
    public static void load(String text) {
        if (text == null || text.isEmpty()) return;
        CsvTable table = CsvTable.parse(text);
        for (java.util.Map<String, String> row : table.rows) {
            String kind = CsvTable.str(row, "kind", "");
            String key = CsvTable.str(row, "key", "");
            String value = CsvTable.str(row, "value", "").trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            if ("difficulty".equals(kind)) {
                loadDifficultyRow(key, value);
                continue;
            }
            if (!"gamebalance".equals(kind)) continue;
            try {
                Field f = GameBalance.class.getDeclaredField(key);
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) continue;
                Class<?> t = f.getType();
                // Boxed Field.set (not setInt/setDouble/...): the primitive
                // setters are missing from TeaVM's reflection emulation, and
                // Field.set auto-unboxes onto primitive fields on every runtime.
                if (t == int.class)         f.set(null, Integer.parseInt(value));
                else if (t == double.class) f.set(null, Double.parseDouble(value));
                else if (t == long.class)   f.set(null, Long.parseLong(value));
                else if (t == boolean.class) f.set(null, Boolean.parseBoolean(value));
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
        com.bjsp123.rl2.model.MinMax aDmg = MobCombat.rawDamageRange(a);
        com.bjsp123.rl2.model.MinMax bDmg = MobCombat.rawDamageRange(b);
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

    // ------------------------- To-hit ---------------------------------------
    /** Minimum to-hit probability, applied AFTER the accuracy/evasion ratio.
     *  Guarantees even a heavily out-evaded attacker lands ~1 in 10, so a
     *  high-evasion foe can never be a total wall that decides a fight by pure
     *  whiff-streak. Surprise auto-hits are unaffected (already 100%). */
    public static double RULES_HIT_CHANCE_FLOOR = 0.10;

    // ------------------------- Surprise attacks -----------------------------
    /** Damage multiplier applied after normal armour/resist rolls for a surprise hit. */
    public static double RULES_SURPRISE_DAMAGE_MULT = 1.5;
    /** If true, a currently obstructed target-to-attacker LOS can trigger surprise. */
    public static boolean RULES_SURPRISE_SURPRISE_IF_NO_LOS_NOW = true;
    /** If true, the target's turn-start visibility snapshot can trigger surprise. */
    public static boolean RULES_SURPRISE_SURPRISE_IF_NO_LOS_LAST_TURN = true;
    /** If true, physical single-target throws are eligible for surprise. */
    public static boolean RULES_SURPRISE_ALLOW_THROW = true;
    /** If true, every targeted attack type can surprise; otherwise only PHYSICAL-element
     *  melee / ranged / thrown attacks can. OFF by design: magic never surprises, so an
     *  unseen caster can't land a guaranteed, amplified, un-dodgeable opening hit. */
    public static boolean RULES_SURPRISE_ALLOW_ALL_TARGETED_ATTACK_TYPES = false;
    /** If true, a mob standing in a smoke cloud is concealed from a viewer who
     *  can't peer into that tile with KEEN_SIGHT - so a smoke plume both blocks
     *  sight through it AND hides whoever is fighting inside it, enabling surprise
     *  attacks against (and from) foes blinded by the smoke. */
    public static boolean RULES_SURPRISE_SMOKE_CONCEALS = true;

    // ------------------------- Difficulty -----------------------------------
    /** Selected difficulty for the active run. Set via {@link #applyDifficulty}
     *  at run start (and reset to NORMAL when leaving a game). Game logic reads
     *  the active run's numbers via {@link #tuning()}. */
    public static Difficulty difficulty = Difficulty.NORMAL;

    /** Fraction of max HP the player is restored to when a revive charm fires. */
    public static double REVIVE_HP_RESTORE_FRAC = 1.0;
    /** Fraction of each hostile's max HP dealt by the revive shockwave. */
    public static double REVIVE_AOE_MAXHP_FRAC = 0.20;

    /** The five selectable difficulty levels. Display text comes from
     *  {@link TextCatalog} (keys {@code difficulty.<lower>.name} /
     *  {@code .description}); the numbers live in the {@code TUNING} table
     *  below, config.csv-tunable via {@code difficulty}-kind rows. */
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

    /**
     * Every per-difficulty knob bundled into one immutable value. Accessor
     * names spell out exactly what each number is - {@code ...Mult} for a
     * multiplier, {@code ...FracPerTurn} for a per-turn fraction - so a caller
     * can never mistake a multiplier for a raw stat.
     */
    public record DifficultyTuning(
            /** Player max-HP multiplier (applied in MobStats for the player mob). */
            double  playerHpMult,
            /** Enemy max-HP multiplier (applied in MobStats for non-player mobs). */
            double  enemyHpMult,
            /** Fraction of the player's max HP regenerated per standard turn (MobStats). */
            double  regenFracPerTurn,
            /** Renewing-spawn cadence multiplier; >1 = fresh enemies arrive less often (TurnSystem). */
            double  spawnCadenceMult,
            /** Player movement-speed multiplier; >1 = faster (lower move cost) (MobStats). */
            double  playerSpeedMult,
            /** Jade Peach revive charms granted to the player at run start. */
            int     startingReviveCharms,
            /** When true, jade items (fish/crab/bull) don't consume charges on use (ItemSystem). */
            boolean jadeItemsFreeCharges,
            /** Run-score coefficient for this tier. */
            double  scoreMult) {}

    /** The single source of truth for every difficulty number: one row per tier,
     *  seeded with the baked defaults below and overridable from config.csv rows
     *  of kind {@code difficulty} with key {@code <TIER>.<FIELD>} - e.g.
     *  {@code difficulty,EASY.PLAYER_HP_MULT,2.0} (see {@link #load}). */
    private static final java.util.EnumMap<Difficulty, DifficultyTuning> TUNING =
            new java.util.EnumMap<>(Difficulty.class);
    static {
        //                                              playerHp enemyHp regen/turn spawn speed charms jadeFree score
        TUNING.put(Difficulty.EASY,      new DifficultyTuning(2.0, 1.0, 0.015, 2.0, 1.2, 8, false, 0.5));
        // Gentle sits midway between Easy and Normal on every axis.
        TUNING.put(Difficulty.GENTLE,    new DifficultyTuning(1.5, 1.0, 0.005, 1.5, 1.1, 4, false, 0.75));
        TUNING.put(Difficulty.NORMAL,    new DifficultyTuning(1.0, 1.0, 0.0,   1.0, 1.0, 0, false, 1.0));
        TUNING.put(Difficulty.HARD,      new DifficultyTuning(1.0, 1.3, 0.0,   1.0, 1.0, 0, false, 1.5));
        TUNING.put(Difficulty.VERY_HARD, new DifficultyTuning(1.0, 1.5, 0.0,   0.5, 1.0, 0, false, 2.0));
        // SuperEasy (debug): like Easy but triple HP and free jade charges.
        TUNING.put(Difficulty.SUPEREASY, new DifficultyTuning(3.0, 1.0, 0.015, 2.0, 1.2, 8, true,  0.25));
    }

    /** The {@link DifficultyTuning} row for {@code d} (null -&gt; NORMAL). */
    public static DifficultyTuning tuningFor(Difficulty d) {
        return TUNING.get(d == null ? Difficulty.NORMAL : d);
    }

    /** Tuning for the active run's {@link #difficulty} - the single read point
     *  for game logic (MobStats, TurnSystem, ItemSystem, run start). Baked
     *  default is NORMAL so non-game contexts (autoplay, menus) are unaffected. */
    public static DifficultyTuning tuning() {
        return tuningFor(difficulty);
    }

    /** Set the active {@link #difficulty}. Call at run start (new and loaded
     *  games); call with {@link Difficulty#NORMAL} to reset when returning to
     *  menus so preview mobs aren't scaled by the last run's difficulty. */
    public static void applyDifficulty(Difficulty d) {
        difficulty = (d == null) ? Difficulty.NORMAL : d;
    }

    /** Apply one config.csv {@code difficulty}-kind row: key is
     *  {@code <TIER>.<FIELD>}, value the replacement number/flag. Hand-edited
     *  CSV rule: an unknown tier or field, or a malformed value, logs a warning
     *  and keeps the baseline - never throws. */
    private static void loadDifficultyRow(String key, String value) {
        int dot = key.indexOf('.');
        String tierName = dot > 0 ? key.substring(0, dot) : key;
        String field    = dot > 0 ? key.substring(dot + 1) : "";
        Difficulty tier;
        try {
            tier = Difficulty.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            System.err.println("[csv] difficulty row: unknown tier '" + tierName + "' - row skipped");
            return;
        }
        DifficultyTuning t = TUNING.get(tier);
        try {
            DifficultyTuning nt = switch (field) {
                case "PLAYER_HP_MULT" -> new DifficultyTuning(Double.parseDouble(value),
                        t.enemyHpMult(), t.regenFracPerTurn(), t.spawnCadenceMult(), t.playerSpeedMult(),
                        t.startingReviveCharms(), t.jadeItemsFreeCharges(), t.scoreMult());
                case "ENEMY_HP_MULT" -> new DifficultyTuning(t.playerHpMult(), Double.parseDouble(value),
                        t.regenFracPerTurn(), t.spawnCadenceMult(), t.playerSpeedMult(),
                        t.startingReviveCharms(), t.jadeItemsFreeCharges(), t.scoreMult());
                case "REGEN_FRAC" -> new DifficultyTuning(t.playerHpMult(), t.enemyHpMult(),
                        Double.parseDouble(value), t.spawnCadenceMult(), t.playerSpeedMult(),
                        t.startingReviveCharms(), t.jadeItemsFreeCharges(), t.scoreMult());
                case "SPAWN_CADENCE_MULT" -> new DifficultyTuning(t.playerHpMult(), t.enemyHpMult(),
                        t.regenFracPerTurn(), Double.parseDouble(value), t.playerSpeedMult(),
                        t.startingReviveCharms(), t.jadeItemsFreeCharges(), t.scoreMult());
                case "SPEED_MULT" -> new DifficultyTuning(t.playerHpMult(), t.enemyHpMult(),
                        t.regenFracPerTurn(), t.spawnCadenceMult(), Double.parseDouble(value),
                        t.startingReviveCharms(), t.jadeItemsFreeCharges(), t.scoreMult());
                case "REVIVE_CHARMS" -> new DifficultyTuning(t.playerHpMult(), t.enemyHpMult(),
                        t.regenFracPerTurn(), t.spawnCadenceMult(), t.playerSpeedMult(),
                        Integer.parseInt(value), t.jadeItemsFreeCharges(), t.scoreMult());
                case "JADE_FREE_CHARGES" -> new DifficultyTuning(t.playerHpMult(), t.enemyHpMult(),
                        t.regenFracPerTurn(), t.spawnCadenceMult(), t.playerSpeedMult(),
                        t.startingReviveCharms(), Boolean.parseBoolean(value), t.scoreMult());
                case "SCORE_MULT" -> new DifficultyTuning(t.playerHpMult(), t.enemyHpMult(),
                        t.regenFracPerTurn(), t.spawnCadenceMult(), t.playerSpeedMult(),
                        t.startingReviveCharms(), t.jadeItemsFreeCharges(), Double.parseDouble(value));
                default -> null;
            };
            if (nt == null) {
                System.err.println("[csv] difficulty row: unknown field '" + field + "' - row skipped");
            } else {
                TUNING.put(tier, nt);
            }
        } catch (NumberFormatException e) {
            System.err.println("[csv] difficulty row " + key + ": bad value '" + value + "' - row skipped");
        }
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
    /** Level cap for a perk the class did NOT start with points in. Resolved
     *  per (mob, perk) by {@link MobProgression#perkCap}; the perk-spend UI
     *  and the autoplay spender both stop at the resolved cap. Tunable from
     *  {@code config.csv}. */
    public static int PERK_CAP_OPEN = 5;
    /** Level cap for a class-signature perk (one the class starts with points
     *  in, via {@code startingPerks} in mobs.csv). Tunable from
     *  {@code config.csv}. */
    public static int PERK_CAP_SIGNATURE = 8;

    public static int XP_PER_POWER_ORB = 10;

    public static int MANA_PER_PILL = 2;

    // ------------------------- Item-stat scaling factors --------------------
    // Universal per-level increment formula for stats that grow with item
    // level: `scaled = base + N × max(1, base/FACTOR)`. The factor controls
    // how aggressively the increment scales with the base value. Smaller
    // factor → bigger per-level jumps for high-base items.

    /** Divisor for the per-level increment of "amount" stats (damage,
     *  armor, apDamage, magicResist, accuracy, evasion,
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
    /** Maximum bag slots for consumables (FOOD + POTION combined). Identical
     *  food / potions at the same level merge into a single counted stack. */
    public static int BAG_CONSUMABLE_SIZE = 20;
    /** Maximum bag slots for the remaining throwable / tool items (BOMB, ORB,
     *  THROWN). Bombs at the same type + level merge into stacks; orbs are
     *  always singletons. */
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
    /** Per-depth probability that an intermediate depth carries exactly two
     *  levels (a random 2 of the 3 themed columns - one theme missing).
     *  {@code THREE_LEVEL_PROBABILITY} carries all three; the remaining
     *  probability ({@code 1 - two - three}) yields a single random-column
     *  level. */
    public static double TWO_LEVEL_PROBABILITY = 0.6;
    /** Per-depth probability that an intermediate depth carries all three
     *  themed columns (one level each). See {@link #TWO_LEVEL_PROBABILITY}. */
    public static double THREE_LEVEL_PROBABILITY = 0.1;
    /** Per-level probability that a level gets a second "diagonal" downstair
     *  to a different-column level at depth+1, in addition to its primary
     *  (same-column-preferred) downstair. */
    public static double DIAGONAL_STAIR_PROBABILITY = 0.25;

    // ------------------------- Level population ------------------------------
    /** Base hostile-mob target when populating a fresh level. Actual count is
     *  {@code STARTING_MOBS_PER_LEVEL + rng(4)}, so each level starts with
     *  this many to {@code this + 3} hostiles. */
    public static int STARTING_MOBS_PER_LEVEL = 7;
    /** Extra starting hostiles at full depth: the initial population target
     *  gains {@code round(depthFraction * MOBS_PER_LEVEL_DEPTH_BONUS)} mobs,
     *  so deep floors open crowded while the surface stays gentle. */
    public static int MOBS_PER_LEVEL_DEPTH_BONUS = 4;
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
    /** Number of pills yielded per POWERUPS drop pick - powerups come as a pair
     *  since individual pills are minor. One {@code dropAmount} unit that lands
     *  on POWERUPS therefore drops this many pills. */
    public static int POWERUPS_PER_DROP = 2;

    // ------------------------- Mob spawn-level scaling -----------------------
    // Mobs spawned anywhere in the dungeon scale up with depth via the same
    // rule: a mob spawning at the depth that matches its {@code minPowerLevel}
    // is level 1; for every {@link #MOB_DEPTH_LEVEL_SCALE} of depth-fraction
    // above its min the mob gains one level, capped at
    // {@link #MAX_MOB_DEPTH_LEVEL_SCALE} extra levels. Replaces the old
    // {@code 1 + level.depth} rule, which over-leveled mobs deep in the dungeon
    // because every spawn jumped by the floor's full depth regardless of how
    // far the mob's own power band reached.
    public static double MOB_DEPTH_LEVEL_SCALE     = 0.3;
    public static int    MAX_MOB_DEPTH_LEVEL_SCALE = 3;

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
    /** Standard turns spent on a floor per +1 hazard. 400 is fast enough that
     *  lingering to strip a floor bare is a real risk/reward decision, without
     *  making environmental/renewing-spawn pressure a top-tier killer. */
    public static int HAZARD_TURNS_PER_POINT = 400;

    // ------------------------- Final boss (RL-19) ----------------------------
    /** Great Wraith spawn level with zero beacons lit. */
    public static int BOSS_BASE_LEVEL = 18;
    /** Extra boss spawn-level per beacon lit (capped at MAX_CHARACTER_LEVEL). */
    public static int BOSS_LEVEL_PER_BEACON = 1;
    /** The boss gains one extra ability per this many beacon spirits. */
    public static int BOSS_ABILITY_PER_BEACONS = 3;
    /** Chance that a landed hit on the Great Wraith shatters one beacon spirit
     *  (each spirit destroyed weakens the boss by one beacon's worth of power). */
    public static double BOSS_SPIRIT_DESTROY_CHANCE = 0.5;
    // Boss-floor revenant adds. The floor's hazardLevel is set from the player's
    // total kills on arrival (kills -> hazard, below), and the hazard indexes a
    // lookup of spawn intervals. Boss hazard ranges 0..BOSS_HAZARD_MAX (7), wider
    // than the normal HAZARD_MAX (5); regular floors are unaffected.
    /** Kills at/below which boss hazard is 0 (the slow end). */
    public static int BOSS_HAZARD_KILL_FLOOR = 50;
    /** Kills per +1 boss hazard above {@link #BOSS_HAZARD_KILL_FLOOR}. */
    public static int BOSS_HAZARD_KILLS_PER_POINT = 10;
    /** Boss-floor hazard cap (distinct from the normal-floor {@link #HAZARD_MAX}).
     *  Must be < {@link #BOSS_ADD_CADENCE_BY_HAZARD}.length. */
    public static int BOSS_HAZARD_MAX = 7;
    /** Standard turns between revenant-add spawns, indexed by boss hazard 0..7.
     *  Kills 0-49 -> hz0 (12), 50-59 -> hz1 (10), ... 110+ -> hz7 (2). Arrays are
     *  NOT config.csv-overridable - tune this table in code. */
    public static int[] BOSS_ADD_CADENCE_BY_HAZARD = { 12, 10, 8, 6, 5, 4, 3, 2 };
    /** Safety floor on the revenant spawn cadence. */
    public static int BOSS_ADD_CADENCE_MIN = 2;
    /** Max live revenant adds before the add-spawner pauses. */
    public static int BOSS_ADD_MAX_ALIVE = 8;
    /** Cap on the total reanimated kills reproduced over the fight (0 = all). */
    public static int BOSS_ADD_TOTAL_CAP = 0;

    // ------------------------- Run score (RL-19 / RL-58) ---------------------
    // score = (mobsKilled*PER_MOB + gemsFound*PER_GEM + foodEaten*PER_FOOD
    //          + beaconsLit*PER_BEACON_LIT + (killedWraith ? WRAITH_BONUS : 0)
    //          + (allBeaconsLit ? PERFECT_VICTORY_BONUS : 0))
    //         * scoreMultiplier(difficulty).  Applies to every run (deaths too).
    public static int SCORE_PER_MOB        = 1;
    public static int SCORE_PER_GEM        = 10;
    public static int SCORE_PER_FOOD       = 20;
    public static int SCORE_PER_BEACON_LIT = 1000;
    public static int SCORE_WRAITH_BONUS   = 10000;
    /** Bonus for a perfect victory (every beacon in the world lit). */
    public static int PERFECT_VICTORY_BONUS = 500;

    /** Score coefficient for difficulty {@code d} (from the per-tier
     *  {@code TUNING} table; config-tunable via {@code difficulty,<TIER>.SCORE_MULT}). */
    public static double scoreMultiplier(Difficulty d) {
        return tuningFor(d).scoreMult();
    }

    /** Pre-multiplier score subtotal (before the difficulty coefficient). */
    public static long scoreSubtotal(com.bjsp123.rl2.model.RunStats stats,
                                     int beaconsLit, boolean killedWraith,
                                     boolean allBeaconsLit) {
        if (stats == null) return 0;
        return (long) stats.mobsKilled * SCORE_PER_MOB
             + (long) stats.gemsFound  * SCORE_PER_GEM
             + (long) stats.foodEaten  * SCORE_PER_FOOD
             + (long) beaconsLit       * SCORE_PER_BEACON_LIT
             + (killedWraith   ? SCORE_WRAITH_BONUS    : 0)
             + (allBeaconsLit  ? PERFECT_VICTORY_BONUS : 0);
    }

    /** Final run score: {@link #scoreSubtotal} times the active difficulty's
     *  {@link #scoreMultiplier}. Used for both deaths and victories. */
    public static int runScore(com.bjsp123.rl2.model.RunStats stats,
                               int beaconsLit, boolean killedWraith, boolean allBeaconsLit) {
        return (int) Math.round(scoreSubtotal(stats, beaconsLit, killedWraith, allBeaconsLit)
                * scoreMultiplier(difficulty));
    }

    // ------------------------- Gem recycle (RL-50) ---------------------------
    /** Expected gems from recycling an item = {@code RECYCLE_BASE_GEMS +
     *  power * RECYCLE_GEMS_PER_POWER}, rolled stochastically. Base is the
     *  power-0 yield (~0.25 = a 1-in-4 chance of a single gem). */
    public static double RECYCLE_BASE_GEMS = 0.25;
    /** Extra expected gems added at full item power. */
    public static double RECYCLE_GEMS_PER_POWER = 2.75;
    /** Hard cap on gems from a single recycle. */
    public static int RECYCLE_MAX_GEMS = 4;

    /** Manifest Elixirs scroll: how many random potions it conjures, and the
     *  depth bonus their generation uses (like other creation scrolls). */
    public static int ELIXIR_FORMULA_POTION_COUNT = 5;
    public static int ELIXIR_FORMULA_DEPTH_BONUS  = 2;
    /** Flat depth bonus shared by the creation scrolls: every conjured item is
     *  generated as if found {@code current_depth + this} levels down, so the
     *  reward scales with progress instead of saturating on early floors. */
    public static int CREATION_SCROLL_DEPTH_BONUS = 4;
    /** Number of bombs the Manifest Munitions scroll conjures. */
    public static int INVOCATION_CHIYOU_BOMB_COUNT = 10;
    /** Hard cap on any item's effective maximum charges, however much its level
     *  would otherwise scale it. */
    public static int MAX_ITEM_CHARGES = 8;

    // ------------------------- Throw range -----------------------------------
    /** Default Chebyshev throw range for the player targeting overlay. */
    public static int DEFAULT_THROW_RANGE = 6;
    /** Throw range bonus granted per level of the HURLER perk. */
    public static int HURLER_RANGE_PER_LEVEL = 2;

    // ------------------------- Ranged shots ----------------------------------
    /** Accuracy modifier applied to an innate ranged shot resolved at
     *  point-blank range (target adjacent, Chebyshev 1). Negative = penalty;
     *  shooting an adjacent target is hard. */
    public static int POINT_BLANK_ACCURACY_MOD = -50;

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
    /** Turns between spore-cloud emissions from a mushroom tile (the firing
     *  frequency). Each mushroom is phase-staggered 0-6 turns so they don't fire
     *  in lockstep. */
    public static int MUSHROOM_SPORE_INTERVAL = 30;
    /** POISONED stacks applied per turn to a mob standing in a spore cloud. */
    public static int SPORE_CLOUD_STACKS = 2;
    /** Per-turn chance that a water / blood surface adjacent to fire evaporates
     *  (the surface is removed) in addition to emitting steam. */
    public static double SURFACE_EVAPORATION_CHANCE = 0.25;

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
    /** Percentage of SPECIAL_GEM rewards (unique mobs, special rooms) that
     *  roll a plain hamethyst instead of a metal/exotic - the main knob for
     *  the per-world rare-gem budget. */
    public static double SPECIAL_GEM_BASIC_PCT = 60;

    /** Relative class weights for a generic (class-agnostic) gem roll - the
     *  {@code LootCategory.GEM} reference used by ANY scatter and themed-room GEM cells.
     *  Basic common, metal rare, exotic very rare. */
    public static int GEM_WEIGHT_BASIC  = 70;
    public static int GEM_WEIGHT_METAL  = 25;
    public static int GEM_WEIGHT_EXOTIC = 5;
}
