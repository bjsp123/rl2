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

    // Average-based helpers (meleeExpectedDamage, rangedExpectedDamage, attackRate)
    // were retired when {@link #simulate} switched to hit-by-hit rolls. The hit-roll
    // primitives below are now the only damage path.

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

    /** Build a warrior {@link Mob} at character level {@code level}, equipped with the
     *  starter sword but <em>no armour</em>. The unarmoured benchmark gives MobFight's
     *  expected-damage model enough penetration that mobs with reasonable damage values
     *  (4–6) can actually score wins, so {@link #threatScore} responds to melee weight,
     *  not just specials + speed. Doesn't run through MobProgression — it just
     *  hand-applies the per-level deltas (acc/eva/maxHp), which is enough for the sim. */
    private static Mob benchmarkWarrior(int charLevel) {
        Mob m = baseFighter(charLevel,
                GameBalance.WARRIOR_START_HP,
                GameBalance.WARRIOR_BASE_ATTACK,
                GameBalance.WARRIOR_BASE_DEFENSE,
                GameBalance.WARRIOR_BASE_DAMAGE);
        com.bjsp123.rl2.model.Item sword = ItemFactory.sword();
        m.inventory.bag.add(sword); m.inventory.equip(sword);
        return m;
    }

    /** Benchmark rogue at character level {@code charLevel} — sword equipped, no armour,
     *  half HP. Stats track {@link GameBalance}'s ROGUE_* constants the same way the
     *  in-game starter kit does. */
    private static Mob benchmarkRogue(int charLevel) {
        Mob m = baseFighter(charLevel,
                GameBalance.ROGUE_START_HP,
                GameBalance.ROGUE_BASE_ATTACK,
                GameBalance.ROGUE_BASE_DEFENSE,
                GameBalance.ROGUE_BASE_DAMAGE);
        com.bjsp123.rl2.model.Item sword = ItemFactory.sword();
        m.inventory.bag.add(sword); m.inventory.equip(sword);
        return m;
    }

    /** Benchmark mage at character level {@code charLevel} — dagger equipped (lower
     *  damage range than the sword), no armour, half HP. */
    private static Mob benchmarkMage(int charLevel) {
        Mob m = baseFighter(charLevel,
                GameBalance.MAGE_START_HP,
                GameBalance.MAGE_BASE_ATTACK,
                GameBalance.MAGE_BASE_DEFENSE,
                GameBalance.MAGE_BASE_DAMAGE);
        com.bjsp123.rl2.model.Item dagger = ItemFactory.dagger();
        m.inventory.bag.add(dagger); m.inventory.equip(dagger);
        return m;
    }

    /** Shared scaffolding for the per-class benchmark fighters. Builds a Mob at half
     *  the class's start HP plus per-level deltas for accuracy / evasion / maxHp.
     *  Caller is responsible for equipping the weapon. */
    private static Mob baseFighter(int charLevel,
                                   int startHp, int baseAtk, int baseDef, int baseDmg) {
        Mob m = new Mob();
        // Benchmark fighters run at HALF the regular start HP — the threat-score sim is
        // meant to surface mid-tier mobs as a real threat, and full HP at typical mob
        // damage ranges outlasts almost everyone.
        m.intrinsic.maxHp      = (startHp + GameBalance.HP_PER_LEVEL * (charLevel - 1)) * 0.5;
        m.intrinsic.accuracy   = baseAtk  + GameBalance.ATTACK_PER_LEVEL  * (charLevel - 1);
        m.intrinsic.evasion    = baseDef  + GameBalance.DEFENSE_PER_LEVEL * (charLevel - 1);
        m.intrinsic.damage     = com.bjsp123.rl2.model.MinMax.of(baseDmg);
        m.intrinsic.moveCost   = GameBalance.PLAYER_MOVE_COST;
        m.intrinsic.attackCost = GameBalance.PLAYER_ATTACK_COST;
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
        java.util.List<Mob.MobType> hostiles = new java.util.ArrayList<>();
        for (Mob.MobType t : Mob.MobType.values()) {
            if (t == Mob.MobType.PLAYER) continue;
            Mob template = MobFactory.spawn(t, origin);
            if (template == null) continue;
            if (template.attackTypes != null
                    && template.attackTypes.contains(Mob.MobType.PLAYER)) {
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
        for (Mob.MobType t : hostiles) {
            Mob proto = MobFactory.spawn(t, origin);
            String name = proto.name != null ? proto.name : t.name();
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

    /** Convenience entry point — build + dump the default 5-trial × {1,2,3}-level
     *  table to stdout. Run via:
     *  {@code java -cp rlib/build/classes/java/main com.bjsp123.rl2.logic.MobFight}.
     */
    public static void main(String[] args) {
        System.out.print(formatLeagueTable(5, new int[]{1, 2, 3}));
    }
}
