package com.bjsp123.rl2.save;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory ledger of completed arena matchups. Most-recent-first; older
 * entries past {@link #MAX} fall off the end. Persistence is the separate
 * concern of {@link ArenaHallOfFameStore}.
 *
 * <p>Distinct from {@link HallOfFame} because the entry shape is different
 * (two teams instead of one player run) and the ordering rule is different
 * (recency, not score).
 */
public class ArenaHallOfFame {

    /** Hard cap on how many recent matchups to remember. */
    public static final int MAX = 50;

    /** Most-recent first. Mutable; mutate via {@link #add} or directly when
     *  loading from persisted JSON via {@link ArenaHallOfFameStore}. */
    public List<ArenaHallOfFameEntry> entries = new ArrayList<>();

    /** Prepend {@code e} as the newest matchup, dropping any tail past
     *  {@link #MAX}. Doesn't persist — caller pairs this with
     *  {@link ArenaHallOfFameStore#save}. */
    public void add(ArenaHallOfFameEntry e) {
        entries.add(0, e);
        if (entries.size() > MAX) entries = new ArrayList<>(entries.subList(0, MAX));
    }
}
