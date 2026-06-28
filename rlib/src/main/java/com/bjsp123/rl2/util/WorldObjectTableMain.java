package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * [DEV / DIAGNOSTIC] Headless object-census tool. Not shipping code.
 *
 * <p>Generates a batch of worlds (the same {@code WorldTopology.build} pipeline
 * {@code Rl2Game.create} uses, minus libGDX) and tabulates every generated
 * <em>item</em> by four axes:
 * <ul>
 *   <li><b>object type</b> - the items.csv {@code type} key (gems show as
 *       {@code GEM:<species>} since they're procedural and carry no type).</li>
 *   <li><b>enchantment</b> - the instance plus-level ({@code +N}); a brand,
 *       when present, is appended as {@code +N of <Brand>}.</li>
 *   <li><b>depth</b> - the dungeon depth of the level the object generated on.</li>
 *   <li><b>drop kind</b> - how it entered the world:
 *     <ul>
 *       <li>{@code mob} - carried by a mob (bag or equipped); becomes loot on death.</li>
 *       <li>{@code unique} - a floor item sitting inside a themed/curated room
 *           (a {@link Level.RoomSnapshot} whose {@code kind} is set); these are
 *           the set-piece rewards, not ambient scatter.</li>
 *       <li>{@code level} - ambient floor scatter / gems placed by the level
 *           generator outside any themed room.</li>
 *     </ul></li>
 * </ul>
 *
 * <p>Writes a full CSV to {@code results/world_objects.csv} and prints a
 * markdown census table plus pivot summaries to stdout.
 *
 * <pre>
 *   ./gradlew :rlib:objectTable                # 10 worlds (the default)
 *   ./gradlew :rlib:objectTable --args="25"    # 25 worlds
 * </pre>
 */
public final class WorldObjectTableMain {

    private WorldObjectTableMain() {}

    /** Field separator for the census map key - a control char that can't appear
     *  in a type name, enchantment string, or drop kind. */
    private static final String SEP = "|";

    /** One tallied object instance bucket - the 5-tuple key plus a running count.
     *  {@code source} is the specific origin: the mob species for {@code mob} /
     *  {@code unique-mob} drops, the themed-room {@code kind} for
     *  {@code unique-room} drops, and {@code "-"} for ambient {@code level}
     *  scatter. */
    private static final class Row {
        final String type;
        final String ench;
        final int depth;
        final String drop;
        final String source;
        long count;
        Row(String type, String ench, int depth, String drop, String source) {
            this.type = type; this.ench = ench; this.depth = depth;
            this.drop = drop; this.source = source;
        }
    }

    public static void main(String[] args) throws IOException {
        int worlds = args.length > 0 ? Integer.parseInt(args[0].trim()) : 10;
        Path assets = ArenaHarness.locateAssetsDir();
        ArenaHarness.loadData(assets);
        // ArenaHarness.loadData doesn't load brands, so without this the 1-in-5
        // brand roll on mob gear would always no-op (empty brand pool) and the
        // enchantment axis would never show an "of <Brand>" suffix.
        Path brands = assets.resolve("brands.csv");
        if (Files.exists(brands)) {
            com.bjsp123.rl2.logic.Registries.loadBrands(Files.readString(brands));
        }

        int width = 48, height = 48;
        // Fixed base seed so the census is reproducible run to run.
        Random seedRng = new Random(0x0B7EC75L);
        List<String> seedCodes = new ArrayList<>();

        // 4-tuple census keyed for sorted output, plus pivot tallies.
        Map<String, Row> census = new TreeMap<>();
        Map<String, Long> byDrop  = new TreeMap<>();
        Map<Integer, Long> byDepth = new TreeMap<>();
        Map<String, Long> byEnch  = new TreeMap<>();
        // Gems are excluded from the main item census, so tally them separately:
        // every gem generated anywhere in the world, by drop source and species.
        Map<String, Long> gemByDrop    = new TreeMap<>();
        Map<String, Long> gemBySpecies = new TreeMap<>();
        // Every mob present at generation, by species (all of them - hostiles,
        // neutrals, retainers, uniques, inanimate hills, beacon mobs).
        Map<String, Long> mobByType    = new TreeMap<>();
        long levelCount = 0, objectCount = 0;

        for (int w = 0; w < worlds; w++) {
            long seed = seedRng.nextLong();
            seedCodes.add(SeedCode.encode(Math.floorMod(seed, SeedCode.SPACE)));
            Random rng = new Random(seed);
            World world = new World();
            world.unique = new UniqueTracker();
            world.levels = WorldTopology.build(width, height, rng, world.unique);
            if (world.levels == null) continue;

            for (Level lvl : world.levels) {
                if (lvl == null || lvl.tiles == null) continue;   // topology holes
                levelCount++;

                // Floor items: only a GENUINELY unique room (perLevelUnique
                // beacons + the special-cased GEM_HEARTH) counts as unique-room.
                // Ordinary regular themed rooms fall back into level scatter -
                // they're stamped on nearly every room, so they're not "unique".
                // The room kind (if any) is kept as the source either way.
                if (lvl.items != null) {
                    for (Item it : lvl.items) {
                        if (it == null) continue;
                        String roomKind = floorRoomKind(lvl, it);   // themed-room kind or null
                        String drop   = isGenuinelyUniqueRoom(roomKind) ? "unique-room" : "level";
                        String source = roomKind != null ? roomKind : "-";
                        objectCount += tally(census, byDrop, byDepth, byEnch, it,
                                lvl.depth, drop, source);
                    }
                }
                // Mob-carried items (bag + equipped) - split unique-flagged mobs
                // (named bosses / one-shot species) from ordinary mobs. Source is
                // the carrying mob's species. Mobs whose loot is suppressed on
                // death (dropType=NOTHING_AT_ALL, e.g. the ENEMY_PLAYER_* beacon
                // bosses, or per-instance suppressLoot) never feed the player, so
                // their carried gear is NOT a real drop and is skipped.
                if (lvl.mobs != null) {
                    for (Mob m : lvl.mobs) {
                        if (m == null) continue;
                        mobByType.merge(m.mobType != null ? m.mobType : "?", 1L, Long::sum);
                        if (m.inventory == null || !dropsLootOnDeath(m)) continue;
                        String drop   = m.unique ? "unique-mob" : "mob";
                        String source = m.mobType != null ? m.mobType : "?";
                        for (Item it : m.inventory.bag) {
                            objectCount += tally(census, byDrop, byDepth, byEnch, it,
                                    lvl.depth, drop, source);
                        }
                        for (Item it : m.inventory.allEquipped()) {
                            objectCount += tally(census, byDrop, byDepth, byEnch, it,
                                    lvl.depth, drop, source);
                        }
                    }
                }

                // Gem census: count EVERY gem generated on the level, regardless
                // of the main census's gem/powerup and loot-suppression filters,
                // so this is the true "all gems in the world" total.
                if (lvl.items != null) {
                    for (Item it : lvl.items) {
                        if (it == null || !it.isGem()) continue;
                        String drop = isGenuinelyUniqueRoom(floorRoomKind(lvl, it))
                                ? "unique-room" : "level";
                        countGem(gemByDrop, gemBySpecies, it, drop);
                    }
                }
                if (lvl.mobs != null) {
                    for (Mob m : lvl.mobs) {
                        if (m == null || m.inventory == null) continue;
                        String drop = m.unique ? "unique-mob" : "mob";
                        for (Item it : m.inventory.bag) countGem(gemByDrop, gemBySpecies, it, drop);
                        for (Item it : m.inventory.allEquipped()) countGem(gemByDrop, gemBySpecies, it, drop);
                    }
                }
            }
        }

        writeCsv(census, worlds);
        printReport(worlds, levelCount, objectCount, seedCodes, census,
                byDrop, byDepth, byEnch);
        printGemReport(worlds, gemByDrop, gemBySpecies);
        printMobReport(worlds, mobByType);
    }

    /** Per-world average count of each mob species present at generation,
     *  sorted by descending frequency. */
    private static void printMobReport(int worlds, Map<String, Long> mobByType) {
        double wpf = Math.max(1, worlds);
        long total = 0;
        for (long v : mobByType.values()) total += v;
        System.out.println("== Mobs by species (per world) ==");
        System.out.printf("  TOTAL mobs/world: %.2f   (%d mobs across %d worlds, %d species)%n",
                total / wpf, total, worlds, mobByType.size());
        mobByType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %-26s %7.2f%n", e.getKey(), e.getValue() / wpf));
        System.out.println();
    }

    /** Tally one gem (skips non-gems) into the per-source and per-species maps. */
    private static void countGem(Map<String, Long> gemByDrop,
                                 Map<String, Long> gemBySpecies, Item it, String drop) {
        if (it == null || !it.isGem()) return;
        long n = Math.max(1, it.count);
        gemByDrop.merge(drop, n, Long::sum);
        gemBySpecies.merge(String.valueOf(it.gemSpecies), n, Long::sum);
    }

    private static void printGemReport(int worlds, Map<String, Long> gemByDrop,
                                       Map<String, Long> gemBySpecies) {
        double wpf = Math.max(1, worlds);
        long total = 0;
        for (long v : gemByDrop.values()) total += v;
        System.out.println("== Gems (all gems generated; excluded from the item census above) ==");
        System.out.printf("  TOTAL gems/world: %.2f   (%d gems across %d worlds)%n",
                total / wpf, total, worlds);
        System.out.println("  by source (per world):");
        gemByDrop.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("    %-12s %8.2f%n", e.getKey(), e.getValue() / wpf));
        System.out.println("  by species (per world):");
        gemBySpecies.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("    %-12s %8.2f%n", e.getKey(), e.getValue() / wpf));
        System.out.println();
    }

    /** Add one item (honouring its stack {@code count}) to every tally. Returns
     *  the number of object instances added so the caller can total them. */
    private static long tally(Map<String, Row> census, Map<String, Long> byDrop,
                              Map<Integer, Long> byDepth, Map<String, Long> byEnch,
                              Item it, int depth, String drop, String source) {
        if (it == null) return 0;
        // Excluded by request: procedural gems and walk-over POWERUP pills
        // (HEALTH/CHARGE/XP) - classified by stats, never by type name.
        if (it.isGem() || it.useBehavior == Item.UseBehavior.POWERUP) return 0;
        long n = Math.max(1, it.count);
        String type = displayType(it);
        String ench = enchantment(it);
        String key = type + SEP + ench + SEP + depth + SEP + drop + SEP + source;
        census.computeIfAbsent(key, k -> new Row(type, ench, depth, drop, source)).count += n;
        byDrop.merge(drop, n, Long::sum);
        byDepth.merge(depth, n, Long::sum);
        byEnch.merge(ench, n, Long::sum);
        return n;
    }

    /** Mirrors {@link com.bjsp123.rl2.logic.LootSystem#dropLootOnDeath}'s
     *  suppression gates: a mob feeds the player on death unless it carries the
     *  per-instance {@code suppressLoot} flag or its species declares
     *  {@code dropType=NOTHING_AT_ALL} (the ENEMY_PLAYER_* beacon bosses). */
    private static boolean dropsLootOnDeath(Mob m) {
        if (m.suppressLoot) return false;
        com.bjsp123.rl2.logic.MobDefinition def =
                com.bjsp123.rl2.logic.Registries.mob(m.mobType);
        return def == null || !dropsNothingAtAll(def);
    }

    private static boolean dropsNothingAtAll(com.bjsp123.rl2.logic.MobDefinition def) {
        if (def.dropTypes == null) return false;
        for (String t : def.dropTypes) {
            if ("NOTHING_AT_ALL".equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    /** {@code "[EQUIPMENT|POTION] x2.5"}-style summary of a species' rolled-drop
     *  parameters (the mobs.csv {@code dropType} / {@code dropAmount} columns),
     *  or {@code ""} when the source isn't a catalogued mob (e.g. a room kind). */
    private static String mobDropParams(String mobType) {
        com.bjsp123.rl2.logic.MobDefinition def =
                com.bjsp123.rl2.logic.Registries.mob(mobType);
        if (def == null) return "";
        String types = def.dropTypes == null || def.dropTypes.isEmpty()
                ? "(none)" : String.join("|", def.dropTypes);
        return String.format(java.util.Locale.ROOT, "%s x%.2f", types, def.dropAmount);
    }

    /** The themed-room {@code kind} of the room a floor item sits inside (the
     *  source of a curated {@code unique-room} drop), or {@code null} when the
     *  item is ambient {@code level} scatter outside any themed room. */
    private static String floorRoomKind(Level lvl, Item it) {
        if (it.location == null || lvl.rooms == null) return null;
        int x = it.location.tileX(), y = it.location.tileY();
        for (Level.RoomSnapshot r : lvl.rooms) {
            if (r.kind == null) continue;
            if (x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h) return r.kind;
        }
        return null;
    }

    /** True only for genuinely-unique themed rooms: those flagged {@code unique}
     *  or {@code perLevelUnique} in themedrooms.csv (the BEACON rooms), plus the
     *  special-cased GEM_HEARTH (tagged directly, not a themedrooms.csv row).
     *  Ordinary regular themed rooms (POTION_ROOM, ARMORY, ROUND, SUBROOM, ...)
     *  are stamped on nearly every room and so are NOT unique. */
    private static boolean isGenuinelyUniqueRoom(String kind) {
        if (kind == null) return false;
        if (kind.equals("GEM_HEARTH")) return true;
        com.bjsp123.rl2.logic.ThemedRoomDefinition d =
                com.bjsp123.rl2.logic.Registries.themedRoom(kind);
        return d != null && (d.unique || d.perLevelUnique != null);
    }

    /** items.csv type, or {@code GEM:<species>} for procedural gems. */
    private static String displayType(Item it) {
        if (it.type != null && !it.type.isEmpty()) return it.type;
        if (it.gemSpecies != null) return "GEM:" + it.gemSpecies;
        return "?";
    }

    /** {@code +N}, with the brand name appended when the instance is branded. */
    private static String enchantment(Item it) {
        String base = "+" + Math.max(0, it.level);
        if (it.brand != null) {
            String bn = it.brand.name != null ? it.brand.name : it.brand.brand;
            if (bn != null && !bn.isEmpty()) return base + " of " + bn;
        }
        return base;
    }

    private static void writeCsv(Map<String, Row> census, int worlds) throws IOException {
        double wpf = Math.max(1, worlds);
        StringBuilder sb = new StringBuilder(
                "type,enchantment,depth,drop_kind,source,count,per_world\n");
        for (Row r : census.values()) {
            sb.append(csv(r.type)).append(',').append(csv(r.ench)).append(',')
              .append(r.depth).append(',').append(r.drop).append(',')
              .append(csv(r.source)).append(',')
              .append(r.count).append(',')
              .append(String.format(java.util.Locale.ROOT, "%.3f", r.count / wpf))
              .append('\n');
        }
        Path out = Paths.get("results", "world_objects.csv").toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println("[rl2] wrote " + out);
    }

    /** Per-world totals grouped into the broad gameplay buckets the user asked
     *  for: equipment (weapon/offhand/armor/amulet), wands, bombs, potions, food,
     *  with everything else (tools, orbs, thrown, generic items) as "other". The
     *  category comes from each type's {@code items.csv} row. */
    private static void printCategoryBreakdown(Map<String, Row> census, double wpf) {
        // Preserve a fixed display order.
        String[] order = {"equipment", "wand", "bomb", "potion", "food", "other"};
        Map<String, Long> byBucket = new java.util.LinkedHashMap<>();
        for (String b : order) byBucket.put(b, 0L);
        for (Row r : census.values()) {
            byBucket.merge(bucketOf(r.type), r.count, Long::sum);
        }
        System.out.println("== By category (per world) ==");
        long total = 0;
        for (long v : byBucket.values()) total += v;
        for (String b : order) {
            System.out.printf("  %-10s %8.2f%n", b, byBucket.get(b) / wpf);
        }
        System.out.printf("  %-10s %8.2f%n", "TOTAL", total / wpf);
        System.out.println();
    }

    /** Equipment (weapon/offhand/armor/amulet) split by specific slot category
     *  and by whether the instance is enchanted - an instance counts as enchanted
     *  when it carries a plus-level or a brand, i.e. its enchantment string is
     *  anything other than the bare {@code "+0"}. */
    private static void printEquipmentBreakdown(Map<String, Row> census, double wpf) {
        String[] cats = {"WEAPON", "OFFHAND", "ARMOR", "AMULET"};
        Map<String, long[]> tally = new java.util.LinkedHashMap<>();   // cat -> {plain, enchanted}
        for (String c : cats) tally.put(c, new long[2]);
        for (Row r : census.values()) {
            com.bjsp123.rl2.logic.ItemDefinition def =
                    com.bjsp123.rl2.logic.Registries.item(r.type);
            if (def == null || def.inventoryCategory == null) continue;
            String cat = def.inventoryCategory.name();
            long[] cell = tally.get(cat);
            if (cell == null) continue;   // not one of the four equipment slots
            boolean enchanted = !r.ench.equals("+0");
            cell[enchanted ? 1 : 0] += r.count;
        }
        System.out.println("== Equipment by slot x enchanted (per world) ==");
        System.out.printf("  %-10s %8s %10s %8s%n", "slot", "plain", "enchanted", "total");
        long pAll = 0, eAll = 0;
        for (String c : cats) {
            long[] cell = tally.get(c);
            pAll += cell[0]; eAll += cell[1];
            System.out.printf("  %-10s %8.2f %10.2f %8.2f%n",
                    c, cell[0] / wpf, cell[1] / wpf, (cell[0] + cell[1]) / wpf);
        }
        System.out.printf("  %-10s %8.2f %10.2f %8.2f%n",
                "TOTAL", pAll / wpf, eAll / wpf, (pAll + eAll) / wpf);
        System.out.println();
    }

    /** The {@link #printEquipmentBreakdown} table further split by drop source.
     *  One row per (slot, source) with plain / enchanted / total columns, a
     *  per-slot {@code (all)} subtotal, and a grand TOTAL. (slot, source)
     *  combinations with no items are skipped to keep the table compact. */
    private static void printEquipmentBySourceBreakdown(Map<String, Row> census, double wpf) {
        String[] cats    = {"WEAPON", "OFFHAND", "ARMOR", "AMULET"};
        String[] sources = {"level", "mob", "unique-mob", "unique-room"};
        // cat -> source -> {plain, enchanted}
        Map<String, Map<String, long[]>> tally = new java.util.LinkedHashMap<>();
        for (String c : cats) {
            Map<String, long[]> bySrc = new java.util.LinkedHashMap<>();
            for (String s : sources) bySrc.put(s, new long[2]);
            tally.put(c, bySrc);
        }
        for (Row r : census.values()) {
            com.bjsp123.rl2.logic.ItemDefinition def =
                    com.bjsp123.rl2.logic.Registries.item(r.type);
            if (def == null || def.inventoryCategory == null) continue;
            Map<String, long[]> bySrc = tally.get(def.inventoryCategory.name());
            if (bySrc == null) continue;            // not one of the four slots
            long[] cell = bySrc.get(r.drop);
            if (cell == null) continue;             // unexpected drop kind
            cell[r.ench.equals("+0") ? 0 : 1] += r.count;
        }
        System.out.println("== Equipment by slot x source x enchanted (per world) ==");
        System.out.printf("  %-9s %-13s %8s %10s %8s%n",
                "slot", "source", "plain", "enchanted", "total");
        long gP = 0, gE = 0;
        for (String c : cats) {
            Map<String, long[]> bySrc = tally.get(c);
            long sP = 0, sE = 0;
            for (String s : sources) {
                long[] cell = bySrc.get(s);
                if (cell[0] + cell[1] == 0) continue;   // skip empty (slot, source)
                sP += cell[0]; sE += cell[1];
                System.out.printf("  %-9s %-13s %8.2f %10.2f %8.2f%n",
                        c, s, cell[0] / wpf, cell[1] / wpf, (cell[0] + cell[1]) / wpf);
            }
            System.out.printf("  %-9s %-13s %8.2f %10.2f %8.2f%n",
                    c, "(all)", sP / wpf, sE / wpf, (sP + sE) / wpf);
            gP += sP; gE += sE;
        }
        System.out.printf("  %-9s %-13s %8.2f %10.2f %8.2f%n",
                "TOTAL", "", gP / wpf, gE / wpf, (gP + gE) / wpf);
        System.out.println();
    }

    /** Map an object type to a coarse gameplay bucket via its {@code items.csv}
     *  category. Unknown types (none here, since gems are pre-filtered) fall to
     *  "other". */
    private static String bucketOf(String type) {
        com.bjsp123.rl2.logic.ItemDefinition def =
                com.bjsp123.rl2.logic.Registries.item(type);
        if (def == null || def.inventoryCategory == null) return "other";
        return switch (def.inventoryCategory) {
            case WEAPON, OFFHAND, ARMOR, AMULET -> "equipment";
            case WAND   -> "wand";
            case BOMB   -> "bomb";
            case POTION -> "potion";
            case FOOD   -> "food";
            default     -> "other";   // TOOL, ORB, THROWN, ITEM, GEM
        };
    }

    /** Per-world instance totals grouped by {@link Row#source}, restricted to the
     *  given drop kind. Used by the by-specific-source breakdowns. */
    private static void printSourceBreakdown(String title, String dropKind,
                                             Map<String, Row> census, double wpf,
                                             boolean showDropParams) {
        Map<String, Long> bySource = new TreeMap<>();
        long total = 0;
        for (Row r : census.values()) {
            if (!r.drop.equals(dropKind)) continue;
            bySource.merge(r.source, r.count, Long::sum);
            total += r.count;
        }
        System.out.println(title + " (per world; " + bySource.size()
                + " sources, " + String.format(java.util.Locale.ROOT, "%.2f", total / wpf)
                + " items/world)");
        if (showDropParams) {
            System.out.printf("  %-28s %8s   %s%n", "source", "items/wd", "dropType x dropAmount");
        }
        // Sort sources by descending volume.
        List<Map.Entry<String, Long>> entries = new ArrayList<>(bySource.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> e : entries) {
            if (showDropParams) {
                System.out.printf("  %-28s %8.2f   %s%n",
                        e.getKey(), e.getValue() / wpf, mobDropParams(e.getKey()));
            } else {
                System.out.printf("  %-28s %8.2f%n", e.getKey(), e.getValue() / wpf);
            }
        }
        System.out.println();
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"")
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    private static void printReport(int worlds, long levelCount, long objectCount,
                                    List<String> seedCodes, Map<String, Row> census,
                                    Map<String, Long> byDrop, Map<Integer, Long> byDepth,
                                    Map<String, Long> byEnch) {
        double wpf = Math.max(1, worlds);   // divisor for per-world averages
        System.out.println();
        System.out.printf("[rl2] %d worlds, %d levels (%.1f/world), %d object instances "
                + "(%.1f/world) (48x48, DUNGEON_DEPTH=%d)%n",
                worlds, levelCount, levelCount / wpf, objectCount, objectCount / wpf,
                com.bjsp123.rl2.logic.GameBalance.DUNGEON_DEPTH);
        System.out.println("[rl2] seeds: " + String.join(" ", seedCodes));
        System.out.println("[rl2] all counts below are PER-WORLD averages across "
                + worlds + " worlds.");
        System.out.println("[rl2] EXCLUDED: gems and walk-over POWERUP pills.");
        System.out.println();

        printCategoryBreakdown(census, wpf);
        printEquipmentBreakdown(census, wpf);
        printEquipmentBySourceBreakdown(census, wpf);

        System.out.println("== By drop kind (per world) ==");
        for (Map.Entry<String, Long> e : byDrop.entrySet()) {
            System.out.printf("  %-12s %8.2f%n", e.getKey(), e.getValue() / wpf);
        }
        System.out.println();

        System.out.println("== By depth (per world) ==");
        for (Map.Entry<Integer, Long> e : byDepth.entrySet()) {
            System.out.printf("  depth %-3d %8.2f%n", e.getKey(), e.getValue() / wpf);
        }
        System.out.println();

        System.out.println("== By enchantment (per world) ==");
        for (Map.Entry<String, Long> e : byEnch.entrySet()) {
            System.out.printf("  %-18s %8.2f%n", e.getKey(), e.getValue() / wpf);
        }
        System.out.println();

        // Specific-source breakdowns the user asked for: which ordinary mobs,
        // which unique mobs, and which themed rooms produced the loot.
        printSourceBreakdown("== By specific mob (mob drops) ==", "mob", census, wpf, true);
        printSourceBreakdown("== By specific unique mob ==", "unique-mob", census, wpf, true);
        printSourceBreakdown("== By specific unique room ==", "unique-room", census, wpf, false);

        System.out.println("== Full census (type | ench | depth | drop | source | avg/world) ==");
        System.out.printf("%-26s %-18s %5s %-12s %-22s %9s%n",
                "type", "enchantment", "depth", "drop", "source", "avg/world");
        System.out.println("-------------------------------------------------------------------------------------------------------");
        // Re-sort for a readable census: by type, then depth, then drop, then enchantment.
        List<Row> rows = new ArrayList<>(census.values());
        rows.sort((a, b) -> {
            int c = a.type.compareTo(b.type);
            if (c != 0) return c;
            if (a.depth != b.depth) return Integer.compare(a.depth, b.depth);
            c = a.drop.compareTo(b.drop);
            if (c != 0) return c;
            return a.ench.compareTo(b.ench);
        });
        for (Row r : rows) {
            System.out.printf("%-26s %-18s %5d %-12s %-22s %9.3f%n",
                    r.type, r.ench, r.depth, r.drop, r.source, r.count / wpf);
        }
    }
}
