package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.ui.ItemLore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * [DEV / DIAGNOSTIC] Headless ItemLore dump. Not shipping code.
 *
 * <p>Loads registries, builds the named items at +0 and +5, and prints the
 * formatter output so we can eyeball the "initial stats + scaling" lore
 * without booting the desktop UI.
 *
 * <p>Gradle: {@code ./gradlew :rgame:dumpItemLore --args="SWORD WAND_FIRE JADE_BULL POTION_POISON"}
 */
public final class ItemLoreDumpMain {

    private ItemLoreDumpMain() {}

    public static void main(String[] args) throws IOException {
        Path assets = locateAssetsDir();
        loadData(assets);

        String[] types = args.length > 0
                ? args
                : new String[] { "SWORD", "WAND_FIRE", "WAND_MAGIC_MISSILE",
                        "JADE_BULL", "JADE_CRAB", "FROG",
                        "SCALE_MAIL", "POTION_POISON", "CHERRY_BOMB",
                        "POWER_ORB", "HEALING_POTION" };

        for (String type : types) {
            for (int level : new int[] { 0, 5 }) {
                Item it;
                try {
                    it = ItemFactory.build(type);
                } catch (Exception e) {
                    System.out.println("=== " + type + " +" + level + " === (unknown type)");
                    continue;
                }
                if (it == null) {
                    System.out.println("=== " + type + " +" + level + " === (build returned null)");
                    continue;
                }
                it.level = level;
                System.out.println("=== " + type + " +" + level + " ===");
                System.out.println(ItemLore.describeDetails(it));
                System.out.println();
            }
        }
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
        if (Files.exists(strings)) TextCatalog.load(Files.readString(strings));
        Path config = assets.resolve("config.csv");
        if (Files.exists(config)) GameBalance.load(Files.readString(config));
        Registries.loadMobs(Files.readString(assets.resolve("mobs.csv")));
        Registries.loadItems(Files.readString(assets.resolve("items.csv")));
        Path themed = assets.resolve("themedrooms.csv");
        if (Files.exists(themed)) Registries.loadThemedRooms(Files.readString(themed));
    }
}
