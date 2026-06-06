package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.GemSpecies.GemClass;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/recipes.csv} parsed into a typed POJO (RL-50).
 * A recipe combines 2-4 ingredient gems - described by {@link Slot} constraints -
 * at a gem hearth to produce one {@link #output} item (a {@code GEM}-category
 * item type catalogued in {@code items.csv}).
 *
 * <p>Slots are matched against a set of raw ingredient gems order-insensitively by
 * {@link RecipeSystem}. Each slot classifies a candidate gem by its rarity
 * class / species (never by name string) - see {@link Slot#accepts}.
 *
 * <p>{@link Registries} loads + indexes these; {@link RecipeSystem} does the
 * combine matching. The forge UI ({@code V2Forge}) renders them.
 */
public final class GemRecipe {

    /** Kind of constraint a recipe slot imposes on a candidate ingredient gem.
     *  Ordered most-specific-first so the greedy matcher in
     *  {@link RecipeSystem} resolves tightly-constrained slots before loose
     *  ones. */
    public enum SlotKind {
        /** Exactly one named {@link GemSpecies} (the recipe's keystone). */
        NAMED,
        /** Any {@link GemClass#EXOTIC} gem. */
        EXOTIC,
        /** Any {@link GemClass#METAL} or {@link GemClass#EXOTIC} gem. */
        METAL_OR_EXOTIC,
        /** Any gem at all. */
        ANY
    }

    /** A single ingredient constraint. {@link #species} is non-null iff
     *  {@link #kind} is {@link SlotKind#NAMED}. */
    public static final class Slot {
        public final SlotKind kind;
        public final GemSpecies species;

        private Slot(SlotKind kind, GemSpecies species) {
            this.kind = kind;
            this.species = species;
        }

        static Slot of(SlotKind kind)            { return new Slot(kind, null); }
        static Slot named(GemSpecies species)    { return new Slot(SlotKind.NAMED, species); }

        /** True if {@code gem} (a raw ingredient gem) satisfies this slot.
         *  Classifies by stats/species, never by name string. */
        public boolean accepts(Item gem) {
            if (gem == null || !gem.isGem()) return false;
            GemClass cls = gem.gemSpecies.gemClass;
            return switch (kind) {
                case NAMED           -> gem.gemSpecies == species;
                case EXOTIC          -> cls == GemClass.EXOTIC;
                case METAL_OR_EXOTIC -> cls == GemClass.METAL || cls == GemClass.EXOTIC;
                case ANY             -> true;
            };
        }
    }

    public String output;
    public int rarity;
    public List<Slot> slots = new ArrayList<>();

    public static List<GemRecipe> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<GemRecipe> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            out.add(parseRow(row));
        }
        return out;
    }

    private static GemRecipe parseRow(Map<String, String> row) {
        GemRecipe r = new GemRecipe();
        r.output = CsvTable.str(row, "output", null);
        r.rarity = CsvTable.intCell(row, "rarity", 0);
        for (int i = 1; i <= 4; i++) {
            Slot s = parseSlot(CsvTable.str(row, "slot" + i, null));
            if (s != null) r.slots.add(s);
        }
        return r;
    }

    /** Map a slot cell ("any" / "exotic" / "metal_exotic" / a species name) to a
     *  {@link Slot}. Empty / null cells return {@code null} (slot absent). */
    private static Slot parseSlot(String cell) {
        if (cell == null) return null;
        String s = cell.trim();
        if (s.isEmpty()) return null;
        return switch (s.toLowerCase()) {
            case "any"          -> Slot.of(SlotKind.ANY);
            case "exotic"       -> Slot.of(SlotKind.EXOTIC);
            case "metal_exotic" -> Slot.of(SlotKind.METAL_OR_EXOTIC);
            default             -> Slot.named(GemSpecies.valueOf(s.toUpperCase()));
        };
    }
}
