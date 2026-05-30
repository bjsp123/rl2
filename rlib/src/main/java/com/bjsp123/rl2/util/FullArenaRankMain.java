package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.CombatArena;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Full-fat arena power ranker. Not shipping code.
 *
 * <p>Unlike {@link MobPowerRankMain} (which uses the pure-melee
 * {@link GameBalance#mobfight}), this runner builds a real
 * {@link CombatArena} level for every fight, places the two combatants with
 * their full kits (starting inventory + perks + items applied via
 * {@link MobFactory#player} for the three player classes), and advances the
 * simulation through {@link CombatArena#tickHeadless} until one combatant
 * dies. So thrown bombs, fired wands, drunk potions, JADE_CRAB / JADE_FISH
 * invocations, AI behaviour, terrain interactions, and surface effects all
 * matter to the outcome. Writes a per-fight CSV to {@code arena_full.csv}.
 *
 * <p>Gradle: {@code ./gradlew :rlib:rankPowerFull --args="5"} (optional
 * trials-per-pair override; defaults to 5 because each match is much more
 * expensive than a {@code mobfight} duel).
 *
 * <p>Caveats:
 * <ul>
 *   <li>JADE_BULL ({@code UseBehavior.CHARGE}) is gated to player-only via
 *       {@code MobSystem.isUsableByAi}, so the AI controlling a PLAYER_WARRIOR
 *       in this arena won't dash. The Warrior's other equipment (SWORD,
 *       SCALE_MAIL, HEALING_POTION) still applies.</li>
 *   <li>PLAYER rows are converted to {@code Behavior.MOB} for the duration of
 *       the fight so the AI drives them (otherwise {@code TurnSystem.tick}
 *       stalls on the player-turn gate).</li>
 *   <li>Stalemates (turn-cap reached, both alive) count as a 0.5 result for
 *       both sides.</li>
 * </ul>
 */
public final class FullArenaRankMain {

    private FullArenaRankMain() {}

    /** Character levels to rank at. */
    private static final int[] LEVELS = {1, 10};
    /** Maximum standard turns per fight before declaring a stalemate. */
    private static final int MAX_STANDARD_TURNS = 200;
    /** Arena dimensions. Roomy enough for ranged volleys but not so big that
     *  closing distance dominates the fight. */
    private static final int ARENA_W = 14;
    private static final int ARENA_H = 14;
    /** Default trials per pair. Each match is ~10-100ms so total is
     *  fighters^2 * trials * avg-match-ms. */
    private static final int DEFAULT_TRIALS = 5;

    /** Generated once for the whole run so the same world's gear catalogue
     *  serves every duel. Deterministic seed so reruns reproduce.
     *
     *  <p>Built in {@link #main(String[])} after {@link #loadData(Path)} - a
     *  class-load-time initialiser would run against an empty registry and
     *  the gear world would contain only procedural gems, leaving every
     *  fighter bare-handed. */
    private static PlayerGearProvider GEAR;

    /** Per-fight CSV writer. Same shape as the rank1vN CSV so downstream
     *  tools can union the two datasets. */
    private static java.io.PrintWriter FIGHT_LOG;

    public static void main(String[] args) throws IOException {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TRIALS;
        Path assets = locateAssetsDir();
        loadData(assets);
        GEAR = new PlayerGearProvider(0xC0FFEEL);

        Path csvOut = Paths.get("arena_full.csv");
        FIGHT_LOG = new java.io.PrintWriter(Files.newBufferedWriter(csvOut));
        FIGHT_LOG.println("fighter_a,fighter_b,char_level,trial,outcome,turns,"
                + "a_hp,a_max_hp,b_hp,b_max_hp");

        List<String> fighters = pickFighters();
        System.out.println("[rl2-rank-full] fighters: " + fighters.size()
                + ", trials per pair: " + trials
                + ", pairs: " + (fighters.size() * (fighters.size() - 1) / 2));
        System.out.println("[rl2-rank-full] per-fight CSV: " + csvOut.toAbsolutePath());

        for (int charLvl : LEVELS) {
            long t0 = System.currentTimeMillis();
            rankAt(fighters, charLvl, trials);
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("  (level " + charLvl + " run took "
                    + (elapsed / 1000) + "s)");
        }
        FIGHT_LOG.flush();
        FIGHT_LOG.close();
    }

    /** Same fighter-pool gate as {@link MobPowerRankMain}: anything with
     *  non-zero HP and a melee or ranged damage path. */
    private static List<String> pickFighters() {
        List<String> out = new ArrayList<>();
        for (String type : Registries.mobTypes()) {
            com.bjsp123.rl2.logic.MobDefinition def = Registries.mob(type);
            if (def == null) continue;
            if (def.maxHp <= 0) continue;
            boolean canHit = def.damage > 0 || def.rangedDamage > 0;
            if (!canHit) continue;
            out.add(type);
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void rankAt(List<String> fighters, int charLvl, int trials) {
        int n = fighters.size();
        int[] wins   = new int[n];
        int[] losses = new int[n];
        int[] draws  = new int[n];
        long seed = 0xA15Eb00BBeefcafeL ^ charLvl;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int t = 0; t < trials; t++) {
                    int outcome = simulateMatch(
                            fighters.get(i), fighters.get(j), charLvl,
                            seed + (long) i * 1_000_003L + (long) j * 1009L + t,
                            t);
                    if      (outcome == +1) { wins  [i]++; losses[j]++; }
                    else if (outcome == -1) { losses[i]++; wins  [j]++; }
                    else                    { draws [i]++; draws [j]++; }
                }
            }
        }
        // Win% counts draws as half wins.
        double[] winPct = new double[n];
        int[] pairFights = new int[n];
        for (int i = 0; i < n; i++) {
            pairFights[i] = wins[i] + losses[i] + draws[i];
            if (pairFights[i] > 0) {
                winPct[i] = (wins[i] + 0.5 * draws[i]) / pairFights[i];
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order,
                Comparator.comparingDouble((Integer i) -> -winPct[i]));

        System.out.println();
        System.out.println("==== Full-arena power ranking @ character level "
                + charLvl + " ====");
        System.out.printf("%-4s %-28s %7s %5s %5s %5s %6s%n",
                "rank", "mob", "fights", "wins", "loss", "draw", "win%");
        for (int rank = 0; rank < n; rank++) {
            int i = order[rank];
            System.out.printf("%4d %-28s %7d %5d %5d %5d %5.1f%%%n",
                    rank + 1, fighters.get(i), pairFights[i],
                    wins[i], losses[i], draws[i], winPct[i] * 100.0);
        }
    }

    /** Returns +1 if {@code aType} wins, -1 if {@code bType} wins, 0 on stalemate. */
    private static int simulateMatch(String aType, String bType, int charLvl,
                                     long seed, int trial) {
        Random rng = new Random(seed);
        Level level = CombatArena.buildArenaLevel(ARENA_W, ARENA_H, rng);
        World world = new World();
        world.unique = new UniqueTracker();
        level.world = world;
        // Single-level world so any cross-level escape paths (chasm-fall,
        // teleport orb scatter) resolve back into this same level.
        world.levels = new Level[] { level };

        Mob a = buildFighter(aType);
        Mob b = buildFighter(bType);
        if (a == null || b == null) return 0;
        MobProgression.setSpawnLevel(a, charLvl);
        MobProgression.setSpawnLevel(b, charLvl);
        // PLAYER_* fighters: equip them with gear matching what they'd have
        // found in a real playthrough up to the depth corresponding to
        // charLvl. Other (mob) fighters keep their CSV starting kit.
        if (a.mobType != null && a.mobType.startsWith("PLAYER_")) {
            GEAR.applyKit(a, GEAR.kitForCharLvl(charLvl));
            MobProgression.autoLevelUpPerks(a, rng);
        }
        if (b.mobType != null && b.mobType.startsWith("PLAYER_")) {
            GEAR.applyKit(b, GEAR.kitForCharLvl(charLvl));
            MobProgression.autoLevelUpPerks(b, rng);
        }
        // Arena uses a single-level world, so TELEPORT_ORB's "scatter across
        // dungeon" becomes "scatter within the room" - either a near-no-op or
        // a free advantage to whoever throws first. Strip the orbs from both
        // fighters so neither side's win rate is determined by who teleports
        // who out of melee range. Same goes for any future cross-level
        // utility item.
        stripFromInventory(a, "TELEPORT_ORB");
        stripFromInventory(b, "TELEPORT_ORB");

        // PLAYER behaviour stalls the turn loop on isPlayerTurn. Convert to
        // MOB so the AI drives them; this lets HUNTER-like target acquisition
        // through {@code attackTypes} pursue the opponent.
        if (a.behavior == Mob.Behavior.PLAYER) a.behavior = Mob.Behavior.MOB;
        if (b.behavior == Mob.Behavior.PLAYER) b.behavior = Mob.Behavior.MOB;
        a.stateOfMind = Mob.StateOfMind.AWAKE;
        b.stateOfMind = Mob.StateOfMind.AWAKE;

        Point aPos = new Point(2, ARENA_H / 2);
        Point bPos = new Point(ARENA_W - 3, ARENA_H / 2);
        CombatArena.placeMobs(level,
                java.util.List.of(a, b),
                java.util.List.of(aPos, bPos));
        CombatArena.seedTeamHostility(
                java.util.List.of(a),
                java.util.List.of(b));

        // tickHeadless advances one TurnSystem.tick per call - drain enough
        // ticks to cover MAX_STANDARD_TURNS standard turns (each standard
        // turn is TurnSystem.STANDARD_TURN_TICKS ticks). Drop accumulated
        // events between ticks so the queue doesn't balloon.
        int maxTicks = MAX_STANDARD_TURNS *
                com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
        int result = 0;
        int ticksElapsed = maxTicks;
        for (int t = 0; t < maxTicks; t++) {
            CombatArena.tickHeadless(level, world, 16);
            if (level.events != null) level.events.clear();
            boolean aDead = a.hp <= 0 || !level.mobs.contains(a);
            boolean bDead = b.hp <= 0 || !level.mobs.contains(b);
            if (aDead && bDead) { result =  0; ticksElapsed = t; break; }
            if (aDead)          { result = -1; ticksElapsed = t; break; }
            if (bDead)          { result = +1; ticksElapsed = t; break; }
            if (!CombatArena.hostilePairExists(level)) { ticksElapsed = t; break; }
        }
        String outcome = result == +1 ? "a_win" : result == -1 ? "b_win" : "draw";
        int turns = ticksElapsed / com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
        if (FIGHT_LOG != null) {
            FIGHT_LOG.printf("%s,%s,%d,%d,%s,%d,%.2f,%.2f,%.2f,%.2f%n",
                    aType, bType, charLvl, trial, outcome, turns,
                    Math.max(0, a.hp), a.effectiveStats().maxHp,
                    Math.max(0, b.hp), b.effectiveStats().maxHp);
        }
        return result;
    }

    /** Remove every bag item whose {@code type} matches {@code typeKey}.
     *  Used by the arena runner to exclude items that don't make sense in
     *  a one-room single-level world (TELEPORT_ORB scatters the target
     *  across the dungeon, which collapses to "relocate within the arena"
     *  here). */
    private static void stripFromInventory(Mob m, String typeKey) {
        if (m == null || m.inventory == null || m.inventory.bag == null) return;
        m.inventory.bag.removeIf(it -> it != null && typeKey.equals(it.type));
    }

    /** {@link MobFactory#spawn} returns null for PLAYER_* rows; route those
     *  through {@link MobFactory#player} so the kit + perks land. */
    private static Mob buildFighter(String type) {
        if (type.startsWith("PLAYER_")) {
            String classKey = type.substring("PLAYER_".length());
            com.bjsp123.rl2.model.Mob.CharacterClass cls =
                    com.bjsp123.rl2.model.Mob.CharacterClass.valueOf(classKey);
            return MobFactory.player(new Point(0, 0), cls);
        }
        return MobFactory.spawn(type, new Point(0, 0));
    }

    private static Path locateAssetsDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("assets").resolve("data");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "Could not find assets/data starting from " + cwd);
    }

    private static void loadData(Path assets) throws IOException {
        Path strings = assets.resolve("strings.csv");
        if (Files.exists(strings)) com.bjsp123.rl2.logic.TextCatalog.load(Files.readString(strings));
        Path config = assets.resolve("config.csv");
        if (Files.exists(config)) GameBalance.load(Files.readString(config));
        Registries.loadMobs(Files.readString(assets.resolve("mobs.csv")));
        Registries.loadItems(Files.readString(assets.resolve("items.csv")));
        Path themed = assets.resolve("themedrooms.csv");
        if (Files.exists(themed)) Registries.loadThemedRooms(Files.readString(themed));
    }
}
