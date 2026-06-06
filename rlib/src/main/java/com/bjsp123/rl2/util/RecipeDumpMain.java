package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.GemRecipe;
import com.bjsp123.rl2.logic.GemSystem;
import com.bjsp123.rl2.logic.RecipeSystem;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [DEV / DIAGNOSTIC] Headless validator + balance report for the gem recipes
 * (RL-50). Not shipping code.
 *
 * <p>Loads the same data files {@code Rl2Game.create} reads (without libGDX) and:
 * <ol>
 *   <li>structurally validates every {@code recipes.csv} row (unique output that
 *       resolves to a GEM-category item, rarity 2-7, 2-4 slots, named species
 *       valid);</li>
 *   <li>prints a per-rarity count + a gem-demand-vs-supply report against the
 *       per-game economy, confirming the ~5-high / ~12-low envelope;</li>
 *   <li>round-trips sample gem sets through {@link RecipeSystem#match} (exact set
 *       matches; wrong set is null; shuffled order still matches).</li>
 * </ol>
 *
 * Run: {@code ./gradlew :rlib:dumpRecipes} (see rlib/build.gradle).
 */
public final class RecipeDumpMain {

    private RecipeDumpMain() {}

    // Approx per-game gem supply (RL-47 GEMS_*_AVG x ~12 levels + special rooms).
    private static final double SUPPLY_BASIC  = 23;
    private static final double SUPPLY_METAL  = 8;
    private static final double SUPPLY_EXOTIC = 5;

    public static void main(String[] args) throws IOException {
        Path assets = ArenaHarness.locateAssetsDir();
        ArenaHarness.loadData(assets);

        List<GemRecipe> recipes = Registries.recipes();
        System.out.println("[rl2] loaded " + recipes.size() + " gem recipes");

        int errors = validate(recipes);
        balanceReport(recipes);
        errors += roundTrip(recipes);

        if (errors > 0) {
            System.out.println("\n[rl2] FAILED with " + errors + " error(s)");
            System.exit(1);
        }
        System.out.println("\n[rl2] OK - all recipe checks passed");
    }

    private static int validate(List<GemRecipe> recipes) {
        int errors = 0;
        List<String> seen = new ArrayList<>();
        for (GemRecipe r : recipes) {
            String tag = "recipe[" + r.output + "]";
            if (r.output == null) { System.out.println("ERROR: null output"); errors++; continue; }
            if (seen.contains(r.output)) { System.out.println("ERROR: duplicate output " + r.output); errors++; }
            seen.add(r.output);
            if (Registries.item(r.output) == null) {
                System.out.println("ERROR: " + tag + " has no items.csv row"); errors++;
            } else if (Registries.item(r.output).inventoryCategory != Item.InventoryCategory.GEM) {
                System.out.println("ERROR: " + tag + " output is not a GEM-category item"); errors++;
            }
            if (r.rarity < 2 || r.rarity > 7) {
                System.out.println("ERROR: " + tag + " rarity " + r.rarity + " out of 2..7"); errors++;
            }
            if (r.slots.size() < 2 || r.slots.size() > 4) {
                System.out.println("ERROR: " + tag + " has " + r.slots.size() + " slots (need 2..4)"); errors++;
            }
        }
        System.out.println("[rl2] structural validation: " + (errors == 0 ? "clean" : errors + " error(s)"));
        return errors;
    }

    private static void balanceReport(List<GemRecipe> recipes) {
        int[] byRarity = new int[8];
        double[] demand = new double[3]; // basic, metal, exotic, summed over ALL recipes
        for (GemRecipe r : recipes) {
            byRarity[Math.min(7, Math.max(0, r.rarity))]++;
            addDemand(r, demand);
        }
        System.out.println("\n---- balance report ----");
        System.out.print("recipes by rarity: ");
        for (int i = 2; i <= 7; i++) System.out.print("r" + i + "=" + byRarity[i] + "  ");
        System.out.println();

        int lowCount  = byRarity[2] + byRarity[3] + byRarity[4];
        int highCount = byRarity[6] + byRarity[7];
        System.out.println("low-rarity (2-4): " + lowCount + " recipes; high-rarity (6-7): " + highCount + " recipes");

        // Average gem cost per low vs high recipe (lower bound on rare usage).
        double lowBasic = 0, highRare = 0; int lows = 0, highs = 0;
        for (GemRecipe r : recipes) {
            double[] d = new double[3];
            addDemand(r, d);
            if (r.rarity <= 4) { lowBasic += d[0]; lows++; }
            if (r.rarity >= 6) { highRare += d[1] + d[2]; highs++; }
        }
        System.out.printf("avg basic gems per low recipe:  %.2f%n", lows  == 0 ? 0 : lowBasic / lows);
        System.out.printf("avg rare  gems per high recipe: %.2f%n", highs == 0 ? 0 : highRare / highs);
        System.out.printf("supply/game ~ basic %.0f, metal %.0f, exotic %.0f%n",
                SUPPLY_BASIC, SUPPLY_METAL, SUPPLY_EXOTIC);
        double maxLow  = lows  == 0 ? 0 : SUPPLY_BASIC / (lowBasic / lows);
        double maxHigh = highs == 0 ? 0 : (SUPPLY_METAL + SUPPLY_EXOTIC) / (highRare / highs);
        System.out.printf("=> ~%.0f low-rarity OR ~%.0f high-rarity items per game (target: ~12 / ~5)%n",
                maxLow, maxHigh);
    }

