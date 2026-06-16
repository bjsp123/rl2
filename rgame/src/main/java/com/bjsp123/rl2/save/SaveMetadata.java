package com.bjsp123.rl2.save;
/** Lightweight summary of a saved game, persisted alongside each slot for quick listing. */
public class SaveMetadata {
    public String charClass = "";
    public int    characterLevel;
    public int    depth;
    public int    score;
    public int    hp;
    public int    maxHp;
    public long   timestampMillis;
    /** App version that wrote this save (e.g. "0.1"). Empty on legacy/unstamped
     *  saves, which the loader then treats as incompatible. */
    public String version = "";
    /** App build that wrote this save. {@code -1} on legacy/unstamped saves. */
    public int    build   = -1;
}
