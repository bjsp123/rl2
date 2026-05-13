package com.bjsp123.rl2.save;

import com.bjsp123.rl2.model.HallOfFameEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-memory leaderboard of completed runs. Pure data + ordering policy: the
 * {@link #add} method handles sort-by-score and cap-at-{@link #MAX} so the
 * list is always presentation-ready. Persistence is the separate concern
 * of {@link HallOfFameStore}.
 */
public class HallOfFame {

    /** Maximum number of remembered runs. Older / lower-scoring entries fall
     *  off the end as new ones are added. */
    public static final int MAX = 20;

    /** Highest-scoring first. Mutable; the typical mutation is via
     *  {@link #add}, but {@link HallOfFameStore} also writes here directly
     *  when reading the persisted JSON. */
    public List<HallOfFameEntry> entries = new ArrayList<>();

    /** Append {@code e}, re-sort by score descending, and truncate to
     *  {@link #MAX}. Doesn't persist - caller pairs this with
     *  {@link HallOfFameStore#save}. */
    public void add(HallOfFameEntry e) {
        entries.add(e);
        entries.sort(Comparator.comparingInt((HallOfFameEntry x) -> x.score).reversed());
        if (entries.size() > MAX) entries = new ArrayList<>(entries.subList(0, MAX));
    }
}