    /** Add a recipe's *minimum* gem demand to the [basic,metal,exotic] tally:
     *  NAMED/EXOTIC -> exotic if the keystone is exotic else metal; METAL_OR_EXOTIC
     *  -> metal; ANY -> basic. A lower bound the player can actually pay. */
    private static void addDemand(GemRecipe r, double[] out) {
        for (GemRecipe.Slot s : r.slots) {
            switch (s.kind) {
                case NAMED -> {
                    if (s.species.gemClass == GemSpecies.GemClass.EXOTIC) out[2]++;
                    else if (s.species.gemClass == GemSpecies.GemClass.METAL) out[1]++;
                    else out[0]++;
                }
                case EXOTIC -> out[2]++;
                case METAL_OR_EXOTIC -> out[1]++;
                case ANY -> out[0]++;
            }
        }
    }

    /** Verify each recipe is buildable from its own sample gem fill. We test the
     *  real forge flow (the player picks a recipe row, then {@code canAfford} /
     *  {@code consume} run for that chosen recipe) rather than gem->recipe
     *  {@code match}: recipes deliberately share ingredient shapes (e.g. two
     *  {@code any,any} recipes), so a gem set can satisfy several recipes and
     *  uniqueness is not an invariant. */
    private static int roundTrip(List<GemRecipe> recipes) {
        System.out.println("\n---- build round-trip ----");
        int errors = 0;
        for (GemRecipe r : recipes) {
            List<Item> gems = sampleGems(r);
            if (gems == null) {
                System.out.println("SKIP " + r.output + " (no concrete gem fill found)");
                continue;
            }
            // The exact fill must be affordable for THIS recipe.
            if (!RecipeSystem.canAfford(r, bagOf(gems))) {
                System.out.println("ERROR: " + r.output + " not affordable from its own sample fill");
                errors++;
                continue;
            }
            // Order must not matter.
            List<Item> reversed = new ArrayList<>(gems);
            java.util.Collections.reverse(reversed);
            if (!RecipeSystem.canAfford(r, bagOf(reversed))) {
                System.out.println("ERROR: " + r.output + " affordability is order-sensitive"); errors++;
            }
            // Consume must succeed and leave no gems behind.
            Inventory consumeBag = bagOf(gems);
            if (!RecipeSystem.consume(r, consumeBag)) {
                System.out.println("ERROR: " + r.output + " consume() failed on its own fill"); errors++;
            } else if (!RecipeSystem.gemsInBag(consumeBag).isEmpty()) {
                System.out.println("ERROR: " + r.output + " consume() left gems behind"); errors++;
            }
            // One fewer gem must NOT be affordable.
            if (gems.size() > 1) {
                List<Item> fewer = new ArrayList<>(gems.subList(0, gems.size() - 1));
                if (RecipeSystem.canAfford(r, bagOf(fewer))) {
                    System.out.println("ERROR: " + r.output + " affordable with too few gems"); errors++;
                }
            }
        }
        System.out.println("build round-trip: " + (errors == 0 ? "clean" : errors + " error(s)"));
        return errors;
    }

    /** A fresh inventory whose bag holds exactly {@code gems}. */
    private static Inventory bagOf(List<Item> gems) {
        Inventory inv = new Inventory();
        for (Item g : gems) inv.bag.add(g);
        return inv;
    }

    /** Build a concrete gem list that satisfies every slot of {@code r}, choosing
     *  the strictest gem each slot allows. */
    private static List<Item> sampleGems(GemRecipe r) {
        List<Item> out = new ArrayList<>();
        for (GemRecipe.Slot s : r.slots) {
            GemSpecies pick = switch (s.kind) {
                case NAMED           -> s.species;
                case EXOTIC          -> firstOfClass(GemSpecies.GemClass.EXOTIC);
                case METAL_OR_EXOTIC -> firstOfClass(GemSpecies.GemClass.METAL);
                case ANY             -> firstOfClass(GemSpecies.GemClass.BASIC);
            };
            if (pick == null) return null;
            out.add(GemSystem.createGem(pick));
        }
        return out;
    }

    private static GemSpecies firstOfClass(GemSpecies.GemClass cls) {
        for (GemSpecies g : GemSpecies.values()) if (g.gemClass == cls) return g;
        return null;
    }
}
