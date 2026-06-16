package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.GemSpecies.GemClass;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Level.VisualTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Gem spawn helpers and the gem factory used by {@code placeGems} (RL-47). Gems are picked by
 * rarity {@link GemClass} and biased toward the level's theme by affinity (matching 2x, else
 * 0.5x - the same soft weighting items use). Equip/unequip live on
 * {@link com.bjsp123.rl2.model.Inventory}; gems carry no stats today.
 */
public final class GemSystem {

    private GemSystem() {}

    /** Affinity weight: a gem whose affinity matches the level theme is twice as likely;
     *  a mismatch is half as likely. Mirrors {@code ItemGenerator.themeMultiplier}. */
    private static double affinityWeight(VisualTheme gemAffinity, VisualTheme levelTheme) {
        if (gemAffinity == null || levelTheme == null) return 1.0;
        return gemAffinity == levelTheme ? 2.0 : 0.5;
    }

    private static int classWeight(GemClass cls) {
        return switch (cls) {
            case BASIC  -> GameBalance.GEM_WEIGHT_BASIC;
            case METAL  -> GameBalance.GEM_WEIGHT_METAL;
            case EXOTIC -> GameBalance.GEM_WEIGHT_EXOTIC;
        };
    }

    /** Player-facing rarity word for a gem class: BASIC = "simple",
     *  METAL = "metal", EXOTIC = "exotic". Used in recycle/forge descriptions. */
    public static String classLabel(GemClass cls) {
        return switch (cls) {
            case BASIC  -> "simple";
            case METAL  -> "metal";
            case EXOTIC -> "exotic";
        };
    }

    /** Pick a gem species of rarity {@code cls}, affinity-weighted toward {@code levelTheme}.
     *  Returns {@code null} only if the class has no members (never, for the fixed roster). */
    public static GemSpecies rollSpeciesOfClass(GemClass cls, VisualTheme levelTheme, Random rng) {
        return weightedPick(g -> g.gemClass == cls ? affinityWeight(g.theme, levelTheme) : 0.0, rng);
    }

    /** Pick any gem species, weighted by rarity class x affinity. Backs the generic
     *  {@code LootCategory.GEM} reference (ANY scatter, themed-room {@code GEM} cells). */
    public static GemSpecies rollSpeciesWeighted(VisualTheme levelTheme, Random rng) {
        return weightedPick(g -> classWeight(g.gemClass) * affinityWeight(g.theme, levelTheme), rng);
    }

    private interface Weigher { double weight(GemSpecies g); }

    private static GemSpecies weightedPick(Weigher w, Random rng) {
        List<GemSpecies> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (GemSpecies g : GemSpecies.values()) {
            double weight = w.weight(g);
            if (weight <= 0) continue;
            pool.add(g);
            weights.add(weight);
            total += weight;
        }
        if (pool.isEmpty()) return null;
        double r = rng.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += weights.get(i);
            if (r < acc) return pool.get(i);
        }
        return pool.get(pool.size() - 1);   // fp-drift fallback
    }

    /** Build a gem of {@code species}. The factory point for level population and loot. */
    public static Item createGem(GemSpecies species) {
        Item it = new Item();
        // Procedural item - null type. Identity is carried by gemSpecies, and Item.isGem
        // reads the species field rather than checking the type.
        it.inventoryCategory = InventoryCategory.GEM;
        it.gemSpecies = species;
        it.name = ItemNames.gemDisplayName(it);
        it.description = TextCatalog.gemDescription(species.name(), "");
        return it;
    }
}
