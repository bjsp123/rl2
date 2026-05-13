package com.bjsp123.rl2.save;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.persistence.Persistence;

import java.util.ArrayList;

/**
 * Persistence I/O for {@link HallOfFame}. Static load + save against the
 * {@link Persistence} key {@link #KEY}; serialisation uses libGDX
 * {@code Json} (reflective - {@link HallOfFameEntry} has the no-arg ctor
 * required for round-trip).
 *
 * <p>Decoupled from {@link HallOfFame} so the data class stays free of
 * persistence dependencies - callers that just want to read or mutate the
 * leaderboard don't have to know about the JSON format or the persistence
 * key.
 */
public final class HallOfFameStore {

    private static final String KEY = "rl2-hall-of-fame";
    private static final Json   JSON = new Json();

    private HallOfFameStore() {}

    /** Load the persisted leaderboard, or return an empty {@link HallOfFame}
     *  when nothing has been saved yet (or the saved blob is unreadable). */
    public static HallOfFame load(Persistence persistence) {
        HallOfFame hof = new HallOfFame();
        String raw = persistence.load(KEY);
        if (raw == null || raw.isEmpty()) return hof;
        try {
            JsonValue root = new JsonReader().parse(raw);
            for (JsonValue v = root.child; v != null; v = v.next) {
                hof.entries.add(JSON.readValue(HallOfFameEntry.class, v));
            }
        } catch (Exception ignored) {
            // Corrupt blob: discard and start fresh rather than fail hard.
        }
        return hof;
    }

    /** Persist the current state of {@code hof}. Called after every {@link
     *  HallOfFame#add} the caller wants to durable. */
    public static void save(Persistence persistence, HallOfFame hof) {
        persistence.save(KEY,
                JSON.toJson(hof.entries, ArrayList.class, HallOfFameEntry.class));
    }
}
