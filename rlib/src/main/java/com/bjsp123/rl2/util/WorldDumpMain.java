package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Headless world generator + YAML dumper. Not shipping code.
 *
 * <p>Reads the same data files that {@code Rl2Game.create} reads (without
 * libGDX), builds a {@link World} via {@link WorldTopology#build}, and writes
 * the {@link WorldYamlDump} output to {@code world_dump.yaml} in the working
 * directory (and stdout).
 *
 * <p>Wired up as a Gradle task - see {@code rlib/build.gradle}'s
 * {@code dumpWorld} task. Examples:
 * <pre>
 *   ./gradlew :rlib:dumpWorld                  # random seed
 *   ./gradlew :rlib:dumpWorld --args="ABCDEF"  # six-letter seed code
 *   ./gradlew :rlib:dumpWorld --args="12345"   # raw long seed
 * </pre>
 */
public final class WorldDumpMain {

    private WorldDumpMain() {}

    public static void main(String[] args) throws IOException {
        long seed = parseSeed(args.length > 0 ? args[0] : null);
        Path assets = ArenaHarness.locateAssetsDir();
        ArenaHarness.loadData(assets);

        int width  = 48;
        int height = 48;
        Random rng = new Random(seed);
        World world = new World();
        world.unique = new UniqueTracker();
        world.seed   = seed;
        world.levels = WorldTopology.build(width, height, rng, world.unique);

        String yaml = WorldYamlDump.dump(world);
        Path out = Paths.get("results", "world_dump.yaml").toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, yaml);
        System.out.println("[rl2] seed " + SeedCode.encode(seed) + " (long=" + seed + ")");
        System.out.println("[rl2] wrote " + out);
        System.out.println("---- world_dump.yaml ----");
        System.out.println(yaml);
    }

    /** {@code arg} is a six-letter seed code, a raw long, or null (random).
     *  Six-letter codes route through {@link SeedCode#decode}; long-style args
     *  go through {@link Long#parseLong}; null picks a random code-space seed
     *  so the printed code is always typeable. */
    private static long parseSeed(String arg) {
        if (arg == null || arg.isEmpty()) {
            return new Random().nextLong() % SeedCode.SPACE;
        }
        if (SeedCode.isValid(arg)) return SeedCode.decode(arg);
        return Long.parseLong(arg);
    }

}
