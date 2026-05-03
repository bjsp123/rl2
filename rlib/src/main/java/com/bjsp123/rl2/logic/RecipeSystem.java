package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafting/combining recipes. v1 ships only the same-kind gem size-up recipe; cross-species
 * gem recipes and other categories plug in by adding to {@link #ALL}.
 *
 * <p>Recipe matching is order-insensitive across the three crafting cells. Slots are
 * sparse — a 2-input recipe leaves the third slot null and only matches when exactly two
 * slots are filled.
 */
public final class RecipeSystem {

    /** A single recipe: input cardinality + a {@link Matcher} that decides if a given
     *  triple matches and produces the result. The dense list at {@link #ALL} is iterated
     *  in declaration order; the first match wins. */
    public interface Recipe {
        /** {@code null} if this triple doesn't match the recipe; otherwise the produced
         *  result item (already populated, ready to add to inventory). */
        Item tryMatch(Item a, Item b, Item c);

        /** True if {@code item} could appear in some valid completion of this recipe.
         *  Used to gate the inventory's "Combine" button. */
        boolean involves(Item item);

        /** Display copy for the Recipes tab. */
        String describe();
    }

    /** All registered recipes. First match wins. */
    public static final List<Recipe> ALL = new ArrayList<>();

    static {
        ALL.add(new GemSizeUpRecipe());
    }

    private RecipeSystem() {}

    /** Try to match the three crafting slots against any registered recipe. Returns the
     *  produced item or {@code null} if nothing matches. Slot order is irrelevant. */
    public static Item tryMatch(Item a, Item b, Item c) {
        for (Recipe r : ALL) {
            Item out = r.tryMatch(a, b, c);
            if (out != null) return out;
        }
        return null;
    }

    /** True if any registered recipe could involve {@code item}. */
    public static boolean isCraftable(Item item) {
        if (item == null) return false;
        for (Recipe r : ALL) if (r.involves(item)) return true;
        return false;
    }

    /** Same-kind gem size-up — two gems of identical species and size collapse into one
     *  gem of the next size up. Order-insensitive; the third slot must be empty. */
    static final class GemSizeUpRecipe implements Recipe {
        @Override
        public Item tryMatch(Item a, Item b, Item c) {
            // Exactly two slots must be filled.
            int filled = (a == null ? 0 : 1) + (b == null ? 0 : 1) + (c == null ? 0 : 1);
            if (filled != 2) return null;
            Item x = a, y = b;
            if (x == null) { x = b; y = c; }
            else if (y == null) { y = c; }
            if (x == null || y == null) return null;
            if (!x.isGem() || !y.isGem()) return null;
            if (x.gemSpecies != y.gemSpecies) return null;
            if (x.gemSize != y.gemSize) return null;
            return GemSystem.createGem(x.gemSpecies, x.gemSize + 1);
        }

        @Override
        public boolean involves(Item item) {
            return item != null && item.isGem();
        }

        @Override
        public String describe() {
            return "Two gems of the same kind and size combine into one gem of the next size up.";
        }
    }
}
