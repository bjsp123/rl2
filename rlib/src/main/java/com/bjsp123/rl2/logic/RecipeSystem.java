package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gem-recipe matching (RL-50). Resolves a set of raw ingredient gems against the
 * recipes loaded into {@link Registries}, producing the recipe (and hence the
 * output item id) a player would craft at a gem hearth.
 *
 * <p>Matching is order-insensitive. A recipe matches a gem set only when the set
 * fully satisfies every slot with no gems left over - each gem is consumed by at
 * most one slot. Because recipes are tiny (2-4 slots) and slot constraints nest
 * cleanly (NAMED is stricter than EXOTIC, EXOTIC than METAL_OR_EXOTIC, that than
 * ANY), a greedy assignment that fills the most-specific slots first is exact.
 *
 * <p>This layer only resolves gems -> recipe. Turning the matched recipe's
 * {@link GemRecipe#output} into an actual {@link Item} is
 * {@code ItemFactory.build(output)}, called by the forge once it confirms a
 * craft.
 */
public final class RecipeSystem {

    private RecipeSystem() {}

    /** Most-specific slot kinds first - the order the greedy matcher assigns in. */
    private static final Comparator<GemRecipe.Slot> BY_SPECIFICITY =
            Comparator.comparingInt(s -> s.kind.ordinal());

    /** The recipe exactly satisfied by {@code gems}, or {@code null} if none.
     *  Every gem must be consumed (slot count == gem count) and every slot
     *  filled. Order-insensitive. First matching recipe in registry order wins. */
    public static GemRecipe match(List<Item> gems) {
        if (gems == null || gems.isEmpty()) return null;
        for (GemRecipe r : Registries.recipes()) {
            if (r.slots.size() == gems.size() && canSatisfy(r.slots, gems)) {
                return r;
            }
        }
        return null;
    }

    /** Recipes whose slots can still be completed given the gems placed so far -
     *  i.e. the placed gems map onto a subset of the recipe's slots. Drives the
     *  forge's live filtering as the player drops gems into the hearth. With no
     *  gems placed, every recipe is a candidate. */
    public static List<GemRecipe> candidates(List<Item> placed) {
        List<GemRecipe> out = new ArrayList<>();
        for (GemRecipe r : Registries.recipes()) {
            if (placed == null || placed.isEmpty()) {
                out.add(r);
            } else if (placed.size() <= r.slots.size() && canSatisfy(r.slots, placed)) {
                out.add(r);
            }
        }
        return out;
    }

    /** True if the player's bag holds enough gems to satisfy every slot of
     *  {@code r}. Drives the forge grey-out (recipe shown but not buildable). */
    public static boolean canAfford(GemRecipe r, Inventory inv) {
        if (r == null || inv == null) return false;
        return assignFully(r.slots, gemsInBag(inv));
    }

    /** Consume the gems a recipe needs from the bag (the strictest-fitting gem
     *  per slot), if the bag can pay. Returns {@code true} and removes the gems
     *  on success; returns {@code false} and removes nothing otherwise. The
     *  caller then builds the output and adds it to the inventory. */
    public static boolean consume(GemRecipe r, Inventory inv) {
        if (r == null || inv == null) return false;
        List<Item> avail = gemsInBag(inv);
        avail.sort(Comparator.comparingInt(RecipeSystem::gemSpecificity).reversed());
        List<GemRecipe.Slot> slots = new ArrayList<>(r.slots);
        slots.sort(BY_SPECIFICITY);
        List<Item> chosen = new ArrayList<>();
        for (GemRecipe.Slot s : slots) {
            Item pick = null;
            for (Item g : avail) {
                if (s.accepts(g)) { pick = g; break; }
            }
            if (pick == null) return false;   // can't pay - leave the bag untouched
            avail.remove(pick);
            chosen.add(pick);
        }
        for (Item g : chosen) InventorySystem.removeOneFromBag(inv, g);
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

    /** True if every gem in {@code gems} can be assigned to a distinct slot of
     *  {@code slots} (gems may be fewer than slots - a partial fill). */
    private static boolean canSatisfy(List<GemRecipe.Slot> slots, List<Item> gems) {
        if (gems.size() > slots.size()) return false;
        List<GemRecipe.Slot> open = new ArrayList<>(slots);
        // Assign each gem to the most-specific still-open slot it fits, so loose
        // ANY gems don't greedily consume a slot a constrained gem also needs.
        List<Item> ordered = new ArrayList<>(gems);
        ordered.sort(Comparator.comparingInt(RecipeSystem::gemSpecificity));
        for (Item gem : ordered) {
            open.sort(BY_SPECIFICITY);
            GemRecipe.Slot picked = null;
            for (GemRecipe.Slot s : open) {
                if (s.accepts(gem)) { picked = s; break; }
            }
            if (picked == null) return false;
            open.remove(picked);
        }
        return true;
    }

    /** True if {@code slots} can be filled completely from {@code gems} (enough
     *  gems, all slots satisfied). */
    private static boolean assignFully(List<GemRecipe.Slot> slots, List<Item> gems) {
        if (gems.size() < slots.size()) return false;
        List<Item> pool = new ArrayList<>(gems);
        // Fill the most-specific slots first from the most-specific gems.
        List<GemRecipe.Slot> ordered = new ArrayList<>(slots);
        ordered.sort(BY_SPECIFICITY);
        for (GemRecipe.Slot s : ordered) {
            pool.sort(Comparator.comparingInt(RecipeSystem::gemSpecificity).reversed());
            Item picked = null;
            for (Item gem : pool) {
                if (s.accepts(gem)) { picked = gem; break; }
            }
            if (picked == null) return false;
            pool.remove(picked);
        }
        return true;
    }

    /** Rank a gem from most-specific (exotic) to least (basic), so the matcher
     *  spends rare gems on the slots that demand them. */
    private static int gemSpecificity(Item gem) {
        if (gem == null || !gem.isGem()) return 99;
        return switch (gem.gemSpecies.gemClass) {
            case EXOTIC -> 0;
            case METAL  -> 1;
            case BASIC  -> 2;
        };
    }

    /** True if any registered recipe slot could accept this gem - gates a future
     *  "combine" affordance on a bag gem. */
    public static boolean isCraftIngredient(Item gem) {
        if (gem == null || !gem.isGem()) return false;
        for (GemRecipe r : Registries.recipes()) {
            for (GemRecipe.Slot s : r.slots) {
                if (s.accepts(gem)) return true;
            }
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
}
