package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;

import java.util.Random;

/**
 * Deterministic combat-power estimator for {@link Mob}s. Two mobs are pitted against each
 * other in an idealised duel and the result tells you who wins and how much HP they have
 * left. Used by {@link com.bjsp123.rl2.logic.LevelFactoryPopulate#pickMob} to rank species
 * by danger level so deeper dungeon levels favour more powerful spawns.
 *
 * <p>The duel models three phases:
 * <ol>
 *   <li><b>Two opening ranged volleys</b>. If the attacker has a ranged attack
 *       ({@code rangedDamageMax > 0}), the attacker fires twice while closing. Each
 *       volley uses the attacker's accuracy vs the defender's evasion to compute a hit
 *       probability and applies the average ranged damage net of the defender's armour.
 *       The defender returns fire with their own ranged attack each volley if they have
 *       one. Whichever mob has lower {@code moveCost} closes faster, but for the power
 *       ranking we always credit the attacker with both volleys regardless of speed —
 *       see notes on {@link #powerScore(Mob)}.</li>
 *   <li><b>Melee phase</b>. Both mobs swing each "round" using expected-damage maths
 *       (hit probability × average damage minus average armour). The slower mob (higher
 *       {@code moveCost}) gets fewer effective swings per unit of game-time; we model
 *       this with a per-mob attack rate of {@code 100 / moveCost} swings per round and
 *       collapse to a single damage exchange per round scaled by the rate ratio.</li>
 *   <li><b>Resolution</b>. The first mob whose HP drops to ≤ 0 loses. The other mob's
 *       remaining HP is the result.</li>
 * </ol>
 *
 * <p>The fight is purely deterministic — it uses expected values, no RNG — so calling
 * {@code simulate(a, b)} twice always returns the same {@link Result}. That makes power
 * scores stable for a given {@code MobFactory} configuration.
 */
public final class MobFight {

    /** Number of opening ranged volleys the attacker fires before melee begins. */
    public static final int OPENING_RANGED_VOLLEYS = 2;

    private MobFight() {}

    /** Outcome of a simulated duel. {@link #attackerWon} is true iff the defender
     *  dropped to 0 HP first; {@link #attackerHpLeft} / {@link #defenderHpLeft} are the
     *  surviving HP of each combatant (the loser's value is 0). */
    public static final class Result {
        public final boolean attackerWon;
        public final double  attackerHpLeft;
        public final double  defenderHpLeft;
        public Result(boolean attackerWon, double aHp, double dHp) {
            this.attackerWon    = attackerWon;
            this.attackerHpLeft = aHp;
            this.defenderHpLeft = dHp;
        }
    }

    /** Coarse danger band assigned to a species. Used by callers that want a label
     *  rather than a raw score (UI, encyclopedia, balance dashboards). Cutoffs match
     *  {@link #threatLevelOf} — keep the two in sync. */
    public enum ThreatLevel {
        NEGLIGIBLE, MINOR, MODERATE, SEVERE, DEADLY
    }

    /** Hit-by-hit duel. {@code attacker} fires the opening ranged volleys, then both
     *  sides melee at their natural cadence (100/moveCost swings per 100 ticks) until
     *  one drops. Uses real hit + damage rolls — {@code simulate(a, b)} uses a fresh
     *  seeded RNG so the result is reproducible across calls; pass your own RNG via
     *  {@link #simulate(Mob, Mob, Random)} when you need variance across trials. */
    public static Result simulate(Mob attacker, Mob defender) {
        return simulate(attacker, defender, new Random(THREAT_RNG_SEED));
    }

    public static Result simulate(Mob attacker, Mob defender, Random rng) {
        if (attacker == null || defender == null) {
            return new Result(false, 0, 0);
        }
        double aHp = attacker.effectiveStats().maxHp;
        double dHp = defender.effectiveStats().maxHp;

        // Opening ranged volleys — attacker shoots first each volley, defender returns
        // fire if they have a ranged attack. Skipped silently when neither side does.
        for (int v = 0; v < OPENING_RANGED_VOLLEYS && aHp > 0 && dHp > 0; v++) {
            dHp -= rangedRoll(attacker, defender, rng);
            if (dHp <= 0) break;
            aHp -= rangedRoll(defender, attacker, rng);
        }
        if (aHp <= 0 && dHp <= 0) return new Result(true, 0, 0);
        if (aHp <= 0) return new Result(false, 0, Math.max(0, dHp));
        if (dHp <= 0) return new Result(true,  Math.max(0, aHp), 0);

        // Melee phase — tick-resolution. Each side swings on multiples of its moveCost.
        int aMc = attacker.effectiveStats().moveCost > 0 ? attacker.effectiveStats().moveCost : 100;
        int dMc = defender.effectiveStats().moveCost > 0 ? defender.effectiveStats().moveCost : 100;
        int aSwingAt = aMc;
        int dSwingAt = dMc;
        final int MAX_TICKS = 20000;
        for (int t = 1; t <= MAX_TICKS && aHp > 0 && dHp > 0; t++) {
            if (t == aSwingAt) {
                dHp -= meleeRoll(attacker, defender, rng);
                aSwingAt += aMc;
                if (dHp <= 0) break;
            }
            if (t == dSwingAt) {
                aHp -= meleeRoll(defender, attacker, rng);
                dSwingAt += dMc;
                if (aHp <= 0) break;
            }
        }
        boolean attackerWon = dHp <= 0 && aHp > 0;
        return new Result(attackerWon, Math.max(0, aHp), Math.max(0, dHp));
    }

