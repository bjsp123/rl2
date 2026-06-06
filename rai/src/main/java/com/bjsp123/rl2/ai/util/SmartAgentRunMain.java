package com.bjsp123.rl2.ai.util;

import com.bjsp123.rl2.ai.RaiBootstrap;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;
import com.bjsp123.rl2.util.SeedCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Headless smart-agent benchmark. Generates a real {@link World}
 * via the standard {@link WorldTopology#build} pipeline, drops one SMART-behaviour
 * agent in (one of the three player classes, kit included), and drives the turn
 * loop until the agent dies, reaches the deepest level, or hits the per-run tick
 * budget. Writes a one-row-per-run summary to {@code smart_agent_run.csv}.
 *
 * <p>Wired up as Gradle task {@code :rai:runSmartAgent}. Examples:
 * <pre>
 *   ./gradlew :rai:runSmartAgent                # 10 runs, all three classes
 *   ./gradlew :rai:runSmartAgent --args="50"    # 50 runs per class
 * </pre>
 */
public final class SmartAgentRunMain {

    private static final int    DEFAULT_RUNS_PER_CLASS = 10;
    private static final int    TICK_BUDGET = 200_000;
    private static final int    WORLD_W = 48;
    private static final int    WORLD_H = 48;

    private SmartAgentRunMain() {}

    public static void main(String[] args) throws IOException {
        int runsPerClass = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RUNS_PER_CLASS;
        Path assets = locateAssetsDir();
        loadData(assets);
        RaiBootstrap.init();

        Path csvPath = Paths.get("results", "smart_agent_run.csv").toAbsolutePath();
        Files.createDirectories(csvPath.getParent());
        StringBuilder csv = new StringBuilder(
                "seed,class,depth_reached,max_depth,turns_survived,hp_remaining,outcome\n");

        Random rng = new Random();
        for (Mob.CharacterClass cls : Mob.CharacterClass.values()) {
            for (int i = 0; i < runsPerClass; i++) {
                long seed = rng.nextLong() % SeedCode.SPACE;
                RunResult r = runOne(seed, cls);
                csv.append(seed).append(',').append(cls.name()).append(',')
                        .append(r.depthReached).append(',').append(r.maxDepth).append(',')
                        .append(r.turnsSurvived).append(',')
                        .append(String.format("%.1f", r.hpRemaining)).append(',')
                        .append(r.outcome).append('\n');
                System.out.printf("[%s] seed=%s depth=%d/%d turns=%d hp=%.1f outcome=%s%n",
                        cls.name(), SeedCode.encode(seed),
                        r.depthReached, r.maxDepth, r.turnsSurvived, r.hpRemaining, r.outcome);
            }
        }

        Files.writeString(csvPath, csv.toString());
        System.out.println("[rai] wrote " + csvPath);
    }

    private static RunResult runOne(long seed, Mob.CharacterClass cls) {
        Random rng = new Random(seed);
        World world = new World();
        world.unique = new UniqueTracker();
        world.seed = seed;
        world.levels = WorldTopology.build(WORLD_W, WORLD_H, rng, world.unique);
        world.currentLevelIndex = 0;
        for (Level lvl : world.levels) lvl.world = world;

        Level start = world.currentLevel();
        Point spawn = findAnyFloor(start, rng);
        Mob agent = MobFactory.player(spawn, cls);
        agent.behavior = Mob.Behavior.SMART;
        start.mobs.add(agent);

        int turnsSurvived = 0;
        String outcome = "TIMEOUT";
        int maxDepth = world.levels.length - 1;
        int depthReached = 0;
        for (int t = 0; t < TICK_BUDGET; t++) {
            Level cur = world.currentLevel();
            TurnSystem.tick(cur);
            turnsSurvived++;
            depthReached = Math.max(depthReached, world.currentLevelIndex);
            if (agent.hp <= 0) { outcome = "DEATH"; break; }
            if (world.currentLevelIndex >= maxDepth) { outcome = "WIN"; break; }
        }

        RunResult r = new RunResult();
        r.depthReached = depthReached;
        r.maxDepth = maxDepth;
        r.turnsSurvived = turnsSurvived;
        r.hpRemaining = agent.hp;
        r.outcome = outcome;
        return r;
    }

    private static Point findAnyFloor(Level level, Random rng) {
        for (int tries = 0; tries < 500; tries++) {
            int x = 1 + rng.nextInt(level.width - 2);
            int y = 1 + rng.nextInt(level.height - 2);
            if (!level.tiles[x][y].blocksMovement()) return new Point(x, y);
        }
        return new Point(level.width / 2, level.height / 2);
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

    private static final class RunResult {
        int depthReached;
        int maxDepth;
        int turnsSurvived;
        double hpRemaining;
        String outcome;
    }
}
