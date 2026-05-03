package com.bjsp123.rl2.model;

public class World {
    public Level[] levels;
    public int currentLevelIndex;
    /** Monotonic game-turn counter. Incremented once per render-frame in which any game
     *  state advanced, giving every game event a consistent time stamp across level
     *  changes. Used by {@code HistoricalRecord} to time-stamp history entries. */
    public int turn;
    /** Monotonic <b>game-tick</b> counter — the finer-grained currency drained by
     *  {@link com.bjsp123.rl2.logic.TurnSystem#tick(Level)}. Persisted across level
     *  changes. */
    public int tick;

    public World() {}

    public World(Level[] levels) {
        this.levels = levels;
        this.currentLevelIndex = 0;
    }

    public Level currentLevel() {
        return levels[currentLevelIndex];
    }
}
