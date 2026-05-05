package com.bjsp123.rl2.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Static registry of every item known to the game. Loaded once at startup from
 * {@code assets/data/items.csv} via {@link #load(String)}. Mirrors
 * {@link MobRegistry}: callers ask for a definition by string id and inflate
 * it via {@link ItemFactory#build(String)}.
 *
 * <p>Procedural items (gems) are intentionally NOT in the CSV — they're built
 * dynamically per-level by {@code GemSystem} and have a {@code null} type
 * since their identity is carried by the {@code gemSpecies} + {@code gemSize}
 * fields.
 */
public final class ItemRegistry {

    private static final Map<String, ItemDefinition> defs = new LinkedHashMap<>();

    private ItemRegistry() {}

    /** Parse {@code csv} and populate the registry. Replaces any prior
     *  contents — calling twice is idempotent. */
    public static void load(String csv) {
        defs.clear();
        if (csv == null || csv.isEmpty()) return;
        for (ItemDefinition d : ItemDefinition.parseAll(csv)) {
            if (d.type == null) {
                throw new IllegalArgumentException("items.csv row missing type column");
            }
            defs.put(d.type, d);
        }
    }

    /** Lookup a definition by item-type string. Returns {@code null} for
     *  unknown types and for procedural items (gems) whose type is null. */
    public static ItemDefinition get(String type) {
        if (type == null) return null;
        return defs.get(type);
    }

    /** Read-only view of every item-type string in the CSV, in row order. */
    public static Set<String> knownTypes() {
        return Collections.unmodifiableSet(defs.keySet());
    }

    /** Definition of the item flagged as the empty-slot silhouette for {@code slot},
     *  or {@code null} if no item carries that mark. The inventory renderer paints
     *  this item's sprite as the placeholder for unequipped slots. */
    public static ItemDefinition silhouetteFor(com.bjsp123.rl2.model.Item.ItemSlot slot) {
        if (slot == null) return null;
        for (ItemDefinition d : defs.values()) {
            if (d.silhouetteForSlot == slot) return d;
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
        for (String t : defs.keySet()) {
            if (t.equals(type)) return i;
            i++;
        }
        return Integer.MAX_VALUE;
    }
}
