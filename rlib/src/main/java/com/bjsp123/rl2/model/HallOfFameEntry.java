package com.bjsp123.rl2.model;

import java.util.ArrayList;
import java.util.List;

public class HallOfFameEntry {
    public String charClass = "";
    public int    score;
    public int    depth;
    public List<String> equipment = new ArrayList<>();
    public long   timestampMillis;

    public HallOfFameEntry() {}

    public HallOfFameEntry(String charClass, int score, int depth, List<String> equipment, long ts) {
        this.charClass       = charClass;
        this.score           = score;
        this.depth           = depth;
        this.equipment       = equipment;
        this.timestampMillis = ts;
    }
}
