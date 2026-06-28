package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * [DEV / DIAGNOSTIC] Shared headless-harness helpers for the {@code *Main}
 * arena / ranking / world-dump entrypoints. Not shipping code.
 *
 * <p>These entrypoints (e.g. {@link Arena1vNRankMain}, {@link FullArenaRankMain},
 * {@link MobPowerRankMain}, {@link BombLoadoutArenaMain}, {@link WorldDumpMain})
 * all need to find the assets directory, load the CSV registries, pick a pool
 * of fightable mob types, build fighters, and strip items from inventories the
 * same way. This class owns that shared boilerplate so each main keeps only its
 * unique fight matrix and output logic.
 */
public final class ArenaHarness {

    private ArenaHarness() {}

    /** Walk up from the working directory until an {@code assets/data} folder
     *  appears. Lets the headless tasks be invoked from anywhere in the repo
     *  without hard-coding a relative path. */
    public static Path locateAssetsDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("assets").resolve("data");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "Could not find assets/data starting from " + cwd);
    }

    /** Load the same data files that {@code Rl2Game.create} reads (without
     *  libGDX): strings, config, mobs, items, themed rooms. {@code strings.csv},
     *  {@code config.csv}, and {@code themedrooms.csv} are optional; {@code
     *  mobs.csv} and {@code items.csv} are required. Loading the optional files
     *  is harmless for mains that don't use them. */
    public static void loadData(Path assets) throws IOException {
        Path strings = assets.resolve("strings.csv");
        if (Files.exists(strings))
            com.bjsp123.rl2.logic.TextCatalog.load(Files.readString(strings));
        Path config = assets.resolve("config.csv");
        if (Files.exists(config))
            GameBalance.load(Files.readString(config));
        Registries.loadMobs(Files.readString(assets.resolve("mobs.csv")));
        Registries.loadItems(Files.readString(assets.resolve("items.csv")));
        Path gems = assets.resolve("gems.csv");
        if (Files.exists(gems))
            Registries.loadGems(Files.readString(gems));
        Path themed = assets.resolve("themedrooms.csv");
        if (Files.exists(themed))
            Registries.loadThemedRooms(Files.readString(themed));
        Path recipes = assets.resolve("recipes.csv");
        if (Files.exists(recipes))
            Registries.loadRecipes(Files.readString(recipes));
    }

    /** Mob types meaningful to fight: anything with non-zero HP and a melee or
     *  ranged damage path. Skips inanimate scenery (anthills, statues) that
     *  wouldn't fight back. The returned list is sorted by type name.
     *
     *  @param includePlayers when {@code true}, the three {@code PLAYER_*}
     *      classes are kept in the pool; when {@code false}, they are skipped. */
    public static List<String> fightableMobs(boolean includePlayers) {
        List<String> out = new ArrayList<>();
        for (String type : Registries.mobTypes()) {
            if (!includePlayers && type.startsWith("PLAYER_")) continue;
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

    /** Build a fighter for {@code type}. {@link MobFactory#spawn} returns null
     *  for {@code PLAYER_*} rows, so those route through
     *  {@link MobFactory#player} (parsing the class out of the type suffix);
     *  everything else spawns normally. */
    public static Mob buildFighter(String type) {
        if (type.startsWith("PLAYER_")) {
            String classKey = type.substring("PLAYER_".length());
            Mob.CharacterClass cls = Mob.CharacterClass.valueOf(classKey);
            return MobFactory.player(new Point(0, 0), cls);
        }
        return MobFactory.spawn(type, new Point(0, 0));
    }

    /** Remove every bag item whose {@code type} matches {@code typeKey}.
     *  Used by the arena runners to exclude items that don't make sense in a
     *  one-room single-level world (e.g. TELEPORT_ORB, whose "scatter across
     *  the dungeon" collapses to "relocate within the arena" here). */
    public static void stripFromInventory(Mob m, String typeKey) {
        if (m == null || m.inventory == null || m.inventory.bag == null) return;
        m.inventory.bag.removeIf(it -> it != null && typeKey.equals(it.type));
    }

    /** Win-percentage counting draws as half wins, matching the rank tables.
     *  Returns 0.0 when there are no fights. */
    public static double winPct(int wins, int losses, int draws) {
        int total = wins + losses + draws;
        return total == 0 ? 0.0 : 100.0 * (wins + 0.5 * draws) / total;
    }
}