    /**
     * Power score for {@code mob}: its expected outcome against a baseline punching-bag
     * opponent. Higher = more dangerous. The baseline has the average defenceless mob's
     * stats so the score scales naturally with maxHp, damage, accuracy, ranged kit, and
     * speed. Used by {@link com.bjsp123.rl2.logic.LevelFactoryPopulate#pickMob} to rank
     * species against the dungeon depth.
     *
     * <p>Score = (mob's maxHp left after the duel) + 10 × (1 if mob won, else 0). Adding
     * a flat bonus for the win itself disambiguates two mobs that both barely win — the
     * one with more HP left scores higher, but a clean win always beats a Pyrrhic one.
     */
    public static double powerScore(Mob mob) {
        Mob dummy = baselineDummy();
        Result r = simulate(mob, dummy);
        return r.attackerHpLeft + (r.attackerWon ? 10.0 : 0.0);
    }

    /** Minimal sparring partner — middle-of-the-road stats so the score reflects the
     *  candidate's combat prowess and not the dummy's. */
    private static Mob baselineDummy() {
        Mob d = new Mob();
        d.intrinsic.maxHp      = 30;
        d.intrinsic.accuracy   = 8;
        d.intrinsic.evasion    = 8;
        d.intrinsic.damage     = com.bjsp123.rl2.model.MinMax.of(2);
        d.intrinsic.armor      = com.bjsp123.rl2.model.MinMax.of(0);
        d.intrinsic.moveCost   = 100;
        d.intrinsic.attackCost = 100;
        return d;
    }
    // ════════════════════════════════════════════════════════════════════════
    // THREAT SCORING — the player-facing measure used by pickMob
    // ════════════════════════════════════════════════════════════════════════

    /** Trials per character-level when computing {@link #winsVsFighters}. The mob fights
     *  the same benchmark warrior this many times at each of L1, L3, L5; the win counts
     *  feed the {@link #threatScore} formula. */
    public static final int FIGHTS_PER_LEVEL = 5;

    /** Seed for the threat-sim RNG. Constant so {@link #threatScore} is reproducible —
     *  every call uses a fresh {@code Random(SEED)} and walks through the same sequence
     *  of rolls. Bump if you ever want to re-roll the threat estimates. */
    private static final long THREAT_RNG_SEED = 42L;

    /**
     * Number of duels {@code mob} wins out of {@link #FIGHTS_PER_LEVEL} fights against
     * each of three benchmark player builds (warrior / rogue / mage) at each of
     * character levels 1, 3, and 5 ({@code 3 classes × 3 levels × FIGHTS_PER_LEVEL = 45}
     * fights total when FIGHTS_PER_LEVEL=5). Each fight uses real hit + damage rolls
     * (not the deterministic expected-damage path), with a seeded RNG so the result is
     * reproducible.
     */
    public static int winsVsFighters(Mob mob) {
        Random rng = new Random(THREAT_RNG_SEED);
        int wins = 0;
        for (int charLevel : new int[]{1, 3, 5}) {
            for (java.util.function.IntFunction<Mob> build : BENCHMARK_BUILDS) {
                for (int trial = 0; trial < FIGHTS_PER_LEVEL; trial++) {
                    Mob fighter = build.apply(charLevel);
                    if (simulate(mob, fighter, rng).attackerWon) wins++;
                }
            }
        }
        return wins;
    }

    /** Benchmark player builds the threat sim cycles through. Each entry returns a
     *  level-N mob of the corresponding class with the standard starter weapon equipped
     *  but no armour, at half HP — see {@link #benchmarkWarrior}, {@link #benchmarkRogue},
     *  {@link #benchmarkMage}. */
    private static final java.util.function.IntFunction<Mob>[] BENCHMARK_BUILDS;
    static {
        @SuppressWarnings("unchecked")
        java.util.function.IntFunction<Mob>[] arr = new java.util.function.IntFunction[]{
                (java.util.function.IntFunction<Mob>) MobFight::benchmarkWarrior,
                (java.util.function.IntFunction<Mob>) MobFight::benchmarkRogue,
                (java.util.function.IntFunction<Mob>) MobFight::benchmarkMage,
        };
        BENCHMARK_BUILDS = arr;
    }

