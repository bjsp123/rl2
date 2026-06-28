package com.bjsp123.rl2.save;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistence I/O for {@link Achievements}. Stores a small JSON object with
 * {@code unlocked} (array of {@link Achievement} enum names) and
 * {@code killedMobTypes} (array of mob-type strings); unknown / corrupt
 * entries are silently dropped on load so a downgrade or hand-edit doesn't
 * fail hard.
 *
 * <p>Legacy format - a flat JSON array of achievement names - is still
 * accepted on load so existing players keep their unlocks. The next save
 * writes the new object format.
 */
public final class AchievementsStore {

    private static final String KEY = "rl2-achievements";

    private AchievementsStore() {}

    public static Achievements load(Persistence persistence) {
        Achievements a = new Achievements();
        String raw = persistence.load(KEY);
        if (raw == null || raw.isEmpty()) return a;
        try {
            JsonValue root = new JsonReader().parse(raw);
            if (root == null) return a;
            // Legacy flat-array format: every direct child is an enum name.
            if (root.isArray()) {
                for (JsonValue v = root.child; v != null; v = v.next) {
                    String name = v.asString();
                    if (name == null) continue;
                    try {
                        a.unlocked.add(Achievement.valueOf(name));
                    } catch (IllegalArgumentException ignored) { }
                }
                return a;
            }
            // Current object format.
            JsonValue unlocked = root.get("unlocked");
            if (unlocked != null) {
                for (JsonValue v = unlocked.child; v != null; v = v.next) {
                    String name = v.asString();
                    if (name == null) continue;
                    try {
                        a.unlocked.add(Achievement.valueOf(name));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            JsonValue killed = root.get("killedMobTypes");
            if (killed != null) {
                for (JsonValue v = killed.child; v != null; v = v.next) {
                    String t = v.asString();
                    if (t != null && !t.isEmpty()) a.killedMobTypes.add(t);
                }
            }
        } catch (Exception ignored) {
            // Corrupt blob: discard and start fresh.
        }
        return a;
    }

    public static void save(Persistence persistence, Achievements a) {
        StringBuilder sb = new StringBuilder("{\"unlocked\":[");
        boolean first = true;
        for (Achievement ach : a.unlocked) {
            if (!first) sb.append(',');
            sb.append('"').append(ach.name()).append('"');
            first = false;
        }
        sb.append("],\"killedMobTypes\":[");
        first = true;
        for (String t : a.killedMobTypes) {
            if (!first) sb.append(',');
            sb.append('"').append(t).append('"');
            first = false;
        }
        sb.append("]}");
        persistence.save(KEY, sb.toString());
    }
}
