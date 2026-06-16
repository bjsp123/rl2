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

    // --- Recycle: item -> gems generation (RL-50) ----------------------------
    /** Source-item power at which METAL / EXOTIC gems become reachable in
     *  {@link #rollRecycleClass}. */
    private static final double RECYCLE_METAL_POWER  = 0.4;
    private static final double RECYCLE_EXOTIC_POWER = 0.7;

    /** Recycle "power" 0..1 for an item: base tier ({@code minPowerLevel}) plus
     *  +0.1 per enchant level, capped at 1. Drives both gem count and rarity
     *  odds. Single source shared by recycling and {@link #recycleForecast}. */
    public static double recyclePower(Item item) {
        if (item == null) return 0.0;
        return Math.min(1.0,
                Math.max(0.0, item.minPowerLevel) + Math.max(0, item.level) * 0.1);
    }

    /** Expected number of gems from recycling at {@code power}. Deliberately low:
     *  a power-0 item averages ~0.25 (a 1-in-4 chance of a single gem), rising
     *  to ~3 at full power. May be below 1, so weak items often yield nothing.
     *  Used by {@link #recycleGemCount} (the live roll) and
     *  {@link #recycleForecast} (the blurb) so they can't drift. */
    public static double recycleExpectedGems(double power) {
        return RECYCLE_BASE_GEMS + power * RECYCLE_GEMS_PER_POWER;
    }

    /** Base expected gems at power 0 (the "1 in 4" chance) and the extra
     *  expected gems added at full power. */
    private static final double RECYCLE_BASE_GEMS      = 0.25;
    private static final double RECYCLE_GEMS_PER_POWER = 2.75;   // power 1 -> ~3.0
    /** Hard cap on gems from a single recycle. */
    private static final int    RECYCLE_MAX_GEMS       = 4;

    /** Actual gem count for one recycle: the integer part of the expected value
     *  plus a fractional-chance extra, so a power-0 item drops a gem ~1 time in
     *  4 and richer items a few. Capped at {@link #RECYCLE_MAX_GEMS}. */
    public static int recycleGemCount(double power, Random rng) {
        double e = recycleExpectedGems(power);
        int n = (int) Math.floor(e);
        if (rng.nextDouble() < (e - n)) n++;
        return Math.max(0, Math.min(RECYCLE_MAX_GEMS, n));
    }

    /** Rarity class for one recycled gem: better odds of metal / exotic the
     *  higher the source item's power. */
    public static GemClass rollRecycleClass(double power, Random rng) {
        double r = rng.nextDouble();
        if (power >= RECYCLE_EXOTIC_POWER && r < power - 0.5) return GemClass.EXOTIC;
        if (power >= RECYCLE_METAL_POWER  && r < power)       return GemClass.METAL;
        return GemClass.BASIC;
    }

    /** Rough, player-facing description of what recycling {@code item} is likely
     *  to yield - for the recycle confirmation dialog. Mirrors
     *  {@link #recycleGemCount} + {@link #rollRecycleClass} thresholds so the
     *  blurb can't drift from the real rolls. Examples: "a simple gem"; "a few
     *  simple gems and perhaps a metal gem"; "a handful of simple gems, likely a
     *  metal gem, with a chance of an exotic gem". */
    public static String recycleForecast(Item item) {
        if (item == null) return "";
        double power = recyclePower(item);
        double expected = recycleExpectedGems(power);
        boolean metal  = power >= RECYCLE_METAL_POWER;
        boolean exotic = power >= RECYCLE_EXOTIC_POWER;
        String simple = classLabel(GemClass.BASIC);
        // Quantity wording from the EXPECTED gem count (deterministic), with a
        // "chance of" phrasing when the expectation is below one gem.
        StringBuilder sb = new StringBuilder();
        boolean singular;
        if (expected < 0.75) {
            sb.append("a chance of a ").append(simple).append(" gem");
            singular = true;
        } else if (expected < 1.5) {
            sb.append("a ").append(simple).append(" gem");
            singular = true;
        } else if (expected < 2.5) {
            sb.append("a couple of ").append(simple).append(" gems");
            singular = false;
        } else if (expected < 3.5) {
            sb.append("a few ").append(simple).append(" gems");
            singular = false;
        } else {
            sb.append("several ").append(simple).append(" gems");
            singular = false;
        }
        if (exotic) {
            sb.append(singular ? ", possibly a " : ", likely a ").append(classLabel(GemClass.METAL))
              .append(" gem, with a chance of an ").append(classLabel(GemClass.EXOTIC))
              .append(" gem");
        } else if (metal) {
            sb.append(" and perhaps a ").append(classLabel(GemClass.METAL)).append(" gem");
        }
        return sb.toString();
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
