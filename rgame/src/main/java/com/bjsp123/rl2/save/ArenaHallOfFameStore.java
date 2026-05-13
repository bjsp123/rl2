package com.bjsp123.rl2.save;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.persistence.Persistence;

import java.util.ArrayList;

/**
 * Persistence I/O for {@link ArenaHallOfFame}. Mirrors {@link HallOfFameStore}
 * - static load/save against {@link Persistence}, libGDX {@code Json}
 * serialisation, decoupled from the data POJO.
 */
public final class ArenaHallOfFameStore {

    private static final String KEY = "rl2-arena-hall-of-fame";
    private static final Json   JSON = new Json();

    private ArenaHallOfFameStore() {}

    public static ArenaHallOfFame load(Persistence persistence) {
        ArenaHallOfFame hof = new ArenaHallOfFame();
        String raw = persistence.load(KEY);
        if (raw == null || raw.isEmpty()) return hof;
        try {
            JsonValue root = new JsonReader().parse(raw);
            for (JsonValue v = root.child; v != null; v = v.next) {
                hof.entries.add(JSON.readValue(ArenaHallOfFameEntry.class, v));
            }
        } catch (Exception ignored) {
            // Corrupt blob: discard and start fresh.
        }
        return hof;
    }

    public static void save(Persistence persistence, ArenaHallOfFame hof) {
        persistence.save(KEY,
                JSON.toJson(hof.entries, ArrayList.class, ArenaHallOfFameEntry.class));
    }
}
