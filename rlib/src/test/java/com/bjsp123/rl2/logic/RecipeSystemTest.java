package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guardrails for the simplified (all-ingredients-named) recipe model over the
 * real recipes.csv: every recipe must be exactly affordable from its own
 * ingredient list, consumption must remove exactly the required gems, and no
 * recipe may demand a retired (non-spawning) species. Ports the
 * {@code :rlib:dumpRecipes} round-trip into the always-run test suite.
 */
class RecipeSystemTest extends DataFixture {

    private static Inventory bagOf(List<Item> gems) {
        Inventory inv = new Inventory();
        inv.bag.addAll(gems);
        return inv;
    }

    private static List<Item> sampleGems(GemRecipe r) {
        List<Item> out = new ArrayList<>();
        for (GemSpecies sp : r.ingredients) out.add(GemSystem.createGem(sp));
        return out;
    }

    @Test
    void everyRecipeRoundTrips() {
        List<GemRecipe> recipes = Registries.recipes();
        assertFalse(recipes.isEmpty(), "no recipes loaded");
        for (GemRecipe r : recipes) {
            List<Item> gems = sampleGems(r);

            assertTrue(RecipeSystem.canAfford(r, bagOf(gems)),
                    r.output + " not affordable from its own ingredient list");

            List<Item> reversed = new ArrayList<>(gems);
            Collections.reverse(reversed);
            assertTrue(RecipeSystem.canAfford(r, bagOf(reversed)),
                    r.output + " affordability is order-sensitive");

            Inventory consumeBag = bagOf(gems);
            assertTrue(RecipeSystem.consume(r, consumeBag),
                    r.output + " consume() failed on its own fill");
            assertTrue(RecipeSystem.gemsInBag(consumeBag).isEmpty(),
                    r.output + " consume() left gems behind");

            List<Item> fewer = new ArrayList<>(gems.subList(0, gems.size() - 1));
            assertFalse(RecipeSystem.canAfford(r, bagOf(fewer)),
                    r.output + " affordable with too few gems");
        }
    }

    @Test
    void consumeWithSurplusRemovesExactCounts() {
        GemRecipe r = Registries.recipes().get(0);
        List<Item> gems = sampleGems(r);
        gems.add(GemSystem.createGem(GemSpecies.HAMETHYST));   // surplus
        gems.add(GemSystem.createGem(GemSpecies.GOLD));        // surplus
        Inventory inv = bagOf(gems);
        assertTrue(RecipeSystem.consume(r, inv));
        assertEquals(2, RecipeSystem.gemsInBag(inv).size(),
                "consume must remove exactly the recipe's gems, keeping surplus");
    }

    @Test
    void wrongSpeciesDoesNotPay() {
        // A bag of retired basics (or any species a recipe doesn't list) must
        // afford nothing that demands other gems.
        List<Item> retired = List.of(
                GemSystem.createGem(GemSpecies.LETTUSTONE),
                GemSystem.createGem(GemSpecies.SALAMITE),
                GemSystem.createGem(GemSpecies.ICELANDSPAR),
                GemSystem.createGem(GemSpecies.LETTUSTONE));
        for (GemRecipe r : Registries.recipes()) {
            assertFalse(RecipeSystem.canAfford(r, bagOf(retired)),
                    r.output + " affordable from retired gems only");
        }
    }

    @Test
    void candidatesUseSubsetSemantics() {
        // One hamethyst placed: every recipe listing hamethyst is a candidate.
        List<Item> oneH = List.of(GemSystem.createGem(GemSpecies.HAMETHYST));
        for (GemRecipe r : RecipeSystem.candidates(oneH)) {
            assertTrue(r.ingredients.contains(GemSpecies.HAMETHYST));
        }
        // Exceeding a per-species count disqualifies: 5 hamethysts fit no recipe.
        List<Item> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(GemSystem.createGem(GemSpecies.HAMETHYST));
        assertTrue(RecipeSystem.candidates(five).isEmpty(),
                "no recipe needs 5 hamethysts");
        // Nothing placed: every recipe is a candidate.
        assertEquals(Registries.recipes().size(),
                RecipeSystem.candidates(List.of()).size());
    }

    @Test
    void noRecipeRequiresARetiredSpecies() {
        for (GemRecipe r : Registries.recipes()) {
            for (GemSpecies sp : r.ingredients) {
                GemDefinition def = Registries.gem(sp);
                assertTrue(def == null || def.spawns,
                        r.output + " requires retired species " + sp);
            }
        }
    }

    @Test
    void retiredSpeciesNeverRollAsLoot() {
        java.util.Random rng = new java.util.Random(1234);
        for (int i = 0; i < 500; i++) {
            GemSpecies sp = GemSystem.rollSpeciesWeighted(null, rng);
            GemDefinition def = Registries.gem(sp);
            assertTrue(def == null || def.spawns, "rolled retired species " + sp);
        }
        // The BASIC class collapses to hamethyst everywhere.
        for (int i = 0; i < 100; i++) {
            assertEquals(GemSpecies.HAMETHYST, GemSystem.rollSpeciesOfClass(
                    GemSpecies.GemClass.BASIC, null, rng));
        }
    }
}
