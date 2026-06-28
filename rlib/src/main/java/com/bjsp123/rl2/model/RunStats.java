package com.bjsp123.rl2.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-run statistics for the player - surfaced on the victory / game-over
 * screen and fed into the run score. Lives on the player {@link Mob}; other
 * mobs carry an empty instance (cheap, never read). Serialized with the save
 * via libGDX reflective JSON, so all fields have safe defaults and the maps
 * use plain {@link HashMap} (reconstructable on load).
 */
public final class RunStats {

    public int mobsKilled;
    public int itemsPickedUp;
    public int foodEaten;
    public int gemsFound;

    /** Use-count per item type, pre-bucketed by category at the call site. */
    public Map<String, Integer> wandUses = new HashMap<>();
    public Map<String, Integer> bombUses = new HashMap<>();
    public Map<String, Integer> toolUses = new HashMap<>();

    public void recordWandUse(String type) { bump(wandUses, type); }
    public void recordBombUse(String type) { bump(bombUses, type); }
    public void recordToolUse(String type) { bump(toolUses, type); }

    private static void bump(Map<String, Integer> m, String type) {
        if (type == null || type.isEmpty()) return;
        m.merge(type, 1, Integer::sum);
    }

    /** Item type used most in each category, or {@code null} if none used. */
    public String topWand() { return topKey(wandUses); }
    public String topBomb() { return topKey(bombUses); }
    public String topTool() { return topKey(toolUses); }

    public int topWandCount() { return countOf(wandUses, topWand()); }
    public int topBombCount() { return countOf(bombUses, topBomb()); }
    public int topToolCount() { return countOf(toolUses, topTool()); }

    private static String topKey(Map<String, Integer> m) {
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() != null && e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static int countOf(Map<String, Integer> m, String key) {
        if (key == null) return 0;
        Integer v = m.get(key);
        return v == null ? 0 : v;
    }
}
