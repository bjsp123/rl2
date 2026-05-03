package com.bjsp123.rl2.save;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.persistence.Persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HallOfFame {

    private static final String KEY = "rl2-hall-of-fame";
    private static final int    MAX = 20;

    private final Persistence persistence;
    private final Json json = new Json();
    private List<HallOfFameEntry> entries;

    public HallOfFame(Persistence persistence) {
        this.persistence = persistence;
        load();
    }

    public List<HallOfFameEntry> entries() { return entries; }

    public void add(HallOfFameEntry e) {
        entries.add(e);
        entries.sort(Comparator.comparingInt((HallOfFameEntry x) -> x.score).reversed());
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
                entries.add(json.readValue(HallOfFameEntry.class, v));
            }
        } catch (Exception ex) {
            entries = new ArrayList<>();
        }
    }

    private void save() {
        persistence.save(KEY, json.toJson(entries, ArrayList.class, HallOfFameEntry.class));
    }
}
