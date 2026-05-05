package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item.ItemType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Static registry of every item known to the game. Loaded once at startup from
 * {@code assets/data/items.csv} via {@link #load(String)}. Mirrors
 * {@link MobRegistry}: callers ask for a definition by {@link ItemType} and
 * inflate it via {@link ItemFactory#build(ItemType)}.
 *
 * <p>{@link ItemType#GEM} is intentionally NOT in the CSV — gems are built
 * dynamically per-level by {@code GemSystem} with a randomised species and
 * size.
 */
public final class ItemRegistry {

    private static final Map<ItemType, ItemDefinition> defs = new EnumMap<>(ItemType.class);

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

    /** Lookup a definition by item type. Returns {@code null} for types not in
     *  the CSV (notably {@link ItemType#GEM}). */
    public static ItemDefinition get(ItemType type) {
        if (type == null) return null;
        return defs.get(type);
    }

    /** Read-only view of every item type in the CSV, in row order. Excludes
     *  {@link ItemType#GEM} since gems aren't catalogued here. */
    public static Set<ItemType> knownTypes() {
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
}
