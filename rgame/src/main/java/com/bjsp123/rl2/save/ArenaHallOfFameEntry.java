package com.bjsp123.rl2.save;

/**
 * One row in the arena hall-of-fame ledger. Each entry is a single matchup —
 * two team descriptions plus the outcome (per-team survivor counts) and a
 * wall-clock timestamp.
 *
 * <p>Public fields + no-arg constructor so libGDX's {@link com.badlogic.gdx.utils.Json}
 * round-trips it without custom serialisers. Mirrors {@link com.bjsp123.rl2.model.HallOfFameEntry}.
 */
public class ArenaHallOfFameEntry {
    /** Human-readable team description, e.g. {@code "warrior×3 L5"}. */
    public String teamADescription = "";
    public String teamBDescription = "";

    /** Per-team survivor description, e.g. {@code "warrior×2"} or {@code "—"} when wiped. */
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
