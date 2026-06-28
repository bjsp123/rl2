package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/gems.csv}: the data for a raw gem
 * {@link GemSpecies} that used to live hardcoded in the enum / {@code GemSprites}
 * - its theme affinity and atlas sprite cell. Names + descriptions stay in
 * strings.csv ({@code gem.<SPECIES>.name} / {@code .description}); the rarity
 * {@link GemSpecies.GemClass} stays on the enum (it's structural for recipes).
 */
public final class GemDefinition {

    /** The species this row describes (matches a {@link GemSpecies} constant). */
    public GemSpecies species;
    /** Spawn affinity. Soft-weighted / hard-gated by {@code GemSystem}; null =
     *  no affinity. SHINY is the generic theme. */
    public VisualTheme theme;
    /** Atlas cell on {@code sprites/gems2.png} (32px grid). */
    public int spriteCol;
    public int spriteRow;

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
        return d;
    }
}
