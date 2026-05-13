package com.bjsp123.rl2.save;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.persistence.Persistence;

/**
 * Persistence I/O for {@link Achievements}. Stores a flat JSON array of
 * {@link Achievement} enum names; unknown names (e.g. an entry from a
 * future build) are silently dropped on load so a downgrade doesn't fail
 * hard.
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
            for (JsonValue v = root.child; v != null; v = v.next) {
                String name = v.asString();
                if (name == null) continue;
                try {
                    a.unlocked.add(Achievement.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // Unknown entry from a different build - drop it.
                }
            }
        } catch (Exception ignored) {
            // Corrupt blob: discard and start fresh.
        }
        return a;
    }

    public static void save(Persistence persistence, Achievements a) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Achievement ach : a.unlocked) {
            if (!first) sb.append(',');
            sb.append('"').append(ach.name()).append('"');
            first = false;
        }
        sb.append(']');
        persistence.save(KEY, sb.toString());
    }
}
