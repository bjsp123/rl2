package com.bjsp123.rl2.ai.util;

import com.bjsp123.rl2.ai.RaiBootstrap;
import com.bjsp123.rl2.ai.game.AutoplayGame;
import com.bjsp123.rl2.ai.game.AutoplayStats;
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
import java.util.List;

/**
 * [DEV / DIAGNOSTIC] AI playtesting regression harness (RL-30).
 *
 * <p>Runs the SMART autoplay agent for every player class &times; the three
 * easiest difficulty levels {EASY, GENTLE, NORMAL}, N runs each (default 3),
 * each capped at a {@link #TICK_BUDGET}-tick timeout, then reports max/avg
 * depth, player level, and turn count per (class, difficulty) cell. Writes one
 * row per run to {@code regression.csv} and prints a summary table to stdout.
 *
 * <p>All runs begin at character level 1 on dungeon depth 1; the difficulty is
 * applied via {@link GameBalance#applyDifficulty} (HP multipliers, regen, spawn
 * cadence) and the difficulty's Jade Peach revive charms are granted to the
 * agent. Seeds are deterministic per (class, difficulty, trial) so the batch is
 * reproducible.
 *
 * <p>Gradle: {@code ./gradlew :rai:regression}  (or {@code --args="1"} for a
 * quick smoke run).
 */
public final class RegressionRunMain {

    /** The three easiest difficulties the sweep covers ("very easy / easy /
     *  normal"). All runs start at character level 1; the difficulty supplies the
     *  HP / regen / spawn-rate edge plus revive charms. */
    private static final GameBalance.Difficulty[] DIFFICULTIES = {
            GameBalance.Difficulty.SUPEREASY,
            GameBalance.Difficulty.EASY,
            GameBalance.Difficulty.GENTLE,
            GameBalance.Difficulty.NORMAL,
            GameBalance.Difficulty.HARD,
    };
    private static final int   DEFAULT_RUNS = 3;
    private static final int   TICK_BUDGET  = 250_000;
    /** Fixed base so each (class, difficulty, trial) seed is reproducible. */
    private static final long  SEED_BASE    = 0x21E605EDBA5EL;
    /** Aggregated arrival-turn sum + count per depth index across all runs,
     *  printed by {@link #printDepthPacing} as the average pace-to-depth. */
    private static final long[] DEPTH_TURN_SUM = new long[64];
    private static final int[]  DEPTH_TURN_N   = new int[64];

    private RegressionRunMain() {}

    /** One run's headline numbers. Depth is 1-based (matches Level.depth). */
    private record RunResult(int depth, int charLevel, long turns, String outcome) {}

