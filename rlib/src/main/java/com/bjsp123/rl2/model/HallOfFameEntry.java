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
    /** True if the run ended in victory (Great Wraith defeated) rather than death.
     *  Drives the VICTORY vs YOU DIED screen. Defaults false for old records. */
    public boolean victory     = false;
    /** Beacons lit over the run - the victory score driver. */
    public int    beaconsLit   = 0;
    /** True if every beacon in the world was lit (perfect victory). */
    public boolean allBeaconsLit = false;

    // --- Run stats (RL-58) - surfaced on the victory screen + score breakdown.
    //     All default 0 / "" so older save records load cleanly.
    public int    mobsKilled    = 0;
    public int    itemsPickedUp = 0;
    public int    foodEaten     = 0;
    public int    gemsFound     = 0;
    /** True if the Great Wraith was slain this run (score bonus). */
    public boolean killedGreatWraith = false;
    /** Difficulty name (Difficulty.name()) the run was played at - labels the
     *  score multiplier on the breakdown. Defaults NORMAL for old records. */
    public String difficulty   = "NORMAL";
    /** Most-used item type per category (item type string, "" if none used). */
    public String topWand      = "";
    public String topBomb      = "";
    public String topTool      = "";
    /** Equipped item type strings at run-end (for victory-screen icons). */
    public List<String> equipmentTypes = new ArrayList<>();
    /** Use-counts for the most-used item in each category. */
    public int    topWandCount = 0;
    public int    topBombCount = 0;
    public int    topToolCount = 0;

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
