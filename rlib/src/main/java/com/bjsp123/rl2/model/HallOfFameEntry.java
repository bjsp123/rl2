package com.bjsp123.rl2.model;

import java.util.ArrayList;
import java.util.List;

public class HallOfFameEntry {
    public String charClass = "";
    public int    level;
    public int    score;
    public int    depth;
    public List<String> equipment = new ArrayList<>();
    public long   timestampMillis;
    /** Last player-relevant log line at the moment of death - the "cause" sentence. */
    public String deathMessage = "";
    public int    totalTurns   = 0;
    public int    beastsTamed  = 0;
    /** Perk.name() of the perk with the highest level at run-end, or "" if none. */
    public String favPerk      = "";

    public HallOfFameEntry() {}

    public HallOfFameEntry(String charClass, int level, int score, int depth, List<String> equipment, long ts) {
        this.charClass       = charClass;
        this.level           = level;
        this.score           = score;
        this.depth           = depth;
        this.equipment       = equipment;
        this.timestampMillis = ts;
    }
}
