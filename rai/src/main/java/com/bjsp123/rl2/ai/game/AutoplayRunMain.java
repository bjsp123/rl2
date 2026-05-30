package com.bjsp123.rl2.ai.game;

import com.bjsp123.rl2.ai.RaiBootstrap;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.util.SeedCode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Full-game autoplay driver. For each player class, spawns N
 * {@link AutoplayGame} instances and runs them to completion (death, win, or
 * tick budget). Writes one row per run to {@code autoplay.csv} and prints a
 * per-class aggregate summary.
 *
 * <p>Gradle: {@code ./gradlew :rai:autoplay --args="20"} (runs per class).
 */
public final class AutoplayRunMain {

    private static final int DEFAULT_RUNS_PER_CLASS = 20;

    private AutoplayRunMain() {}

    public static void main(String[] args) throws IOException {
        int runs = DEFAULT_RUNS_PER_CLASS;
        Mob.CharacterClass onlyClass = null;
        boolean trace = false;
        boolean stuckLog = false;
        // Env vars: AUTOPLAY_CLASS=ROGUE AUTOPLAY_RUNS=1 AUTOPLAY_TRACE=1 AUTOPLAY_STUCK=1
        // Easier than wrestling with gradle --args parsing.
        String envClass = System.getenv("AUTOPLAY_CLASS");
        if (envClass != null && !envClass.isEmpty()) {
            onlyClass = Mob.CharacterClass.valueOf(envClass.toUpperCase());
        }
        String envRuns = System.getenv("AUTOPLAY_RUNS");
        if (envRuns != null && !envRuns.isEmpty()) {
            runs = Integer.parseInt(envRuns);
        }
        String envTrace = System.getenv("AUTOPLAY_TRACE");
        if (envTrace != null && !envTrace.isEmpty()) {
            trace = true;
        }
        String envStuck = System.getenv("AUTOPLAY_STUCK");
        if (envStuck != null && !envStuck.isEmpty()) {
            stuckLog = true;
        }
        // Accept either a positional run-count or named flags
        // (--class=ROGUE, --runs=N, --trace) - the named form is for the focused
        // diagnostic runs ("one rogue, log everything"), the positional form
        // is the existing N-per-class batch.
        for (String a : args) {
            if (a == null) continue;
            if (a.startsWith("class=")) {
                onlyClass = Mob.CharacterClass.valueOf(a.substring("class=".length()).toUpperCase());
            } else if (a.startsWith("runs=")) {
                runs = Integer.parseInt(a.substring("runs=".length()));
            } else if ("trace".equals(a)) {
                trace = true;
            } else {
                try { runs = Integer.parseInt(a); } catch (NumberFormatException ignore) {}
            }
        }
        Path assets = locateAssetsDir();
        loadData(assets);
        RaiBootstrap.init();

        Mob.CharacterClass[] classesToRun = onlyClass == null
                ? Mob.CharacterClass.values()
                : new Mob.CharacterClass[]{onlyClass};

        Path csvOut = Paths.get("autoplay.csv").toAbsolutePath();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(csvOut))) {
            csv.println("seed,char_class,outcome,turns,depth_reached,max_depth,"
                    + "char_level,perks_spent,"
                    + "stairs_down,stairs_up,items_picked,mobs_killed,mobs_killed_env,"
                    + "bombs_thrown,wands_fired,potions_drunk,melee_attacks,"
                    + "hp_remaining,max_hp,satiety_remaining");
            Random seedRng = new Random();
            for (Mob.CharacterClass cls : classesToRun) {
                List<AutoplayStats> pool = new ArrayList<>(runs);
                long t0 = System.currentTimeMillis();
                com.bjsp123.rl2.ai.SmartAi.resetDecisionCounters();
                for (int i = 0; i < runs; i++) {
                    long seed = seedRng.nextLong() % SeedCode.SPACE;
                    AutoplayGame g = AutoplayGame.newRun(seed, cls,
                            AutoplayGame.worldWidth(), AutoplayGame.worldHeight());
                    if (trace) g.enableDecisionTrace();
                    if (stuckLog) g.enableStuckLog();
                    long runStart = System.nanoTime();
                    AutoplayStats s = g.runUntil(AutoplayGame.defaultTickBudget());
                    double runMs = (System.nanoTime() - runStart) / 1_000_000.0;
                    s.wallClockMs = runMs;
                    pool.add(s);
                    writeRow(csv, seed, cls, s);
                    System.out.printf("[%s] %s  d=%d/%d  cl=%d  items=%d  kills=%d  bombs=%d  wands=%d  turns=%d  hp=%.0f  known=%.0f%%  time=%.1fs%n",
                            cls.name(), s.outcome, s.depthReached, s.maxDepth, s.finalCharLevel,
                            s.itemsPickedUp, s.mobsKilled, s.bombsThrown, s.wandsFired,
                            s.turnsSurvived, s.finalHp,
                            s.endLevelKnownFraction * 100.0,
                            runMs / 1000.0);
                }
                printAggregate(cls, pool, (System.currentTimeMillis() - t0) / 1000.0);
                printDecisions();
            }
        }
        System.out.println("[autoplay] per-run CSV: " + csvOut);
    }

    private static void writeRow(PrintWriter csv, long seed, Mob.CharacterClass cls, AutoplayStats s) {
        csv.printf("%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%d%n",
                SeedCode.encode(seed), cls.name(), s.outcome, s.turnsSurvived,
                s.depthReached, s.maxDepth,
                s.finalCharLevel, s.finalPerksSpent,
                s.stairsDescended, s.stairsAscended,
                s.itemsPickedUp, s.mobsKilled, s.mobsKilledByEnv,
                s.bombsThrown, s.wandsFired, s.potionsDrunk, s.meleeAttacks,
                s.finalHp, s.finalMaxHp, s.finalSatiety);
    }

    private static void printAggregate(Mob.CharacterClass cls, List<AutoplayStats> pool,
                                       double elapsedSec) {
        if (pool.isEmpty()) return;
        int n = pool.size();
        int wins = 0, deaths = 0, timeouts = 0;
        int sumItems = 0, sumKills = 0, sumKillsEnv = 0, sumTurns = 0;
        int sumStairsDown = 0;
        int sumBombs = 0, sumWands = 0, sumPotions = 0, sumMelee = 0;
        int sumCharLevel = 0, sumPerks = 0;
        double sumDepth = 0;
        List<Integer> depths = new ArrayList<>(n);
        int reachedD3 = 0, reachedD5 = 0, reachedBottom = 0;
        int maxDepthGlobal = 0;
        for (AutoplayStats s : pool) {
            switch (s.outcome) {
                case "WIN" -> wins++;
                case "DEATH" -> deaths++;
                case "TIMEOUT" -> timeouts++;
            }
            sumItems += s.itemsPickedUp;
            sumKills += s.mobsKilled;
            sumKillsEnv += s.mobsKilledByEnv;
            sumTurns += s.turnsSurvived;
            sumStairsDown += s.stairsDescended;
            sumBombs += s.bombsThrown;
            sumWands += s.wandsFired;
            sumPotions += s.potionsDrunk;
            sumMelee += s.meleeAttacks;
            sumCharLevel += s.finalCharLevel;
            sumPerks += s.finalPerksSpent;
            sumDepth += s.depthReached;
            depths.add(s.depthReached);
            if (s.depthReached >= 3) reachedD3++;
            if (s.depthReached >= 5) reachedD5++;
            if (s.depthReached >= s.maxDepth) reachedBottom++;
            if (s.maxDepth > maxDepthGlobal) maxDepthGlobal = s.maxDepth;
        }
        Collections.sort(depths);
        int median = depths.get(n / 2);
        System.out.printf("==== %s aggregate (%d runs, %.1fs) ====%n", cls.name(), n, elapsedSec);
        System.out.printf("  outcomes: WIN=%d DEATH=%d TIMEOUT=%d%n", wins, deaths, timeouts);
        System.out.printf("  depth:   mean=%.1f  median=%d  >=3:%d  >=5:%d  bottom(%d):%d%n",
                sumDepth / n, median, reachedD3, reachedD5, maxDepthGlobal, reachedBottom);
        System.out.printf("  per run: char_lvl=%.1f  perks=%.1f  items=%.1f  kills=%.1f  bombs=%.1f  wands=%.1f  potions=%.1f  melee=%.1f  turns=%.0f  stairs=%.1f%n",
                sumCharLevel / (double) n, sumPerks / (double) n,
                sumItems / (double) n, sumKills / (double) n,
                sumBombs / (double) n, sumWands / (double) n,
                sumPotions / (double) n, sumMelee / (double) n,
                sumTurns / (double) n, sumStairsDown / (double) n);
        System.out.println();
    }

    /** Print top-15 (goal, action) decision tags so we can see what the agent
     *  actually did during the autoplay batch - reveals whether it was waiting,
     *  exploring, or just spinning on one goal. */
    private static void printDecisions() {
        java.util.Map<String, Long> counts = com.bjsp123.rl2.ai.SmartAi.decisionCounters();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return;
        var top = counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .toList();
        StringBuilder sb = new StringBuilder("  decisions: ");
        for (var e : top) {
            sb.append(String.format("%s=%.0f%%(%d) ",
                    e.getKey(), 100.0 * e.getValue() / total, e.getValue()));
        }
        System.out.println(sb.toString().trim());
        System.out.println();
    }

    private static Path locateAssetsDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("assets").resolve("data");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not find assets/data starting from " + cwd);
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
