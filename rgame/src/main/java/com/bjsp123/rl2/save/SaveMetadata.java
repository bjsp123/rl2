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
}
