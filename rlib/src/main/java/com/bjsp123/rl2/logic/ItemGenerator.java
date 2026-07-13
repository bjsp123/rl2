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
 * future random-event drops) routes through here so the
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
        /** A gem, affinity-weighted toward the level theme. Bypasses the items.csv pool. */
        GEM,
        /** A rare reward gem for special rooms / unique mobs: 25% an exotic gem, else 50%
         *  a metal gem, else nothing (a null roll). */
        SPECIAL_GEM,
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
     *  population passes {@code true} so restricted rewards can appear via the
     *  standard category resolution; mob drops and random scatter use the
     *  default {@code false} so those items only ever spawn via guaranteed-per-level
     *  scatter or explicit themed-room drops. (The POWER_ORB is additionally kept out
     *  of the POWERUPS category entirely - see {@code categoryPredicate} - so it is
     *  never a drop; it spawns only via its guaranteed 1-per-level scatter.) */
    public static Item generateItem(double powerLevel, VisualTheme theme,
                                    LootCategory cat, boolean includeRestricted,
                                    Random rng) {
        if (rng == null) return null;
        LootCategory c = (cat == null) ? LootCategory.ANY : cat;

        // GEM / SPECIAL_GEM are separate code paths - gems are procedural (no items.csv
        // row) and route through GemSystem instead of ItemRegistry.
        if (c == LootCategory.GEM) return rollGem(theme, rng);
        if (c == LootCategory.SPECIAL_GEM) return rollSpecialGem(theme, rng);

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

    /** Roll one mob-drop item for a {@code dropType} token (see
     *  {@link com.bjsp123.rl2.logic.MobDefinition#dropTypes}). Recognised tokens:
     *  EQUIPMENT, MAGIC_ITEMS, FOOD, POTION, GEM, SPECIAL_GEM, POWERUPS, BOMBS.
     *  Unknown tokens (including the NOTHING_AT_ALL control marker) yield
     *  {@code null}. */
    public static Item generateDrop(String dropType, double powerLevel,
                                    VisualTheme theme, Random rng) {
        if (dropType == null || rng == null) return null;
        return switch (dropType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "EQUIPMENT"          -> rollEquipment(powerLevel, theme, rng);
            case "MAGIC_ITEMS"        -> generateItem(powerLevel, theme, LootCategory.MAGIC_ITEMS, rng);
            case "FOOD"               -> generateItem(powerLevel, theme, LootCategory.FOOD, rng);
            case "POTION", "POTIONS"  -> generateItem(powerLevel, theme, LootCategory.POTIONS, rng);
            case "GEM"                -> rollGem(theme, rng);
            case "SPECIAL_GEM"        -> rollSpecialGem(theme, rng);
            case "POWERUPS"           -> generateItem(powerLevel, theme, LootCategory.POWERUPS, rng);
            case "BOMBS"              -> generateItem(powerLevel, theme, LootCategory.BOMBS, rng);
            default                   -> null;
        };
    }

    /** Equipment drop: weapon and armor are each twice as likely as an offhand
     *  or amulet. Picks the slot first (so the ratio is independent of how many
     *  item types each slot has), then rolls a themed, power-weighted item of
     *  that slot. */
    private static final InventoryCategory[] EQUIPMENT_SLOTS = {
            InventoryCategory.WEAPON, InventoryCategory.WEAPON,
            InventoryCategory.ARMOR,  InventoryCategory.ARMOR,
            InventoryCategory.OFFHAND, InventoryCategory.AMULET
    };

    private static Item rollEquipment(double powerLevel, VisualTheme theme, Random rng) {
        InventoryCategory slot = EQUIPMENT_SLOTS[rng.nextInt(EQUIPMENT_SLOTS.length)];
        Item it = rollOfSlot(slot, powerLevel, theme, rng);
        // Fall back to the broad equipment pool if that slot had no candidate.
        return it != null ? it : rollNonGem(powerLevel, theme, LootCategory.EQUIPMENT, false, rng);
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
        applyFinishingTouches(it, d, powerLevel, rng);
        return it;
    }

    /** Stamp the power-scaled plusses, top up charges, and roll a random brand
     *  on a freshly built item. Shared by {@link #buildItem} (named item) and
     *  {@link #finishItem} (randomly rolled item).
     *  Food-category items (edible food AND walk-over powerups) stay at level 0
     *  - their effect is flat, and effectiveLevel forces POWERUP to 0 anyway, so
     *  a stamped level would just be a misleading ghost value. */
    private static void applyFinishingTouches(Item it, ItemDefinition d,
                                              double powerLevel, Random rng) {
        if (d != null && it.inventoryCategory != Item.InventoryCategory.FOOD) {
            it.level = plussesForPower(d.powerMin, d.powerMax, powerLevel);
        }
        if (it.baseChargeMax > 0) {
            it.charge = it.maxCharge();
        }
        if (rng != null) BrandSystem.applyRandomBrand(it, rng);
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
     * <p>Public so external callers (loot tables) can apply
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
            case GEM, SPECIAL_GEM -> d -> false;   // unreachable; gem paths short-circuit
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
            // POWERUPS drops (mob loot, themed rooms) are the consumable pills only -
            // xp / mana / hp. The POWER_ORB is restrictedDrop, so it's excluded here and
            // only ever appears via its guaranteed 1-per-level scatter (RL-48).
            case POWERUPS     -> d -> d.useBehavior == UseBehavior.POWERUP && !d.restrictedDrop;
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
        for (GemSpecies g : GemSpecies.values()) {
            GemDefinition gd = Registries.gem(g);
            if (gd != null && gd.theme == theme) gems++;
        }
        if (gems == 0) return 0;
        int items = categoryTypes(LootCategory.NON_GEM).size();
        if (items == 0) return 1;
        return gems / (double) (gems + items);
    }

    private static Item rollGem(VisualTheme theme, Random rng) {
        GemSpecies species = GemSystem.rollSpeciesWeighted(theme, rng);
        return species == null ? null : GemSystem.createGem(species);
    }

    /** Special-room / unique-mob reward gem. A configurable share
     *  ({@link GameBalance#SPECIAL_GEM_BASIC_PCT}) is a plain hamethyst -
     *  special gems are the biggest rare-gem source, so this share is the
     *  main knob holding the per-world rare total near its budget. The rest
     *  splits 40% exotic / 60% metal, themed to the level. Returns
     *  {@code null} if no valid species exists for the rolled class + theme. */
    private static Item rollSpecialGem(VisualTheme theme, Random rng) {
        GemSpecies.GemClass cls;
        if (rng.nextDouble() * 100 < GameBalance.SPECIAL_GEM_BASIC_PCT) {
            cls = GemSpecies.GemClass.BASIC;
        } else {
            cls = rng.nextDouble() < 0.40
                    ? GemSpecies.GemClass.EXOTIC
                    : GemSpecies.GemClass.METAL;
        }
        GemSpecies species = GemSystem.rollSpeciesOfClass(cls, theme, rng);
        return species == null ? null : GemSystem.createGem(species);
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
            if (!d.allowsTheme(theme)) continue;   // hard theme gate (SHINY generic)
            double w = powerWeight(d.powerMin, d.powerMax, powerLevel) * d.dropWeight;
            if (w <= 0) continue;
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

        return finishItem(pick, powerLevel, rng);
    }

    /** Roll a single item restricted to one inventory category / slot (e.g.
     *  WEAPON, ARMOR). Same theme + power weighting as {@link #rollNonGem} but
     *  with an arbitrary slot filter; not cached (slot rolls are infrequent).
     *  Returns {@code null} when no eligible candidate exists. */
    private static Item rollOfSlot(InventoryCategory slot, double powerLevel,
                                   VisualTheme theme, Random rng) {
        List<String> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (String type : Registries.itemTypes()) {
            ItemDefinition d = Registries.item(type);
            if (d == null || d.inventoryCategory != slot) continue;
            if (d.restrictedDrop) continue;
            if (!d.allowsTheme(theme)) continue;   // hard theme gate (SHINY generic)
            double w = powerWeight(d.powerMin, d.powerMax, powerLevel) * d.dropWeight;
            if (w <= 0) continue;
            pool.add(type);
            weights.add(w);
            total += w;
        }
        if (pool.isEmpty()) return null;
        double r = rng.nextDouble() * total;
        String pick = pool.get(pool.size() - 1);
        double acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += weights.get(i);
            if (r < acc) { pick = pool.get(i); break; }
        }
        return finishItem(pick, powerLevel, rng);
    }

    /** Materialise a chosen item type: build it, stamp plusses for the power
     *  level (gems / FOOD-category items stay level 0, matching
     *  effectiveLevel's POWERUP->0 rule), top up charges, and roll a brand. */
    private static Item finishItem(String pick, double powerLevel, Random rng) {
        Item it = ItemFactory.build(pick);
        if (it == null) return null;
        ItemDefinition d = Registries.item(pick);
        applyFinishingTouches(it, d, powerLevel, rng);
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

    // Item theme handling is now a hard gate (ItemDefinition.allowsTheme), not a
    // soft weight multiplier - see rollNonGem / rollOfSlot. (Mob theme weighting
    // still lives in LevelFactoryPopulate.)
}
