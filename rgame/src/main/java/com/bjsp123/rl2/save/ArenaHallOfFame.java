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
     *  {@link #MAX}. Doesn't persist - caller pairs this with
     *  {@link ArenaHallOfFameStore#save}. */
    public void add(ArenaHallOfFameEntry e) {
        entries.add(0, e);
        if (entries.size() > MAX) entries = new ArrayList<>(entries.subList(0, MAX));
    }

    /**
     * One row in the ledger - a single matchup: two team descriptions plus the
     * outcome (per-team survivor counts) and a wall-clock timestamp. Public
     * fields + no-arg constructor so libGDX {@link com.badlogic.gdx.utils.Json}
     * round-trips it. Nested in {@link ArenaHallOfFame}; external readers import
     * {@code ArenaHallOfFame.ArenaHallOfFameEntry}.
     */
    public static class ArenaHallOfFameEntry {
        /** Human-readable team description, e.g. {@code "warriorx3 L5"}. */
        public String teamADescription = "";
        public String teamBDescription = "";

        /** Per-team survivor description, e.g. {@code "warriorx2"} or {@code "-"} when wiped. */
        public String teamASurvivors = "";
        public String teamBSurvivors = "";

        /** {@code 1} = Team A wins, {@code 2} = Team B, {@code 0} = mutual wipe / stalemate. */
        public int winner;

        public long timestampMillis;

        public ArenaHallOfFameEntry() {}

        public ArenaHallOfFameEntry(String teamA, String teamB,
                                    String teamASurvivors, String teamBSurvivors,
                                    int winner, long ts) {
            this.teamADescription = teamA;
            this.teamBDescription = teamB;
            this.teamASurvivors   = teamASurvivors;
            this.teamBSurvivors   = teamBSurvivors;
            this.winner           = winner;
            this.timestampMillis  = ts;
        }
    }
}
