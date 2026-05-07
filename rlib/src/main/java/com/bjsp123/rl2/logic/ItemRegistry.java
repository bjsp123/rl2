package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.util.CsvRegistryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Static registry of every item known to the game. Loaded once at startup from
 * {@code assets/data/items.csv} via {@link #load(String)}.
 *
 * <p>Procedural items (gems) are intentionally NOT in the CSV — they're built
 * dynamically per-level by {@code GemSystem} and have a {@code null} type
 * since their identity is carried by the {@code gemSpecies} + {@code gemSize}
 * fields.
 *
 * <p>Common load/get/knownTypes plumbing lives in {@link CsvRegistryStore};
 * the predicate-matched filter, silhouette lookup, and CSV-row-order helpers
 * are item-specific and stay here.
 */
public final class ItemRegistry {

    private static final CsvRegistryStore<ItemDefinition> STORE =
            new CsvRegistryStore<>("items.csv", ItemDefinition::parseAll, d -> d.type);

    private ItemRegistry() {}

    /** Parse {@code csv} and populate the registry. Replaces any prior
     *  contents — calling twice is idempotent. */
    public static void load(String csv) { STORE.load(csv); }

    /** Lookup a definition by item-type string. Returns {@code null} for
     *  unknown types and for procedural items (gems) whose type is null. */
    public static ItemDefinition get(String type) { return STORE.get(type); }

    /** Read-only view of every item-type string in the CSV, in row order. */
    public static Set<String> knownTypes() { return STORE.knownTypes(); }

    /** Item types whose definition matches {@code predicate}. Used by themed-room
     *  generation to resolve {@code @category} tokens (e.g. "any potion" =
     *  {@code useBehavior == DRINK || useBehavior == HEAL}). Order matches the
     *  CSV; callers pick uniformly with their own RNG. */
    public static List<String> itemTypesMatching(Predicate<ItemDefinition> predicate) {
        List<String> out = new ArrayList<>();
        if (predicate == null) return out;
        for (Map.Entry<String, ItemDefinition> e : STORE.map().entrySet()) {
            if (predicate.test(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Definition of the item flagged as the empty-slot silhouette for
     *  {@code category}, or {@code null} if no item carries that mark. The
     *  inventory renderer paints this item's sprite as the placeholder for
     *  unequipped slots. */
    public static ItemDefinition silhouetteFor(com.bjsp123.rl2.model.Item.InventoryCategory category) {
        if (category == null) return null;
        for (ItemDefinition d : STORE.map().values()) {
            if (d.silhouetteForCategory == category) return d;
        }
        return null;
    }

    /** Insertion-order index of {@code type} in the registry — i.e. the row
     *  position in {@code items.csv}. Used by inventory sort to keep stacks of
     *  related items grouped in their CSV order. Returns {@link Integer#MAX_VALUE}
     *  for unknown / null types so they sort to the end. */
    public static int typeOrder(String type) {
        if (type == null) return Integer.MAX_VALUE;
        int i = 0;
        for (String t : STORE.map().keySet()) {
            if (t.equals(type)) return i;
            i++;
        }
        return Integer.MAX_VALUE;
    }
}
