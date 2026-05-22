package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Level.VisualTheme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Single-source item generator. Every dungeon-side path that needs to roll a
 * fresh {@link Item} (level scatter, themed-room drops, mob loot tables,
 * crafting outcomes, future random-event drops) routes through here so the
 * power-level <-> plusses contract and category-filter set live in exactly
 * one place.
 *
 * <p>The two entry points are:
 * <ul>
 *   <li>{@link #generateItem(double, VisualTheme, LootCategory, Random)} -
 *       roll one item subject to power-level, theme, and category filters.
 *       Returns {@code null} when no eligible item exists for the request.</li>
 *   <li>{@link #generateItems(double, double, VisualTheme, LootCategory, Random)}
 *       - wrap {@code generateItem} to return a list of size {@code count}.
 *       The integer part of {@code count} is the guaranteed count; the
 *       fractional part is the probability of one extra item (so
 *       {@code count = 2.3} yields 2 items 70% of the time and 3 items 30%
 *       of the time). Useful for spec-driven loot tables that average a
 *       small number per source.</li>
 * </ul>
 *
 * <h3>Plusses (item.level)</h3>
 * Items beyond food and gems carry a {@code +N} level that scales their
 * stats. This generator picks {@code N} so the item's effective power
 * (defined as {@code (powerMin + powerMax) / 2 + 0.1 * N}) just exceeds the
 * caller's requested power level. So a sword with {@code powerMin=0.2,
 * powerMax=0.6} (avg {@code 0.4}) requested at power {@code 0.7} comes out
 * as {@code +4} (effective {@code 0.4 + 0.4 = 0.8 > 0.7}). Food is always
 * {@code +0} per the existing convention.
 *
 * <h3>Categories</h3>
 * The category-to-types mapping is computed once on first use, then
 * filtered by theme + power-level on every call. New categories add one
 * arm to {@link LootCategory} and one predicate in
 * {@link #categoryPredicate}. Misnamed strings parse to {@link LootCategory#ANY}
 * so a typo in a CSV cell falls back to a sensible default rather than
 * silently producing no items.
 */
public final class ItemGenerator {

    private ItemGenerator() {}

    /**
     * Coarse buckets for "what kind of item should this loot source roll?".
     * String tokens map onto these via {@link #parse(String)} so CSV cells
     * (e.g. a themed-room {@code items} column or a future loot-table column)
     * can carry a category by name.
     */
    public enum LootCategory {
        /** Any item - non-gem and gem species mix. Empty / null tokens parse to this. */
        ANY,
        /** Every non-gem item type. Mirrors today's level-scatter pool. */
        NON_GEM,
        /** Theme-appropriate gem (any tier). Bypasses the items.csv pool entirely. */
        GEM,
        /** Wearables: weapon / shield (offhand) / armor. */
        EQUIPMENT,
        /** Wand or amulet - what a mage caches in their tower. */
        MAGIC_ITEMS,
        /** Edible (carries an EAT use-behavior). */
        FOOD,
        /** Drinkable (DRINK or HEAL use-behavior). */
        POTIONS,
        /** Throwable bombs (IGNITE / OIL_SPLASH / BLAST / FREEZE thrown behaviors). */
        BOMBS,
        /** Food + potions + bombs - the "supplies stash" bucket. */
        CONSUMABLES,
        /** Meat drops from carnivorous animals. */
        MEAT,
        /** Character-progression pickup items such as power orbs. */
        POWERUPS;

        /** Token -> category. Whitespace-trimmed, case-insensitive. {@code null}
         *  or empty strings => {@link #ANY}. Unknown strings => {@code null} so
         *  callers (themed-room item refs, loot-table cells) can tell a category
         *  name apart from a literal item type and route to {@code buildItem}
         *  on miss. */
        public static LootCategory parse(String token) {
            if (token == null) return ANY;
            String t = token.trim().toUpperCase(java.util.Locale.ROOT);
            if (t.isEmpty() || t.equals("ANY")) return ANY;
            try {
                return LootCategory.valueOf(t);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // -- Public API -----------------------------------------------------------

    /** Roll {@code itemCount} items. Integer part = guaranteed count; the
     *  fractional part is the probability of one bonus item (e.g.
     *  {@code 2.3} -> 2 items 70% of the time, 3 items 30%). Returns an empty
     *  list when no eligible item exists for the (theme, category, power)
     *  triple - callers can tell "no eligible candidates" apart from "rolled
     *  zero" by passing whole-number counts and checking the list size. */
    public static List<Item> generateItems(double itemCount, double powerLevel,
                                           VisualTheme theme, LootCategory cat,
                                           Random rng) {
        if (itemCount <= 0 || rng == null) return new ArrayList<>();
        int n = (int) Math.floor(itemCount);
        double frac = itemCount - n;
        if (frac > 0 && rng.nextDouble() < frac) n++;
        List<Item> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Item it = generateItem(powerLevel, theme, cat, rng);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** Roll a single item. The category gates the candidate set; theme +
     *  power-level then weight the pick. Returns {@code null} when the
     *  category has no eligible candidates for this theme - e.g. asking for
     *  a GEM on a theme with no gem table. */
    public static Item generateItem(double powerLevel, VisualTheme theme,
                                    LootCategory cat, Random rng) {
        return generateItem(powerLevel, theme, cat, /*includeRestricted=*/ false, rng);
    }

    /** Overload that controls whether items with
     *  {@link ItemDefinition#restrictedDrop} are eligible. Themed-room
     *  population passes {@code true} to access headline rewards like
     *  POWER_ORB through the standard category resolution; mob drops and
     *  random scatter use the default {@code false} so those items only
     *  ever spawn via guaranteed-per-level scatter or explicit themed-room
     *  drops. */
    public static Item generateItem(double powerLevel, VisualTheme theme,
                                    LootCategory cat, boolean includeRestricted,
                                    Random rng) {
        if (rng == null) return null;
        LootCategory c = (cat == null) ? LootCategory.ANY : cat;

        // GEM is a separate code path - gems are procedural (no items.csv
        // row) and route through GemSystem instead of ItemRegistry.
        if (c == LootCategory.GEM) return rollGem(theme, rng);

        // ANY mixes a gem path in with the non-gem pool. The probability of
        // landing on the gem path is set to roughly the gem fraction of the
        // available registry - e.g. ~10-15% on a typical themed level. We
        // compute it as nGems / (nGems + nItems) where both numbers are
        // theme-filtered; if a theme has no gems, ANY collapses to NON_GEM.
        if (c == LootCategory.ANY && rng.nextDouble() < gemFraction(theme)) {
            Item g = rollGem(theme, rng);
            if (g != null) return g;
        }

        return rollNonGem(powerLevel, theme, c, includeRestricted, rng);
    }

    /** Build a specific item type and apply plusses for the given power level.
     *  Used for literal item references in themed-room {@code items} cells
     *  (e.g. a row that pre-declares {@code HEALING_POTION*1} instead of a
     *  {@code POTIONS} category) and any other non-random "I want exactly
     *  this item, scaled to this level" call site. Returns {@code null}
     *  when {@code type} is unknown to {@link ItemRegistry}. */
    public static Item buildItem(String type, double powerLevel) {
        return buildItem(type, powerLevel, null);
    }

    /** As {@link #buildItem(String, double)} but also applies a random brand
     *  (20% chance for equipment/amulets) when {@code rng} is non-null. */
    public static Item buildItem(String type, double powerLevel, Random rng) {
        if (type == null) return null;
        Item it;
        try {
            it = ItemFactory.build(type);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        if (it == null) return null;
        ItemDefinition d = Registries.item(type);
        if (d != null && d.useBehavior != UseBehavior.EAT) {
            it.level = plussesForPower(d.powerMin, d.powerMax, powerLevel);
        }
        if (it.baseChargeMax > 0) {
            it.charge = it.maxCharge();
        }
        if (rng != null) BrandSystem.applyRandomBrand(it, rng);
        return it;
    }

    // -- Plusses --------------------------------------------------------------

    /**
     * Compute {@code +N} for an item whose definition has the given power
     * band, given the requested power level. Each plus is worth
     * {@code 0.1} power; we add plusses until effective power
     * {@code ((min+max)/2 + 0.1*N)} just exceeds the requested level. Returns
     * 0 when the item is already at or above the request, or when the band
     * is invalid (e.g. powerMin > powerMax).
     *
     * <p>Public so external callers (loot tables, recipe outputs) can apply
     * the same scaling without re-rolling the whole item.
     */
    public static int plussesForPower(double powerMin, double powerMax, double requested) {
        if (powerMax < powerMin) return 0;
        double avg = (powerMin + powerMax) * 0.5;
        if (avg >= requested) return 0;
        int plusses = 0;
        double effective = avg;
        while (effective < requested) {
            plusses++;
            effective = avg + plusses * 0.1;
        }
        return plusses;
    }

    // -- Internals ------------------------------------------------------------

    /** Cache: category -> eligible (non-gem) types. Built lazily on first
     *  generator call, then reused for the JVM lifetime. {@link ItemRegistry}
     *  is loaded once at startup so the cache never goes stale during normal
     *  play; tests that reload the registry should call {@link #clearCache}. */
    private static final EnumMap<LootCategory, List<String>> CATEGORY_CACHE =
            new EnumMap<>(LootCategory.class);

    /** Reset the category cache. Call after reloading {@link ItemRegistry}
     *  in a test harness so the next generation pass sees the new types. */
    public static void clearCache() { CATEGORY_CACHE.clear(); }

    private static List<String> categoryTypes(LootCategory cat) {
        List<String> cached = CATEGORY_CACHE.get(cat);
        if (cached != null) return cached;
        Predicate<ItemDefinition> p = categoryPredicate(cat);
        List<String> out = new ArrayList<>();
        for (String type : Registries.itemTypes()) {
            ItemDefinition d = Registries.item(type);
            if (d == null) continue;
            // Gems are excluded from every non-GEM bucket - the GEM bucket is
            // the only legitimate gem path.
            if (d.inventoryCategory == InventoryCategory.GEM) continue;
            if (p.test(d)) out.add(type);
        }
        CATEGORY_CACHE.put(cat, out);
        return out;
    }

    private static Predicate<ItemDefinition> categoryPredicate(LootCategory cat) {
        return switch (cat) {
            case ANY, NON_GEM -> d -> true;
            case GEM          -> d -> false;   // unreachable; GEM short-circuits
            case EQUIPMENT    -> d -> d.inventoryCategory == InventoryCategory.WEAPON
                                   || d.inventoryCategory == InventoryCategory.OFFHAND
                                   || d.inventoryCategory == InventoryCategory.ARMOR;
            case MAGIC_ITEMS  -> d -> d.inventoryCategory == InventoryCategory.WAND
                                   || d.inventoryCategory == InventoryCategory.AMULET;
            case FOOD         -> d -> d.useBehavior == UseBehavior.EAT;
            case POTIONS      -> d -> d.useBehavior == UseBehavior.DRINK;
            case BOMBS        -> d -> isBomb(d);
            case CONSUMABLES  -> d -> d.useBehavior == UseBehavior.EAT
                                   || d.useBehavior == UseBehavior.DRINK
                                   || isBomb(d);
            case MEAT         -> d -> "FOUL_MEAT".equals(d.type)
                                   || "TASTY_MEAT".equals(d.type);
            case POWERUPS     -> d -> d.useBehavior == UseBehavior.POWERUP;
        };
    }

    private static boolean isBomb(ItemDefinition d) {
        return d.inventoryCategory == InventoryCategory.BOMB;
    }

    /** Probability that an ANY-category roll falls into the gem branch. Set
     *  to {@code nGemSpecies / (nGemSpecies + nNonGemTypes)} so it adapts to
     *  the registry size and theme; a theme with no gem table returns 0
     *  (and ANY collapses to NON_GEM for that theme). */
    private static double gemFraction(VisualTheme theme) {
        if (theme == null) return 0;
        int gems = 0;
        for (GemSpecies g : GemSpecies.values()) if (g.theme == theme) gems++;
        if (gems == 0) return 0;
        int items = categoryTypes(LootCategory.NON_GEM).size();
        if (items == 0) return 1;
        return gems / (double) (gems + items);
    }

    private static Item rollGem(VisualTheme theme, Random rng) {
        if (theme == null) return null;
        GemSpecies species = GemSystem.rollSpeciesForTheme(theme, rng);
        if (species == null) return null;
        return GemSystem.createGem(species, 1);
    }

    /** Pick a single non-gem item from the eligible pool: theme-matching,
     *  category-matching, with weight from {@link #powerWeight}. Picks
     *  uniformly from candidates with positive weight; returns {@code null}
     *  when the pool is empty. */
    private static Item rollNonGem(double powerLevel, VisualTheme theme,
                                   LootCategory cat, boolean includeRestricted,
                                   Random rng) {
        List<String> candidates = categoryTypes(cat);
        List<String> pool = new ArrayList<>(candidates.size());
        List<Double> weights = new ArrayList<>(candidates.size());
        double total = 0;
        for (String type : candidates) {
            ItemDefinition d = Registries.item(type);
            if (d == null) continue;
            if (d.restrictedDrop && !includeRestricted) continue;
            double w = powerWeight(d.powerMin, d.powerMax, powerLevel);
            if (w <= 0) continue;
            w *= themeMultiplier(d.theme, theme);
            pool.add(type);
            weights.add(w);
            total += w;
        }
        if (pool.isEmpty()) return null;

        // Weighted pick.
        double r = rng.nextDouble() * total;
        String pick = pool.get(pool.size() - 1);  // fallback for fp drift
        double acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += weights.get(i);
            if (r < acc) { pick = pool.get(i); break; }
        }

        Item it = ItemFactory.build(pick);
        if (it == null) return null;
        ItemDefinition d = Registries.item(pick);
        // Food and gems don't carry plusses (existing convention from
        // assignItemLevel); WANDs / EQUIPMENT / etc. all do.
        if (d != null && d.useBehavior != UseBehavior.EAT) {
            it.level = plussesForPower(d.powerMin, d.powerMax, powerLevel);
        }
        if (it.baseChargeMax > 0) {
            it.charge = it.maxCharge();
        }
        BrandSystem.applyRandomBrand(it, rng);
        return it;
    }

    /** Triangle weight over {@code [min, max]}, peaking at the midpoint and
     *  floored at {@link #POWER_EDGE_WEIGHT} inside the band. Mirrors the
     *  helper in {@link LevelFactoryPopulate} - duplicated here so the
     *  generator doesn't have to reach into the populator's internals.
     *  TODO: extract to {@code GameMath} once we have a third caller. */
    private static double powerWeight(double powerMin, double powerMax, double levelF) {
        if (levelF < powerMin || levelF > powerMax) return 0.0;
        double half = (powerMax - powerMin) * 0.5;
        if (half <= 0) return 1.0;
        double mid = (powerMin + powerMax) * 0.5;
        double w = 1.0 - Math.abs(mid - levelF) / half;
        return Math.max(POWER_EDGE_WEIGHT, w);
    }

    private static final double POWER_EDGE_WEIGHT = 0.05;

    /** Weight multiplier for a definition's theme relative to the level's theme.
     *  Null theme = theme-neutral (1x); matching = twice as likely (2x);
     *  mismatching = half as likely (0.5x). */
    private static double themeMultiplier(VisualTheme defTheme, VisualTheme levelTheme) {
        if (defTheme == null) return 1.0;
        return defTheme == levelTheme ? 2.0 : 0.5;
    }
}
