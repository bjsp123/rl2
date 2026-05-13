package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;

/**
 * Factory for fresh {@link Item} instances. Catalog is fully data-driven -
 * every entry lives in {@code assets/data/items.csv} and is loaded into
 * {@link ItemRegistry} at startup. Adding a new item type requires only a CSV
 * row; no code change.
 *
 * <p>Procedural items (gems) are NOT in the registry - gems are built
 * dynamically by {@code GemSystem} per-level with a randomised species and
 * size, so callers needing a gem use that path instead of {@link #build}.
 */
public final class ItemFactory {

    private ItemFactory() {}

    /** Build a fresh item of {@code type} at level 0. Throws
     *  {@link IllegalArgumentException} when the type isn't in
     *  {@link ItemRegistry} (typo, or trying to build a procedural item like
     *  a gem through this path). */
    public static Item build(String type) {
        ItemDefinition def = ItemRegistry.get(type);
        if (def == null) {
            throw new IllegalArgumentException("no items.csv row for type: " + type);
        }
        Item it = new Item();
        def.apply(it);
        return it;
    }
}
