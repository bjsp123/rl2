package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemSlot;
import com.bjsp123.rl2.model.Item.ItemType;
import com.bjsp123.rl2.model.Level.VisualTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Gem spawn helpers — theme-gated species draw and the factory used by {@code placeGems}
 * and the recipe engine. Equip/unequip live on {@link com.bjsp123.rl2.model.Inventory}
 * (gems are just regular slot-bound items in {@link ItemSlot#GEM1}/{@code GEM2}/{@code GEM3});
 * stat bonuses from equipped gems will plug into {@link ItemSystem#contributeInto} when
 * designed, with no separate dispatch path needed.
 */
public final class GemSystem {

    private GemSystem() {}

    /** Per-tier relative weights for the spawn draw. Tier 1 species are common; tier 3
     *  species are rare. Tunable. */
    private static final int TIER1_WEIGHT = 70;
    private static final int TIER2_WEIGHT = 25;
    private static final int TIER3_WEIGHT = 5;

    /** Range of gems spawned per level (inclusive). */
    public static final int MIN_GEMS_PER_LEVEL = 4;
    public static final int MAX_GEMS_PER_LEVEL = 6;

    /** Pick a random gem species suitable for a level of {@code theme}, weighted by tier.
     *  Returns {@code null} when the theme has no gem table (every other VisualTheme). */
    public static GemSpecies rollSpeciesForTheme(VisualTheme theme, Random rng) {
        List<GemSpecies> pool = new ArrayList<>();
        int total = 0;
        for (GemSpecies g : GemSpecies.values()) {
            if (g.theme != theme) continue;
            int w = weightForTier(g.tier);
            for (int i = 0; i < w; i++) pool.add(g);
            total += w;
        }
        if (total == 0) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    private static int weightForTier(int tier) {
        return switch (tier) {
            case 1 -> TIER1_WEIGHT;
            case 2 -> TIER2_WEIGHT;
            default -> TIER3_WEIGHT;
        };
    }

    /** Build a level-1 ("tiny") gem of {@code species}. The factory point used both by
     *  level population and by the recipe engine — same-kind size-up calls
     *  {@link #createGem(GemSpecies, int)} with the next size. */
    public static Item createGem(GemSpecies species) {
        return createGem(species, 1);
    }

    public static Item createGem(GemSpecies species, int size) {
        Item it = new Item();
        it.type = ItemType.GEM;
        // Tag with the canonical gem slot so Inventory.equip routes it via the same
        // multi-slot resolution path that rings use (first empty / else swap GEM1).
        it.slot = ItemSlot.GEM1;
        it.gemSpecies = species;
        it.gemSize = Math.max(1, size);
        it.name = ItemSystem.gemDisplayName(it);
        it.glyph = "*";
        return it;
    }
}
