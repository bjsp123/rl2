package com.bjsp123.rl2.save;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.persistence.Persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent ledger of completed arena matchups. Mirrors
 * {@link HallOfFame}'s shape — one persistence key, libGDX {@code Json}
 * serialisation, capped at {@link #MAX} entries (most-recent-first).
 *
 * <p>Distinct from the main hall of fame because the entry shape is different
 * (two teams instead of one player run) and the rankings ordering is different
 * (here we sort by recency; the main table sorts by score).
 */
public class ArenaHallOfFame {

    private static final String KEY = "rl2-arena-hall-of-fame";
    private static final int    MAX = 50;

    private final Persistence persistence;
    private final Json json = new Json();
    private List<ArenaHallOfFameEntry> entries;

    public ArenaHallOfFame(Persistence persistence) {
        this.persistence = persistence;
        load();
    }

    /** Most recent first. */
    public List<ArenaHallOfFameEntry> entries() { return entries; }

    /** Append a new arena outcome. Drops the oldest entry past {@link #MAX}. */
    public void add(ArenaHallOfFameEntry e) {
        entries.add(0, e);
        if (entries.size() > MAX) entries = new ArrayList<>(entries.subList(0, MAX));
        save();
    }

    private void load() {
        String raw = persistence.load(KEY);
        if (raw == null || raw.isEmpty()) { entries = new ArrayList<>(); return; }
        try {
            JsonValue root = new JsonReader().parse(raw);
            entries = new ArrayList<>();
            for (JsonValue v = root.child; v != null; v = v.next) {
                entries.add(json.readValue(ArenaHallOfFameEntry.class, v));
            }
        } catch (Exception ex) {
            entries = new ArrayList<>();
        }
    }

    private void save() {
        persistence.save(KEY, json.toJson(entries, ArrayList.class, ArenaHallOfFameEntry.class));
    }
}