    public static void main(String[] args) throws IOException {
        int runs = DEFAULT_RUNS;
        for (String a : args) {
            if (a == null) continue;
            try { runs = Integer.parseInt(a.trim()); } catch (NumberFormatException ignore) {}
        }

        loadData(locateAssetsDir());
        RaiBootstrap.init();
        com.bjsp123.rl2.ai.SmartAi.resetDecisionCounters();

        // [diag] "stuck" => run one stalling MAGE L50 with the per-level
        // dwell / decision-mix stuck-log, then exit. For investigating timeouts.
        if (args.length > 0 && "stuck".equals(args[0])) {
            runStuckDiag();
            return;
        }

        Path csvOut = Paths.get("results", "regression.csv").toAbsolutePath();
        Files.createDirectories(csvOut.getParent());
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(csvOut))) {
            csv.println("seed,char_class,difficulty,outcome,turns,depth_reached,final_char_level");
            System.out.printf("[regression] SMART agent  |  %d run(s)/cell  |  timeout %d ticks%n%n",
                    runs, TICK_BUDGET);

            for (Mob.CharacterClass cls : Mob.CharacterClass.values()) {
                for (GameBalance.Difficulty diff : DIFFICULTIES) {
                    List<RunResult> cell = new ArrayList<>(runs);
                    for (int trial = 0; trial < runs; trial++) {
                        long seed = seedFor(cls, diff, trial);
                        // Apply the difficulty BEFORE building the run so every mob
                        // (player + enemies) is created with the scaled HP, then
                        // grant the difficulty's revive charms to the agent.
                        GameBalance.applyDifficulty(diff);
                        AutoplayGame g = AutoplayGame.newRun(seed, cls,
                                AutoplayGame.worldWidth(), AutoplayGame.worldHeight());
                        grantReviveCharms(g.agent, GameBalance.STARTING_REVIVE_CHARMS);
                        AutoplayStats s = g.runUntil(TICK_BUDGET);
                        RunResult r = new RunResult(s.depthReached + 1,
                                s.finalCharLevel, s.turnsSurvived, s.outcome);
                        cell.add(r);
                        csv.printf("%s,%s,%s,%s,%d,%d,%d%n",
                                SeedCode.encode(seed), cls.name(), diff.name(),
                                r.outcome, r.turns, r.depth, r.charLevel);
                        for (int d = 0; d < s.arrivalTickPerDepth.length
                                && d < DEPTH_TURN_SUM.length; d++) {
                            if (s.arrivalTickPerDepth[d] >= 0) {
                                DEPTH_TURN_SUM[d] += s.arrivalTickPerDepth[d];
                                DEPTH_TURN_N[d]++;
                            }
                        }
                    }
                    printCell(cls, diff, cell);
                }
            }
            // Reset so anything that runs after the sweep sees Normal balance.
            GameBalance.applyDifficulty(GameBalance.Difficulty.NORMAL);
        }
        System.out.println("\n[regression] per-run CSV: " + csvOut);
        printDepthPacing();
        printDecisions();
    }

    /** Average cumulative turns to FIRST reach each depth, across all runs that
     *  reached it. Shows the agent's pace and where the run's time is spent. */
    private static void printDepthPacing() {
        System.out.println("[regression] avg turns to reach each depth (n = runs reaching it):");
        for (int d = 0; d < DEPTH_TURN_SUM.length; d++) {
            if (DEPTH_TURN_N[d] == 0) continue;
            System.out.printf("    depth %2d   avg %,8d turns   (n=%d)%n",
                    d + 1, DEPTH_TURN_SUM[d] / DEPTH_TURN_N[d], DEPTH_TURN_N[d]);
        }
    }

    /** Print the top decision tags (branch/action) across the whole batch.
     *  Tick-weighted: long timeout runs dominate, so this surfaces what the
     *  agent spends its time doing when it stalls. */
    private static void printDecisions() {
        java.util.Map<String, Long> counts = com.bjsp123.rl2.ai.SmartAi.decisionCounters();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return;
        System.out.println("[regression] decision mix (top 15, tick-weighted):");
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.printf("    %-28s %5.1f%%  (%d)%n",
                        e.getKey(), 100.0 * e.getValue() / total, e.getValue()));
    }

    /** [diag] Run one WARRIOR NORMAL-difficulty trial with the stuck-log enabled,
     *  to inspect the per-level dwell / decision mix behind regular-dungeon
     *  timeouts. */
    private static void runStuckDiag() {
        Mob.CharacterClass cls = Mob.CharacterClass.WARRIOR;
        GameBalance.Difficulty diff = GameBalance.Difficulty.NORMAL;
        int trial = 0;
        long seed = seedFor(cls, diff, trial);
        GameBalance.applyDifficulty(diff);
        AutoplayGame g = AutoplayGame.newRun(seed, cls,
                AutoplayGame.worldWidth(), AutoplayGame.worldHeight());
        grantReviveCharms(g.agent, GameBalance.STARTING_REVIVE_CHARMS);
        g.enableStuckLog();
        AutoplayStats s = g.runUntil(TICK_BUDGET);
        System.out.printf("[stuck-diag] %s %s seed=%s outcome=%s depth=%d turns=%d%n",
                cls.name(), diff.name(), SeedCode.encode(seed),
                s.outcome, s.depthReached + 1, s.turnsSurvived);
    }

    /** Grant {@code n} Jade Peach revive charms into the agent's bag so the
     *  difficulty's "lives" are exercised by the autoplay run. */
    private static void grantReviveCharms(Mob agent, int n) {
        if (agent == null || agent.inventory == null || n <= 0) return;
        for (int i = 0; i < n; i++) {
            try {
                com.bjsp123.rl2.model.Item charm =
                        com.bjsp123.rl2.logic.ItemFactory.build("JADE_PEACH");
                com.bjsp123.rl2.logic.InventorySystem.addToBag(agent.inventory, charm);
            } catch (RuntimeException ignored) { /* registry missing JADE_PEACH */ }
        }
    }

    /** Reproducible seed per cell + trial. Uses {@code ordinal()} (stable),
     *  not enum {@code hashCode()} (identity-based, varies per JVM). */
    private static long seedFor(Mob.CharacterClass cls, GameBalance.Difficulty diff, int trial) {
        long s = SEED_BASE
                ^ (cls.ordinal() * 0xC2B2AE3D27D4EB4FL)
                ^ ((diff.ordinal() + 1L) * 0x9E3779B97F4A7C15L);
        return Math.floorMod(s + trial, SeedCode.SPACE);
    }

    private static void printCell(Mob.CharacterClass cls, GameBalance.Difficulty diff,
                                  List<RunResult> cell) {
        if (cell.isEmpty()) return;
        int n = cell.size();
        int win = 0, death = 0, timeout = 0;
        int maxDepth = 0, maxLvl = 0;
        long maxTurns = 0, sumDepth = 0, sumLvl = 0, sumTurns = 0;
        for (RunResult r : cell) {
            switch (r.outcome) {
                case "WIN"   -> win++;
                case "DEATH" -> death++;
                default      -> timeout++;
            }
            maxDepth = Math.max(maxDepth, r.depth);
            maxLvl   = Math.max(maxLvl, r.charLevel);
            maxTurns = Math.max(maxTurns, r.turns);
            sumDepth += r.depth;
            sumLvl   += r.charLevel;
            sumTurns += r.turns;
        }
        System.out.printf(
            "%-8s %-7s runs=%d  W/D/T=%d/%d/%d  depth max=%d avg=%.1f  lvl max=%d avg=%.1f  turns max=%d avg=%.0f%n",
            cls.name(), diff.name(), n, win, death, timeout,
            maxDepth, sumDepth / (double) n,
            maxLvl,   sumLvl   / (double) n,
            maxTurns, sumTurns / (double) n);
    }

    // -- headless data load (mirrors AutoplayRunMain) -------------------------
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
