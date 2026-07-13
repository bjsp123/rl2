package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Gem-recipe matching (RL-50). Every recipe ingredient names a specific
 * {@link GemSpecies}, so matching is plain multiset counting: a bag affords a
 * recipe when it holds at least the required number of gems of each species.
 * Order-insensitive by construction.
 *
 * <p>This layer only resolves gems -> recipe. Turning the matched recipe's
 * {@link GemRecipe#output} into an actual {@link Item} is
 * {@code ItemFactory.build(output)}, called by the forge once it confirms a
 * craft.
 */
public final class RecipeSystem {

    private RecipeSystem() {}

    /** Recipes whose ingredient multiset contains the gems placed so far -
     *  drives the forge's live filtering as the player drops gems into the
     *  hearth. With no gems placed, every recipe is a candidate. */
    public static List<GemRecipe> candidates(List<Item> placed) {
        List<GemRecipe> out = new ArrayList<>();
        Map<GemSpecies, Integer> placedCounts = countGems(placed);
        for (GemRecipe r : Registries.recipes()) {
            if (contains(countRecipe(r), placedCounts)) out.add(r);
        }
        return out;
    }

    /** True if the player's bag holds enough gems of each required species.
     *  Drives the forge grey-out (recipe shown but not buildable). */
    public static boolean canAfford(GemRecipe r, Inventory inv) {
        if (r == null || inv == null) return false;
        return contains(countGems(gemsInBag(inv)), countRecipe(r));
    }

    /** Consume the gems a recipe needs from the bag, if the bag can pay.
     *  Returns {@code true} and removes exactly the required gems on success;
     *  returns {@code false} and removes nothing otherwise. The caller then
     *  builds the output and adds it to the inventory. */
    public static boolean consume(GemRecipe r, Inventory inv) {
        if (r == null || inv == null || !canAfford(r, inv)) return false;
        for (GemSpecies needed : r.ingredients) {
            Item pick = null;
            for (Item g : gemsInBag(inv)) {
                if (g.gemSpecies == needed) { pick = g; break; }
            }
            if (pick == null) return false;   // unreachable after canAfford
            InventorySystem.removeOneFromBag(inv, pick);
        }
        return true;
    }

    /** Raw ingredient gems currently in the bag (excludes crafted GEM-category
     *  items, which are not gems). */
    public static List<Item> gemsInBag(Inventory inv) {
        List<Item> out = new ArrayList<>();
        if (inv == null) return out;
        for (Item it : inv.bag) {
            if (it != null && it.isGem()) {
                for (int i = 0; i < Math.max(1, it.count); i++) out.add(it);
            }
        }
        return out;
    }

    /** True if any registered recipe lists this gem's species - gates a future
     *  "combine" affordance on a bag gem. */
    public static boolean isCraftIngredient(Item gem) {
        if (gem == null || !gem.isGem()) return false;
        for (GemRecipe r : Registries.recipes()) {
            if (r.ingredients.contains(gem.gemSpecies)) return true;
        }
        return false;
    }

    /** True if {@code item} is a crafted gem-item (a GEM-category item that is
     *  not a raw gem) - i.e. a recipe output rather than an ingredient. */
    public static boolean isCraftedGem(Item item) {
        return item != null
                && item.inventoryCategory == InventoryCategory.GEM
                && !item.isGem();
    }

    /** Per-species counts of the raw gems in {@code gems} (stacks expanded by
     *  the caller - {@link #gemsInBag} already does). */
    private static Map<GemSpecies, Integer> countGems(List<Item> gems) {
        Map<GemSpecies, Integer> counts = new EnumMap<>(GemSpecies.class);
        if (gems == null) return counts;
        for (Item g : gems) {
            if (g != null && g.isGem()) counts.merge(g.gemSpecies, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<GemSpecies, Integer> countRecipe(GemRecipe r) {
        Map<GemSpecies, Integer> counts = new EnumMap<>(GemSpecies.class);
        for (GemSpecies sp : r.ingredients) counts.merge(sp, 1, Integer::sum);
        return counts;
    }

    /** True if {@code outer} holds at least {@code inner}'s count for every
     *  species in {@code inner}. */
    private static boolean contains(Map<GemSpecies, Integer> outer,
                                    Map<GemSpecies, Integer> inner) {
        for (Map.Entry<GemSpecies, Integer> e : inner.entrySet()) {
            if (outer.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }
}
