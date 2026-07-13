package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/gems.csv}: the data for a raw gem
 * {@link GemSpecies} - its theme affinity, atlas sprite cell, whether it
 * still spawns, and its player-facing name + description. The rarity
 * {@link GemSpecies.GemClass} stays on the enum (it's structural for recipes).
 */
public final class GemDefinition {

    /** The species this row describes (matches a {@link GemSpecies} constant). */
    public GemSpecies species;
    /** Spawn affinity, HARD-gated by {@code GemSystem}: the gem only appears
     *  on levels of this theme. null = no affinity, spawns everywhere. */
    public VisualTheme theme;
    /** Atlas cell on {@code sprites/gems2.png} (32px grid). */
    public int spriteCol;
    public int spriteRow;
    /** False for retired species (the pre-merge basic gems): never generated
     *  by loot, scatter, or recycle, but still render and load from old saves. */
    public boolean spawns = true;
    /** Player-facing display name (e.g. "hamethyst"). */
    public String name;
    /** Player-facing flavor description. */
    public String description;

    public static List<GemDefinition> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<GemDefinition> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            GemDefinition d = parseRow(row);
            if (d != null) out.add(d);
        }
        return out;
    }

    private static GemDefinition parseRow(Map<String, String> row) {
        String sp = CsvTable.str(row, "species", null);
        if (sp == null || sp.isEmpty()) return null;
        GemSpecies species;
        try {
            species = GemSpecies.valueOf(sp.trim());
        } catch (IllegalArgumentException bad) {
            System.err.println("[gems.csv] unknown species '" + sp + "' - skipped");
            return null;
        }
        GemDefinition d = new GemDefinition();
        d.species   = species;
        d.theme     = CsvTable.enumCell(row, "theme", VisualTheme.class, null);
        d.spriteCol = CsvTable.intCell(row, "spriteCol", 0);
        d.spriteRow = CsvTable.intCell(row, "spriteRow", 0);
        // Defensive: anything other than an explicit "false" spawns.
        d.spawns      = !"false".equalsIgnoreCase(CsvTable.str(row, "spawns", "true").trim());
        d.name        = CsvTable.str(row, "name", "");
        d.description = CsvTable.str(row, "description", "");
        return d;
    }
}
