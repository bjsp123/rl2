package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/recipes.csv} parsed into a typed POJO (RL-50).
 * A recipe combines 2-4 ingredient gems - each a specific named
 * {@link GemSpecies} - at a gem hearth to produce one {@link #output} item
 * (a {@code GEM}-category item type catalogued in {@code items.csv}).
 *
 * <p>There are no wildcard ("any gem" / "any exotic") slots: every ingredient
 * names its species, so {@link RecipeSystem} matches by simple per-species
 * counting, order-insensitively.
 *
 * <p>{@link Registries} loads + indexes these; {@link RecipeSystem} does the
 * combine matching. The forge UI ({@code V2Forge}) renders them.
 */
public final class GemRecipe {

    public String output;
    public int rarity;
    /** Ingredient species in CSV slot order (the order the forge draws the
     *  equation row). Duplicates mean "N of that gem". */
    public List<GemSpecies> ingredients = new ArrayList<>();

    public static List<GemRecipe> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<GemRecipe> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            GemRecipe r = parseRow(row);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Parse one recipe row, or {@code null} (with a stderr warning) if any
     *  slot cell doesn't name a known {@link GemSpecies}. A recipe missing an
     *  ingredient is wrong, so the whole row is skipped - never thrown - per
     *  the defensive hand-edited-CSV rules. Legacy wildcard tokens ("any",
     *  "exotic", "metal_exotic") are unknown species and skip the row too. */
    private static GemRecipe parseRow(Map<String, String> row) {
        GemRecipe r = new GemRecipe();
        r.output = CsvTable.str(row, "output", null);
        r.rarity = CsvTable.intCell(row, "rarity", 0);
        for (int i = 1; i <= 4; i++) {
            String cell = CsvTable.str(row, "slot" + i, null);
            if (cell == null || cell.trim().isEmpty()) continue;
            try {
                r.ingredients.add(GemSpecies.valueOf(cell.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                System.err.println("[csv] recipes.csv: unknown gem species '" + cell
                        + "' in recipe " + r.output + " - recipe skipped");
                return null;
            }
        }
        return r;
    }
}