    /** Single melee swing of {@code a} at {@code b}, with a real hit roll + damage roll
     *  + armour roll (ranges as per {@link MobSystem#rawDamageRange} and
     *  {@link MobSystem#resistRange}). Returns the damage actually inflicted. */
    private static int meleeRoll(Mob a, Mob b, Random rng) {
        int denom = a.effectiveStats().accuracy + b.effectiveStats().evasion;
        if (denom <= 0) return 0;
        if (rng.nextInt(denom) >= a.effectiveStats().accuracy) return 0;
        com.bjsp123.rl2.model.MinMax dmg = MobSystem.rawDamageRange(a);
        com.bjsp123.rl2.model.MinMax arm = MobSystem.resistRange(b);
        int rolledDmg = rollInRange(dmg.min(), dmg.max(), rng);
        int rolledArm = rollInRange(arm.min(), arm.max(), rng);
        return Math.max(0, rolledDmg - rolledArm);
    }

    /** Single ranged shot from {@code a} at {@code b}. Same hit roll + damage roll +
     *  armour roll structure as {@link #meleeRoll}, but uses the ranged damage range
     *  off the mob. Returns 0 if the mob has no ranged attack. */
    private static int rangedRoll(Mob a, Mob b, Random rng) {
        com.bjsp123.rl2.model.StatBlock as = a.effectiveStats();
        com.bjsp123.rl2.model.StatBlock bs = b.effectiveStats();
        if (as.rangedDamage.max() <= 0) return 0;
        int denom = as.accuracy + bs.evasion;
        if (denom <= 0) return 0;
        if (rng.nextInt(denom) >= as.accuracy) return 0;
        com.bjsp123.rl2.model.MinMax arm = MobSystem.resistRange(b);
        int rolledDmg = rollInRange(as.rangedDamage.min(), as.rangedDamage.max(), rng);
        int rolledArm = rollInRange(arm.min(), arm.max(), rng);
        return Math.max(0, rolledDmg - rolledArm);
    }

