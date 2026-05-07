package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Render a {@link World} as YAML for inspection or off-tool consumption.
 *
 * <p>Top-level structure groups levels by {@link Level#depth}:
 * <pre>
 * depths:
 *   - depth: 1
 *     levels:
 *       - index: 0           # World.levels index
 *         layout: PACKED
 *         theme: CRYSTAL
 *         side: CENTER
 *         size: 48x48
 *         flags: [WATER]
 *         rooms:
 *           - {x: 5, y: 5, w: 7, h: 7}
 *           ...
 *         mobs:
 *           - {index: 0, type: PLAYER_WARRIOR, name: Warrior, position: [10, 10],
 *              hp: 25, characterLevel: 1}
 *           ...
 *         items:
 *           - {type: SWORD, name: sword, position: [12, 14], level: 1}
 *           - {type: HEALING_POTION, name: healing potion, held_by_mob_index: 0}
 *           ...
 * </pre>
 *
 * <p>Items in a mob's inventory + equipment are emitted in the level's
 * {@code items} block with a {@code held_by_mob_index} pointer back to the
 * carrying mob (matching the user's request that held items appear in items,
 * not nested inside mobs).
 */
public final class WorldYamlDump {

    private WorldYamlDump() {}

    public static String dump(World world) {
        StringBuilder sb = new StringBuilder();
        if (world == null || world.levels == null) {
            sb.append("depths: []\n");
            return sb.toString();
        }

        // Top matter: seed (six-letter code + raw long) so a dump can be
        // reproduced by passing the code back through {@code WorldTopology.build}.
        sb.append("seed: ").append(com.bjsp123.rl2.util.SeedCode.encode(world.seed))
          .append("    # long=").append(world.seed).append('\n');

        // Group levels by depth (low → high), preserving World.levels index per
        // entry so the YAML records original positions in the topology.
        Map<Integer, List<Integer>> byDepth = new TreeMap<>();
        for (int i = 0; i < world.levels.length; i++) {
            Level lvl = world.levels[i];
            if (lvl == null) continue;
            byDepth.computeIfAbsent(lvl.depth, k -> new ArrayList<>()).add(i);
        }

        sb.append("depths:\n");
        for (Map.Entry<Integer, List<Integer>> e : byDepth.entrySet()) {
            sb.append("  - depth: ").append(e.getKey()).append('\n');
            sb.append("    levels:\n");
            for (int idx : e.getValue()) {
                appendLevel(sb, idx, world.levels[idx]);
            }
        }
        return sb.toString();
    }

    private static void appendLevel(StringBuilder sb, int idx, Level lvl) {
        sb.append("      - index: ").append(idx).append('\n');
        sb.append("        layout: ").append(lvl.layout).append('\n');
        sb.append("        theme: ").append(lvl.theme).append('\n');
        sb.append("        side: ").append(lvl.side).append('\n');
        sb.append("        size: ").append(lvl.width).append('x').append(lvl.height).append('\n');
        sb.append("        flags: ").append(lvl.flags).append('\n');

        // Rooms: emit the snapshot taken in LevelFactory.createDungeonLevel.
        // {@code kind} is non-null when themed-room stamping claimed the room
        // (e.g. POTION_ROOM, KOBOLD_FORTRESS); plain layout rooms omit it.
        sb.append("        rooms:\n");
        if (lvl.rooms != null && !lvl.rooms.isEmpty()) {
            for (Level.RoomSnapshot r : lvl.rooms) {
                sb.append("          - {x: ").append(r.x)
                  .append(", y: ").append(r.y)
                  .append(", w: ").append(r.w)
                  .append(", h: ").append(r.h);
                if (r.kind != null) {
                    sb.append(", kind: ").append(yamlString(r.kind));
                }
                sb.append("}\n");
            }
        }

        // Mobs first so we can emit held-item back-references by index. Mob
        // index is the position in lvl.mobs, NOT World-wide; that's enough for
        // the held_by_mob_index pointer to disambiguate within a single level.
        sb.append("        mobs:\n");
        if (lvl.mobs != null) {
            for (int mi = 0; mi < lvl.mobs.size(); mi++) {
                appendMob(sb, mi, lvl.mobs.get(mi));
            }
        }

        sb.append("        items:\n");
        // Floor items first.
        if (lvl.items != null) {
            for (Item it : lvl.items) {
                appendItem(sb, it, /*heldByIdx=*/-1);
            }
        }
        // Then mob-held items (bag + equipped slots), tagged with the mob's
        // level-local index.
        if (lvl.mobs != null) {
            for (int mi = 0; mi < lvl.mobs.size(); mi++) {
                Mob m = lvl.mobs.get(mi);
                if (m == null || m.inventory == null) continue;
                if (m.inventory.bag != null) {
                    for (Item it : m.inventory.bag) appendItem(sb, it, mi);
                }
                for (Item eq : m.inventory.allEquipped()) appendItem(sb, eq, mi);
            }
        }
    }

    private static void appendMob(StringBuilder sb, int mi, Mob m) {
        if (m == null) return;
        sb.append("          - {index: ").append(mi)
          .append(", type: ").append(yamlString(m.mobType))
          .append(", name: ").append(yamlString(m.name));
        if (m.position != null) {
            sb.append(", position: [").append(m.position.tileX())
              .append(", ").append(m.position.tileY()).append(']');
        }
        sb.append(", hp: ").append(m.hp);
        sb.append(", characterLevel: ").append(m.characterLevel);
        if (m.behavior != null) sb.append(", behavior: ").append(m.behavior);
        if (m.faction != null && !m.faction.isEmpty()) {
            sb.append(", faction: ").append(yamlString(m.faction));
        }
        if (m.owner != null) {
            sb.append(", owner_type: ").append(yamlString(m.owner.mobType));
        }
        sb.append("}\n");
    }

    private static void appendItem(StringBuilder sb, Item it, int heldByMobIdx) {
        if (it == null) return;
        sb.append("          - {type: ").append(yamlString(it.type));
        if (it.name != null) sb.append(", name: ").append(yamlString(it.name));
        if (heldByMobIdx >= 0) {
            sb.append(", held_by_mob_index: ").append(heldByMobIdx);
        } else if (it.location != null) {
            sb.append(", position: [").append(it.location.tileX())
              .append(", ").append(it.location.tileY()).append(']');
        }
        if (it.level > 0) sb.append(", level: ").append(it.level);
        if (it.inventoryCategory != null) sb.append(", category: ").append(it.inventoryCategory);
        sb.append("}\n");
    }

    /** Quote a string only when it contains YAML-significant chars; bare names
     *  like {@code SWORD} or {@code MOUSE} stay readable. */
    private static String yamlString(String s) {
        if (s == null) return "null";
        if (s.isEmpty()) return "\"\"";
        // Quote when the string contains a colon, comma, space, hash, bracket,
        // or starts with something the YAML parser would interpret as a number.
        boolean needsQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' || c == ',' || c == '#' || c == '['
             || c == ']' || c == '{' || c == '}' || c == '\'' || c == '"'
             || c == '\n') { needsQuote = true; break; }
        }
        if (!needsQuote && s.charAt(0) == ' ') needsQuote = true;
        if (!needsQuote) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
