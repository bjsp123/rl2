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
    /** Last player-relevant log line at the moment of death - the "cause" sentence.
     *  Kept for backwards-compat with older save records; new code populates
     *  {@link #deathLog} instead. */
    public String deathMessage = "";
    /** The final five rolling-log lines captured at the moment of death, ordered
     *  oldest -> newest with the very last entry describing the cause. Shown on
     *  the V2GameOver screen so the player can see the lead-up to their death.
     *  Populated by PlayScreen when the death snapshot is captured. */
    public List<String> deathLog = new ArrayList<>();
    /** Single-line "what killed you" headline composed from the last fatal
     *  {@link com.bjsp123.rl2.logic.MobSystem.DamageCause} - e.g. "Rogue
     *  burned to death in a fire caused by Kobold's fire wand." Rendered as
     *  the large top frame on V2GameOver; empty for older save records. */
    public String deathHeadline = "";
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