    private static int rollInRange(int min, int max, Random rng) {
        if (max <= min) return min;
        return rng.nextInt(max - min + 1) + min;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CHALLENGE RATING — wins per mob across the three player classes at three
    // character levels. Designed so a single number per species summarises how
    // dangerous it is for a typical campaign run.
    // ════════════════════════════════════════════════════════════════════════

    /** Trials per (class, level) pairing in the challenge-rating sim. */
    public static final int CHALLENGE_TRIALS_PER_LEVEL = 5;
    /** Player character levels probed by the challenge sim — early / mid / late. */
    public static final int[] CHALLENGE_LEVELS = {1, 5, 10};
    /** Free ranged volleys the mob fires at the closing player before melee. */
    public static final int CHALLENGE_RANGED_VOLLEYS = 3;
    /** Magic missile casts the mage benchmark fires before stepping into melee. */
    public static final int MAGE_OPENING_MISSILES = 2;
    /** Probability the rogue benchmark opens with a fire-bomb throw. */
    public static final double ROGUE_BOMB_CHANCE = 0.5;

    /** Maximum challenge rating: classes × levels × trials. With current
     *  constants that's {@code 3 × 3 × 5 = 45}. */
    public static int maxChallengeRating() {
        return Mob.CharacterClass.values().length
             * CHALLENGE_LEVELS.length
             * CHALLENGE_TRIALS_PER_LEVEL;
    }

    /**
     * Number of duels {@code mob} wins out of {@link #maxChallengeRating()} fights
     * against each player class (warrior / rogue / mage) at each of
     * {@link #CHALLENGE_LEVELS}, with {@link #CHALLENGE_TRIALS_PER_LEVEL} trials
     * per pairing.
     *
     * <p>The fight model is asymmetric and reflects how a real player-vs-mob
     * encounter goes:
     * <ul>
     *   <li>Mob with a ranged attack fires {@link #CHALLENGE_RANGED_VOLLEYS}
     *       times while the player closes (no return fire from the player —
     *       class-specific opening abilities are handled separately).</li>
     *   <li>Mage opens with {@link #MAGE_OPENING_MISSILES} magic missiles, damage
     *       scaled by the mage's character level.</li>
     *   <li>Rogue has a {@link #ROGUE_BOMB_CHANCE} chance to lob a fire bomb
     *       (level-scaled by the rogue's character level).</li>
     *   <li>Then both sides melee at their natural cadence until one drops.</li>
     * </ul>
     * Uses a fresh seeded RNG so the result is reproducible across calls.
     */
    public static int challengeRating(Mob mob) {
        if (mob == null) return 0;
        MobProgression.setSpawnLevel(mob, CHALLENGE_MOB_LEVEL);
        Random rng = new Random(THREAT_RNG_SEED);
        int wins = 0;
        for (int charLevel : CHALLENGE_LEVELS) {
            for (Mob.CharacterClass cls : Mob.CharacterClass.values()) {
                for (int trial = 0; trial < CHALLENGE_TRIALS_PER_LEVEL; trial++) {
                    if (simulateChallenge(mob, cls, charLevel, rng).attackerWon) wins++;
                }
            }
        }
        return wins;
    }

    /**
     * One challenge-rating duel — {@code mob} fights a freshly-built benchmark
     * fighter of {@code playerClass} at {@code charLevel}. Returns the
     * {@link Result} with {@code attackerWon == true} iff the mob killed the
     * fighter first.
     */
    public static Result simulateChallenge(Mob mob, Mob.CharacterClass playerClass,
                                           int charLevel, Random rng) {
        Mob fighter = switch (playerClass) {
            case WARRIOR -> benchmarkWarrior(charLevel);
            case ROGUE   -> benchmarkRogue(charLevel);
            case MAGE    -> benchmarkMage(charLevel);
        };
        if (mob == null || fighter == null) return new Result(false, 0, 0);

        double mobHp    = mob.effectiveStats().maxHp;
        double playerHp = fighter.effectiveStats().maxHp;

        // Phase 1 — mob's free ranged volleys (one-way; player is closing the gap).
        if (mob.effectiveStats().rangedDamage.max() > 0) {
            for (int v = 0; v < CHALLENGE_RANGED_VOLLEYS && mobHp > 0 && playerHp > 0; v++) {
                playerHp -= rangedRoll(mob, fighter, rng);
            }
        }
        if (playerHp <= 0) return new Result(true, mobHp, 0);

        // Phase 2 — class-specific pre-melee openers.
        switch (playerClass) {
            case WARRIOR -> { /* charges in — no opener */ }
            case ROGUE -> {
                if (rng.nextDouble() < ROGUE_BOMB_CHANCE) {
                    int bombLevel = Math.max(0, charLevel - 1);
                    mobHp -= bombDamageRoll(mob, bombLevel, rng);
                }
            }
            case MAGE -> {
                for (int i = 0; i < MAGE_OPENING_MISSILES && mobHp > 0; i++) {
                    mobHp -= magicMissileDamageRoll(mob, charLevel, rng);
                }
            }
        }
        if (mobHp <= 0) return new Result(false, 0, playerHp);

        // Phase 3 — melee. Tick-resolved at each side's natural moveCost cadence.
        int aMc = mob.effectiveStats().moveCost > 0 ? mob.effectiveStats().moveCost : 100;
        int dMc = fighter.effectiveStats().moveCost > 0 ? fighter.effectiveStats().moveCost : 100;
        int aSwingAt = aMc;
        int dSwingAt = dMc;
        final int MAX_TICKS = 20000;
        for (int t = 1; t <= MAX_TICKS && mobHp > 0 && playerHp > 0; t++) {
            if (t == aSwingAt) {
                playerHp -= meleeRoll(mob, fighter, rng);
                aSwingAt += aMc;
                if (playerHp <= 0) break;
            }
            if (t == dSwingAt) {
                mobHp -= meleeRoll(fighter, mob, rng);
                dSwingAt += dMc;
                if (mobHp <= 0) break;
            }
        }
        boolean mobWon = playerHp <= 0 && mobHp > 0;
        return new Result(mobWon, Math.max(0, mobHp), Math.max(0, playerHp));
    }

    /** Number of identical mobs the {@link #challengeRating3v1} sim places against
     *  the lone player benchmark. Tests the "swarm" threat — anthill spawnings,
     *  packs of kobolds, etc. */
    public static final int CHALLENGE_PACK_SIZE = 3;

    /** Mob character level applied by the challenge sim before each fight. The
     *  benchmark fighter scales with {@link #CHALLENGE_LEVELS} (1 / 5 / 10);
     *  this is the parallel knob for the mob side. Set to 1 to fight raw
     *  un-leveled spawns. */
    public static final int CHALLENGE_MOB_LEVEL = 5;

    /**
     * Number of duels {@code mob} wins out of {@link #maxChallengeRating()} fights
     * when {@link #CHALLENGE_PACK_SIZE} copies of the mob fight a single player
     * benchmark. Same trial count as {@link #challengeRating} so the two columns
     * are directly comparable; same RNG seed so the result is reproducible.
     */
    public static int challengeRating3v1(Mob mob) {
        if (mob == null) return 0;
        MobProgression.setSpawnLevel(mob, CHALLENGE_MOB_LEVEL);
        Random rng = new Random(THREAT_RNG_SEED);
        int wins = 0;
        for (int charLevel : CHALLENGE_LEVELS) {
            for (Mob.CharacterClass cls : Mob.CharacterClass.values()) {
                for (int trial = 0; trial < CHALLENGE_TRIALS_PER_LEVEL; trial++) {
                    if (simulateChallenge3v1(mob, cls, charLevel, rng).attackerWon) wins++;
                }
            }
        }
        return wins;
    }

    /**
     * One challenge-rating duel where {@code CHALLENGE_PACK_SIZE} copies of
     * {@code template} fight a freshly-built benchmark fighter of
     * {@code playerClass} at {@code charLevel}. Player focuses fire on the
     * lowest-HP mob each swing; mobs each attack at their natural cadence.
     * Mage's magic missiles single-target the lowest-HP mob; rogue's fire bomb
     * is AOE and hits every alive mob. Mob-on-mob support abilities (kobold
     * general's haste/heal etc.) are NOT simulated.
     */
    public static Result simulateChallenge3v1(Mob template, Mob.CharacterClass playerClass,
                                              int charLevel, Random rng) {
        Mob fighter = switch (playerClass) {
            case WARRIOR -> benchmarkWarrior(charLevel);
            case ROGUE   -> benchmarkRogue(charLevel);
            case MAGE    -> benchmarkMage(charLevel);
        };
        if (template == null || fighter == null) return new Result(false, 0, 0);

        final int N = CHALLENGE_PACK_SIZE;
        double[] mobHp = new double[N];
        double templateMaxHp = template.effectiveStats().maxHp;
        for (int i = 0; i < N; i++) mobHp[i] = templateMaxHp;
        double playerHp = fighter.effectiveStats().maxHp;

        // Phase 1 — each ranged mob fires its volleys at the closing player. With
        // every mob ranged and N=3, that's up to 9 hits before the player can
        // engage anyone in melee.
        if (template.effectiveStats().rangedDamage.max() > 0) {
            for (int i = 0; i < N && playerHp > 0; i++) {
                for (int v = 0; v < CHALLENGE_RANGED_VOLLEYS && playerHp > 0; v++) {
                    playerHp -= rangedRoll(template, fighter, rng);
                }
            }
        }
        if (playerHp <= 0) return new Result(true, sumAlive(mobHp), 0);

        // Phase 2 — class openers.
        switch (playerClass) {
            case WARRIOR -> { /* charges in */ }
            case ROGUE -> {
                if (rng.nextDouble() < ROGUE_BOMB_CHANCE) {
                    int bombLevel = Math.max(0, charLevel - 1);
                    // Fire bomb is AOE — hits every alive mob with an
                    // independent armour roll.
                    for (int i = 0; i < N; i++) {
                        if (mobHp[i] > 0) {
                            mobHp[i] -= bombDamageRoll(template, bombLevel, rng);
                        }
                    }
                }
            }
            case MAGE -> {
                // Magic missile is single-target — pick lowest-HP alive each cast
                // so a follow-up doesn't overkill an already-dead mob.
                for (int cast = 0; cast < MAGE_OPENING_MISSILES; cast++) {
                    int target = lowestHpAlive(mobHp);
                    if (target < 0) break;
                    mobHp[target] -= magicMissileDamageRoll(template, charLevel, rng);
                }
            }
        }
        if (allDead(mobHp)) return new Result(false, 0, playerHp);

        // Phase 3 — melee. Each mob swings at its own cadence; player focuses
        // fire on the lowest-HP alive mob. Dead mobs stop swinging.
        int aMc = template.effectiveStats().moveCost > 0 ? template.effectiveStats().moveCost : 100;
        int dMc = fighter.effectiveStats().moveCost > 0 ? fighter.effectiveStats().moveCost : 100;
        int[] mobSwingAt = new int[N];
        for (int i = 0; i < N; i++) mobSwingAt[i] = aMc;
        int playerSwingAt = dMc;
        final int MAX_TICKS = 20000;
        for (int t = 1; t <= MAX_TICKS && playerHp > 0 && !allDead(mobHp); t++) {
            for (int i = 0; i < N; i++) {
                if (mobHp[i] > 0 && t == mobSwingAt[i]) {
                    playerHp -= meleeRoll(template, fighter, rng);
                    mobSwingAt[i] += aMc;
                    if (playerHp <= 0) break;
                }
            }
            if (playerHp <= 0) break;
            if (t == playerSwingAt) {
                int target = lowestHpAlive(mobHp);
                if (target >= 0) {
                    mobHp[target] -= meleeRoll(fighter, template, rng);
                }
                playerSwingAt += dMc;
            }
        }
        boolean mobsWon = playerHp <= 0 && !allDead(mobHp);
        return new Result(mobsWon, Math.max(0, sumAlive(mobHp)), Math.max(0, playerHp));
    }

    private static double sumAlive(double[] hp) {
        double s = 0;
        for (double d : hp) if (d > 0) s += d;
        return s;
    }

    private static boolean allDead(double[] hp) {
        for (double d : hp) if (d > 0) return false;
        return true;
    }

    /** Index of the lowest-HP alive entry in {@code hp}, or {@code -1} if all dead. */
    private static int lowestHpAlive(double[] hp) {
        int best = -1;
        double lo = Double.MAX_VALUE;
        for (int i = 0; i < hp.length; i++) {
            if (hp[i] > 0 && hp[i] < lo) { lo = hp[i]; best = i; }
        }
        return best;
    }

    /** Damage a single fire-bomb throw deals to {@code target}. Mirrors the in-
     *  game wand/bomb damage formula: base + level × increment, mitigated by the
     *  target's armour roll. */
    private static int bombDamageRoll(Mob target, int bombLevel, Random rng) {
        int dmg = GameBalance.BOMB_DAMAGE_BASE
                + Math.max(0, bombLevel) * GameBalance.BOMB_DAMAGE_INCREMENT;
        com.bjsp123.rl2.model.MinMax arm = MobSystem.resistRange(target);
        int rolledArm = rollInRange(arm.min(), arm.max(), rng);
        return Math.max(0, dmg - rolledArm);
    }

    /** Damage a single magic-missile cast deals to {@code target}, with damage
     *  rolled from the wand-of-magic-missile range scaled by the caster's
     *  character level, then mitigated by the target's magic resistance. */
    private static int magicMissileDamageRoll(Mob target, int casterLevel, Random rng) {
        int min = GameBalance.BASIC_WAND_DAMAGE_MIN
                + casterLevel * GameBalance.WAND_DAMAGE_INCREMENT_MIN;
        int max = GameBalance.BASIC_WAND_DAMAGE_MAX
                + casterLevel * GameBalance.WAND_DAMAGE_INCREMENT_MAX;
        int rolledDmg = rollInRange(min, max, rng);
        com.bjsp123.rl2.model.MinMax mres = target.effectiveStats().magicResist;
        int rolledRes = rollInRange(mres.min(), mres.max(), rng);
        return Math.max(0, rolledDmg - rolledRes);
    }

    /** Build a sorted challenge-rating table for every non-PLAYER mob type. Two
     *  columns: 1v1 (a single mob vs the player benchmark) and 3v1
     *  ({@link #CHALLENGE_PACK_SIZE} identical mobs vs the player). Sorted
     *  descending by 3v1 — the swarm rating is more interesting since it's
     *  where weak-but-numerous species can outshine a tough loner. */
    public static String formatChallengeRatings() {
        com.bjsp123.rl2.model.Point origin = new com.bjsp123.rl2.model.Point(0, 0);
        int max = maxChallengeRating();
        StringBuilder out = new StringBuilder();
        out.append(String.format(
                "Challenge ratings — wins out of %d (%d classes × %d levels × %d trials)%n",
                max, Mob.CharacterClass.values().length,
                CHALLENGE_LEVELS.length, CHALLENGE_TRIALS_PER_LEVEL));
        out.append(String.format(
                "Levels: %s; mob fires %d ranged volleys before melee; "
                        + "mage opens with %d magic missiles; "
                        + "rogue throws a fire bomb %d%% of fights.%n",
                java.util.Arrays.toString(CHALLENGE_LEVELS), CHALLENGE_RANGED_VOLLEYS,
                MAGE_OPENING_MISSILES, (int) (ROGUE_BOMB_CHANCE * 100)));
        out.append(String.format(
                "Mobs are scaled to character level %d before each fight.%n",
                CHALLENGE_MOB_LEVEL));
        out.append(String.format(
                "1v1: one mob vs one player.  3v1: %d copies vs one player%n%n",
                CHALLENGE_PACK_SIZE));
        out.append(String.format("%-24s  %4s  %4s%n", "Species", "1v1", "3v1"));
        out.append("-".repeat(38)).append('\n');
        // Collect (name, cr1, cr3) and sort by cr3 descending, cr1 as tiebreaker.
        java.util.List<int[]> ratings = new java.util.ArrayList<>();
        java.util.List<String> names  = new java.util.ArrayList<>();
        for (String t : MobRegistry.knownTypes()) {
            Mob mob1 = MobFactory.spawn(t, origin);
            Mob mob3 = MobFactory.spawn(t, origin); // separate instance — challengeRating mutates RNG state
            if (mob1 == null) continue;
            int cr1 = challengeRating(mob1);
            int cr3 = challengeRating3v1(mob3);
            ratings.add(new int[]{ratings.size(), cr1, cr3});
            names.add(mob1.name != null ? mob1.name : t);
        }
        ratings.sort((a, b) -> {
            int byThree = Integer.compare(b[2], a[2]);
            return byThree != 0 ? byThree : Integer.compare(b[1], a[1]);
        });
        for (int[] r : ratings) {
            out.append(String.format("%-24s  %4d  %4d%n", names.get(r[0]), r[1], r[2]));
        }
        return out.toString();
    }

    /**
     * Combined threat score for {@code mob}: a {@link #winsVsFighters} component (heavy
     * weight per win) plus a speed bonus (faster mobs are deadlier in melee) plus a
     * specials bonus (catalogue of ability flags — terrifying, ranged, fire-related,
     * teleport, flying, etc). Higher = more dangerous.
     *
     * <p>The formula is intentionally simple and tuned by hand; it isn't normalised to
     * any particular scale. Callers should treat the number as ordinal — what matters
     * is the relative ranking of species, not the absolute magnitude.
     */
    public static double threatScore(Mob mob) {
        if (mob == null) return 0.0;
        double winScore = winsVsFighters(mob) / 3.0 * 30.0;
        double speed    = Math.max(0.0, (100.0 - mob.effectiveStats().moveCost) / 5.0);
        double specials = specialsBonus(mob);
        return winScore + speed + specials;
    }

    /** Map a {@link #threatScore} to a coarse {@link ThreatLevel} bucket. Cutoffs were
     *  picked so the current MobFactory roster spreads across the bands without bunching
     *  at one extreme. Adjust here when rebalancing. */
    public static ThreatLevel threatLevelOf(Mob mob) {
        double score = threatScore(mob);
        if (score >= 45.0) return ThreatLevel.DEADLY;
        if (score >= 28.0) return ThreatLevel.SEVERE;
        if (score >= 14.0) return ThreatLevel.MODERATE;
        if (score >=  6.0) return ThreatLevel.MINOR;
        return ThreatLevel.NEGLIGIBLE;
    }

    /** Build a benchmark fighter at character level {@code charLevel}, equipped with
     *  the starter weapon but <em>no armour</em>. The unarmoured benchmark gives
     *  MobFight's expected-damage model enough penetration that mobs with reasonable
     *  damage values (4–6) can actually score wins, so {@link #threatScore} responds
     *  to melee weight, not just specials + speed. Reads the per-class kit and
     *  per-level deltas from the registry's {@code PLAYER_*} row. */
    private static Mob benchmarkWarrior(int charLevel) { return benchmarkFighter(Mob.CharacterClass.WARRIOR, charLevel); }
    private static Mob benchmarkRogue  (int charLevel) { return benchmarkFighter(Mob.CharacterClass.ROGUE,   charLevel); }
    private static Mob benchmarkMage   (int charLevel) { return benchmarkFighter(Mob.CharacterClass.MAGE,    charLevel); }

    /** Shared per-class benchmark builder. Reads stats from the {@code PLAYER_*}
     *  registry row, scales per-level deltas up to {@code charLevel}, halves max HP,
     *  and equips just the row's first equippable item (sword for warrior/rogue,
     *  dagger for mage). */
    private static Mob benchmarkFighter(Mob.CharacterClass cls, int charLevel) {
        String key = "PLAYER_" + cls.name();
        MobDefinition def = MobRegistry.get(key);
        if (def == null) {
            throw new IllegalStateException("missing mobs.csv row: " + key);
        }
        Mob m = new Mob();
        int levelsAbove = Math.max(0, charLevel - 1);
        // Benchmark fighters run at HALF the regular start HP — the threat-score
        // sim is meant to surface mid-tier mobs as a real threat, and full HP at
        // typical mob damage ranges outlasts almost everyone.
        m.intrinsic.maxHp      = (def.maxHp + def.hpPerLevel * levelsAbove) * 0.5;
        m.intrinsic.accuracy   = def.accuracy + def.accuracyPerLevel * levelsAbove;
        m.intrinsic.evasion    = def.evasion  + def.evasionPerLevel  * levelsAbove;
        m.intrinsic.damage     = def.damage.plus(new com.bjsp123.rl2.model.MinMax(
                def.damagePerLevel.min() * levelsAbove,
                def.damagePerLevel.max() * levelsAbove));
        m.intrinsic.moveCost   = def.moveCost   == 0 ? 100 : def.moveCost;
        m.intrinsic.attackCost = def.attackCost == 0 ? 100 : def.attackCost;
        // Equip the first weapon in the row's startingInventory (no armour, no
        // ammo, no consumables — the benchmark is melee-only by design).
        for (MobDefinition.StartItem s : def.startingInventory) {
            com.bjsp123.rl2.model.Item it = ItemFactory.build(s.type);
            if (it.slot == com.bjsp123.rl2.model.Item.ItemSlot.WEAPON) {
                m.inventory.bag.add(it);
                m.inventory.equip(it);
                break;
            }
        }
        return m;
    }

    /**
     * Catalogue of ability-flag threat points. Each entry was tuned hand-side so the
     * relative ordering of species reads sensibly — terrifying outweighs flying, a fire
     * burst on attack outweighs fire immunity on its own, teleport pressure is
     * respected, ranged adds a flat tactical bonus plus a damage scalar.
     */
    private static double specialsBonus(Mob m) {
        com.bjsp123.rl2.model.StatBlock s = m.intrinsic;
        double pts = 0.0;
        if (s.flying)             pts += 3.0;
        // terrifying doesn't carry a threat bonus — making the player flee is a tactical
        // inconvenience, not a damage-dealing edge. We still credit !terrifiable
        // (immunity to fear) below since that's a real defensive trait.
        if (!s.terrifiable)       pts += 2.0;
        if (s.fireImmune)         pts += 2.0;
        if (s.fireSpreadOnAttack) pts += 5.0;
        if (s.fireExplosionRadiusOnDeath > 0)
                                  pts += 3.0 * s.fireExplosionRadiusOnDeath;
        if (s.teleportRate > 0)   pts += 5.0;
        if (s.rangedDamage.max() > 0) {
            pts += 3.0 + 0.5 * s.rangedDamage.average();
        }
        if (s.size >= 5)          pts += 2.0;
        return pts;
    }

    // ════════════════════════════════════════════════════════════════════════
    // LEAGUE TABLE — wins by hostile-mob species against each player class
    // ════════════════════════════════════════════════════════════════════════

    /** Build a level-1 instance of every hostile {@code MobType} and run {@code trials}
     *  fights of it against each of the three player classes at character levels
     *  {@code charLevels}. Returns a formatted table where each row is a species and
     *  the columns are wins out of {@code trials} for each (class, level) pair. */
    public static String formatLeagueTable(int trials, int[] charLevels) {
        // Hostile species — every MobType except PLAYER, KITTEN (litter-only), and
        // friendlies (MOUSE, DOG, CAT, etc.). We probe each by spawning the template,
        // checking whether it intends to attack the player.
        com.bjsp123.rl2.model.Point origin = new com.bjsp123.rl2.model.Point(0, 0);
        java.util.List<String> hostiles = new java.util.ArrayList<>();
        for (String t : MobRegistry.knownTypes()) {
            Mob template = MobFactory.spawn(t, origin);
            if (template == null) continue;
            if (template.attackTypes != null
                    && template.attackTypes.contains(Mob.TYPE_PLAYER)) {
                hostiles.add(t);
            }
        }

        java.util.function.IntFunction<Mob>[] builds = BENCHMARK_BUILDS;
        String[] classNames = {"Warrior", "Rogue", "Mage"};

        StringBuilder out = new StringBuilder();
        out.append(String.format("League table — wins out of %d trials per (class, level), level-1 mob vs class at level %s%n",
                trials, java.util.Arrays.toString(charLevels)));
        // Header
        out.append(String.format("%-22s", "Species"));
        for (int lvl : charLevels) {
            for (String c : classNames) {
                out.append(String.format(" %-9s", c + "L" + lvl));
            }
        }
        out.append('\n');
        out.append("-".repeat(22 + (charLevels.length * classNames.length) * 10)).append('\n');

        Random rng = new Random(THREAT_RNG_SEED);
        for (String t : hostiles) {
            Mob proto = MobFactory.spawn(t, origin);
            String name = proto.name != null ? proto.name : t;
            out.append(String.format("%-22s", name));
            for (int lvl : charLevels) {
                for (int c = 0; c < builds.length; c++) {
                    int wins = 0;
                    for (int i = 0; i < trials; i++) {
                        Mob mob     = MobFactory.spawn(t, origin);
                        Mob fighter = builds[c].apply(lvl);
                        if (simulate(mob, fighter, rng).attackerWon) wins++;
                    }
                    out.append(String.format(" %d/%-7d", wins, trials));
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Convenience entry point — print the challenge-rating table for every
     *  non-PLAYER mob type. Run via:
     *  {@code java -cp rlib/build/classes/java/main com.bjsp123.rl2.logic.MobFight}.
     */
    public static void main(String[] args) {
        System.out.print(formatChallengeRatings());
    }
}
