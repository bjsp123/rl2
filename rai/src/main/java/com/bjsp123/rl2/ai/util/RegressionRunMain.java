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
    /** HP the agent lost at each depth index, summed across all runs. Printed as
     *  a share of all damage taken so you can see where the run bleeds HP. */
    private static final long[] DMG_TAKEN_BY_DEPTH = new long[64];
    /** Damage taken per depth index, split by source-mob type, across all runs.
     *  Feeds the "top 3 dealers" column of the by-depth report. */
    private static final java.util.Map<Integer, java.util.Map<String, Long>>
            DMG_SRC_BY_DEPTH = new java.util.LinkedHashMap<>();
    /** Cross-run aggregator for incoming-damage breakdowns. Lets the final
     *  summary tell the user where the agent's HP is leaking globally. */
    private static final java.util.Map<String, Long> DMG_TAKEN_BY_MEDIUM =
            new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, Long> DMG_TAKEN_BY_SOURCE =
            new java.util.LinkedHashMap<>();
    /** Count of agent deaths by killer mob type ({@code "ENV"} for
     *  environmental), aggregated across all runs. */
    private static final java.util.Map<String, Integer> DEATHS_BY_KILLER =
            new java.util.LinkedHashMap<>();
    /** Cross-run aggregators for outgoing damage (by attack mode, and by
     *  mode+specific item type) and for items the agent picked up (by type). */
    private static final java.util.Map<String, Long> DMG_DONE_BY_MODE =
            new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, Long> DMG_DONE_BY_TYPE =
            new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, Long> ITEMS_BY_TYPE =
            new java.util.LinkedHashMap<>();

    private RegressionRunMain() {}

    /** One run's headline numbers. Depth is 1-based (matches Level.depth). */
    private record RunResult(int depth, int charLevel, long turns, String outcome,
                             int meleeAttacks, int meleeDamage,
                             int wandsFired,   int wandDamage,
                             int bombsThrown,  int bombDamage,
                             int mobsKilled) {}

    /** Per-win kill total for the end-of-sweep summary. Indexed by
     *  "{class}/{difficulty}" so the report can break down wins by tier. */
    private static final java.util.List<String> WIN_LABELS = new java.util.ArrayList<>();
    private static final java.util.List<Integer> WIN_KILLS = new java.util.ArrayList<>();

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
            csv.println("seed,char_class,difficulty,outcome,turns,depth_reached,final_char_level,"
                    + "melee_attacks,melee_damage,wands_fired,wand_damage,bombs_thrown,bomb_damage,"
                    + "mobs_killed");
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
                        grantReviveCharms(g.agent, GameBalance.tuning().startingReviveCharms());
                        AutoplayStats s = g.runUntil(TICK_BUDGET);
                        RunResult r = new RunResult(s.maxDungeonDepth,
                                s.finalCharLevel, s.turnsSurvived, s.outcome,
                                s.meleeAttacks, s.meleeDamage,
                                s.wandsFired,   s.wandDamage,
                                s.bombsThrown,  s.bombDamage,
                                s.mobsKilled);
                        cell.add(r);
                        csv.printf("%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                                SeedCode.encode(seed), cls.name(), diff.name(),
                                r.outcome, r.turns, r.depth, r.charLevel,
                                r.meleeAttacks, r.meleeDamage,
                                r.wandsFired,   r.wandDamage,
                                r.bombsThrown,  r.bombDamage,
                                r.mobsKilled);
                        if ("WIN".equals(r.outcome)) {
                            WIN_LABELS.add(cls.name() + "/" + diff.name());
                            WIN_KILLS.add(r.mobsKilled);
                        }
                        for (int d = 0; d < s.arrivalTickPerDepth.length
                                && d < DEPTH_TURN_SUM.length; d++) {
                            if (s.arrivalTickPerDepth[d] >= 0) {
                                DEPTH_TURN_SUM[d] += s.arrivalTickPerDepth[d];
                                DEPTH_TURN_N[d]++;
                            }
                        }
                        s.damageTakenByMedium.forEach((k, v) ->
                                DMG_TAKEN_BY_MEDIUM.merge(k, v.longValue(), Long::sum));
                        s.damageTakenBySource.forEach((k, v) ->
                                DMG_TAKEN_BY_SOURCE.merge(k, v.longValue(), Long::sum));
                        if (s.deathCause != null) {
                            DEATHS_BY_KILLER.merge(s.deathCause, 1, Integer::sum);
                        }
                        s.damageDoneByCategory.forEach((k, v) ->
                                DMG_DONE_BY_MODE.merge(k, v.longValue(), Long::sum));
                        s.damageDoneByType.forEach((k, v) ->
                                DMG_DONE_BY_TYPE.merge(k, v.longValue(), Long::sum));
                        s.itemsByType.forEach((k, v) ->
                                ITEMS_BY_TYPE.merge(k, v.longValue(), Long::sum));
                        for (int d = 0; d < s.damageTakenPerDepth.length
                                && d < DMG_TAKEN_BY_DEPTH.length; d++) {
                            DMG_TAKEN_BY_DEPTH[d] += s.damageTakenPerDepth[d];
                        }
                        s.damageTakenBySourceByDepth.forEach((depth, m) ->
                                m.forEach((src, v) -> DMG_SRC_BY_DEPTH
                                        .computeIfAbsent(depth, k -> new java.util.LinkedHashMap<>())
                                        .merge(src, v.longValue(), Long::sum)));
                    }
                    printCell(cls, diff, cell);
                }
            }
            // Reset so anything that runs after the sweep sees Normal balance.
            GameBalance.applyDifficulty(GameBalance.Difficulty.NORMAL);
        }
        System.out.println("\n[regression] per-run CSV: " + csvOut);
        printDepthPacing();
        printDamageTakenByDepth();
        printNonMirrorDealersByDepth();
        printOutgoingDamage();
        printIncomingDamage();
        printItemsFound();
        printDecisions();
        printWinKills();
    }

    /** Where the agent's damage OUTPUT goes across the whole sweep: first by
     *  attack mode (melee / thrown / bomb / wand), then, indented under each
     *  mode, by the specific weapon / wand / bomb / thrown type. Tells you which
     *  modes and which item types actually carry the agent's offence. */
    private static void printOutgoingDamage() {
        long total = DMG_DONE_BY_MODE.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return;
        System.out.println("[regression] agent damage DONE "
                + "(total " + total + " across all runs):");
        for (String mode : new String[] {"melee", "thrown", "bomb", "wand"}) {
            Long modeTotal = DMG_DONE_BY_MODE.get(mode);
            if (modeTotal == null || modeTotal == 0) continue;
            System.out.printf("    %-8s %6.1f%%  (%d)%n",
                    mode, 100.0 * modeTotal / total, modeTotal);
            String prefix = mode + ":";
            DMG_DONE_BY_TYPE.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> System.out.printf("        %-24s %6.1f%%  (%d)%n",
                            e.getKey().substring(prefix.length()),
                            100.0 * e.getValue() / modeTotal, e.getValue()));
        }
    }

    /** Every item type the agent picked up across the sweep, most-found first.
     *  Shows what the loot stream actually delivers to the player. */
    private static void printItemsFound() {
        long total = ITEMS_BY_TYPE.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return;
        System.out.println("[regression] items found by type "
                + "(total " + total + " across all runs):");
        ITEMS_BY_TYPE.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(50)
                .forEach(e -> System.out.printf("    %-26s %6.1f%%  (%d)%n",
                        e.getKey(), 100.0 * e.getValue() / total, e.getValue()));
    }

    /** Total mob kills per winning run, listed inline. Empty when the sweep
     *  produced no wins. Useful for sanity-checking that winning runs are
     *  killing roughly the expected number of mobs - too few means the agent
     *  ran past everything; too many means it ground out the floor instead
     *  of descending. */
    private static void printWinKills() {
        if (WIN_LABELS.isEmpty()) {
            System.out.println("[regression] no wins this sweep");
            return;
        }
        long total = 0;
        for (int k : WIN_KILLS) total += k;
        double avg = total / (double) WIN_KILLS.size();
        System.out.printf("[regression] kills per winning run (%d wins, avg %.1f):%n",
                WIN_KILLS.size(), avg);
        for (int i = 0; i < WIN_LABELS.size(); i++) {
            System.out.printf("    %-22s  kills=%d%n", WIN_LABELS.get(i), WIN_KILLS.get(i));
        }
    }

    /** Where the agent is losing HP across the whole sweep — by the
     *  cause-medium ("blow", "magic", "throw", "fire", "wall-slam", ...)
     *  and by the source mob type. Tells you whether to tune mob melee,
     *  ranged DOTs, environment, or a specific species. */
    private static void printIncomingDamage() {
        long totalMedium = DMG_TAKEN_BY_MEDIUM.values().stream()
                .mapToLong(Long::longValue).sum();
        if (totalMedium == 0) return;
        System.out.println("[regression] agent HP lost by mechanism "
                + "(total " + totalMedium + " across all runs):");
        DMG_TAKEN_BY_MEDIUM.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.printf("    %-16s %6.1f%%  (%d)%n",
                        e.getKey(), 100.0 * e.getValue() / totalMedium, e.getValue()));
        long totalSrc = DMG_TAKEN_BY_SOURCE.values().stream()
                .mapToLong(Long::longValue).sum();
        if (totalSrc == 0) return;
        System.out.println("[regression] agent HP lost by attacker:");
        DMG_TAKEN_BY_SOURCE.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.printf("    %-22s %6.1f%%  (%d)%n",
                        e.getKey(), 100.0 * e.getValue() / totalSrc, e.getValue()));
        int totalDeaths = DEATHS_BY_KILLER.values().stream()
                .mapToInt(Integer::intValue).sum();
        if (totalDeaths == 0) return;
        System.out.println("[regression] agent deaths by killer (" + totalDeaths + " total):");
        DEATHS_BY_KILLER.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.printf("    %-22s %6.1f%%  (%d kills)%n",
                        e.getKey(), 100.0 * e.getValue() / totalDeaths, e.getValue()));
    }

    /** HP the agent lost at each depth: as a share of ALL damage taken across
     *  the sweep (rows sum to ~100%), and as HP-lost-per-visit ({@code
     *  DEPTH_TURN_N} runs reached each depth). The share is confounded by how
     *  many runs visit a depth; per-visit removes that so it reads as the true
     *  per-floor lethality. */
    private static void printDamageTakenByDepth() {
        long total = 0;
        for (long v : DMG_TAKEN_BY_DEPTH) total += v;
        if (total == 0) return;
        System.out.println("[regression] damage TAKEN by depth "
                + "(share of " + total + " total; HP lost per visit):");
        for (int d = 0; d < DMG_TAKEN_BY_DEPTH.length; d++) {
            if (DMG_TAKEN_BY_DEPTH[d] == 0) continue;
            int visits = d < DEPTH_TURN_N.length ? DEPTH_TURN_N[d] : 0;
            double perVisit = visits > 0 ? (double) DMG_TAKEN_BY_DEPTH[d] / visits : 0.0;
            System.out.printf("    depth %2d   %5.1f%%  (%d)   %7.1f/visit  (n=%d)   %s%n",
                    d + 1, 100.0 * DMG_TAKEN_BY_DEPTH[d] / total,
                    DMG_TAKEN_BY_DEPTH[d], perVisit, visits, top3DealersAt(d));
        }
    }

    /** Per-depth incoming damage by ENEMY mob, with the two non-enemy buckets
     *  factored out: mirror matches ({@code ENEMY_PLAYER_*}) and environmental
     *  damage ({@code ENV}). Answers "which real enemies hurt us at each
     *  depth" - the by-depth top-3 above is dominated by mirrors/ENV on many
     *  floors, hiding the actual species doing the work. Shares are of the
     *  depth's ENEMY damage; the header shows how much was mirror/ENV. */
    private static void printNonMirrorDealersByDepth() {
        if (DMG_SRC_BY_DEPTH.isEmpty()) return;
        System.out.println("[regression] HP lost per depth by enemy "
                + "(mirror ENEMY_PLAYER_* and ENV split out):");
        for (int d = 0; d < DMG_TAKEN_BY_DEPTH.length; d++) {
            java.util.Map<String, Long> m = DMG_SRC_BY_DEPTH.get(d);
            if (m == null || m.isEmpty()) continue;
            long mirror = 0, env = 0, enemy = 0;
            for (java.util.Map.Entry<String, Long> e : m.entrySet()) {
                if (e.getKey().startsWith("ENEMY_PLAYER_")) mirror += e.getValue();
                else if (e.getKey().equals("ENV"))          env    += e.getValue();
                else                                        enemy  += e.getValue();
            }
            System.out.printf("    depth %2d   enemies %6d HP   (mirror %d, env %d)%n",
                    d + 1, enemy, mirror, env);
            if (enemy == 0) continue;
            final long enemyTotal = enemy;
            m.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("ENEMY_PLAYER_")
                            && !e.getKey().equals("ENV"))
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(6)
                    .forEach(e -> System.out.printf("        %-26s %6.1f%%  (%d)%n",
                            e.getKey(), 100.0 * e.getValue() / enemyTotal, e.getValue()));
        }
    }

    /** The three source-mob types that dealt the most damage at depth index
     *  {@code d}, each with its share of that depth's incoming damage. */
    private static String top3DealersAt(int d) {
        java.util.Map<String, Long> m = DMG_SRC_BY_DEPTH.get(d);
        if (m == null || m.isEmpty()) return "";
        long depthTotal = m.values().stream().mapToLong(Long::longValue).sum();
        return m.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> String.format("%s %.0f%%", e.getKey(),
                        100.0 * e.getValue() / depthTotal))
                .collect(java.util.stream.Collectors.joining(", "));
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
        grantReviveCharms(g.agent, GameBalance.tuning().startingReviveCharms());
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
        long sumMeleeAttacks = 0, sumMeleeDamage = 0;
        long sumWandsFired   = 0, sumWandDamage  = 0;
        long sumBombsThrown  = 0, sumBombDamage  = 0;
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
            sumMeleeAttacks += r.meleeAttacks;
            sumMeleeDamage  += r.meleeDamage;
            sumWandsFired   += r.wandsFired;
            sumWandDamage   += r.wandDamage;
            sumBombsThrown  += r.bombsThrown;
            sumBombDamage   += r.bombDamage;
        }
        System.out.printf(
            "%-8s %-7s runs=%d  W/D/T=%d/%d/%d  depth max=%d avg=%.1f  lvl max=%d avg=%.1f  turns max=%d avg=%.0f%n",
            cls.name(), diff.name(), n, win, death, timeout,
            maxDepth, sumDepth / (double) n,
            maxLvl,   sumLvl   / (double) n,
            maxTurns, sumTurns / (double) n);
        // Combat-mix line: per-run averages of attack count + total damage
        // by source. Lets you see, e.g., that a class is hitting often but
        // doing low damage, or leaning on wands more than melee.
        System.out.printf(
            "         combat       melee atk=%5.1f dmg=%6.1f   wands fired=%5.1f dmg=%6.1f   bombs thrown=%5.1f dmg=%6.1f%n",
            sumMeleeAttacks / (double) n, sumMeleeDamage / (double) n,
            sumWandsFired   / (double) n, sumWandDamage  / (double) n,
            sumBombsThrown  / (double) n, sumBombDamage  / (double) n);
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
