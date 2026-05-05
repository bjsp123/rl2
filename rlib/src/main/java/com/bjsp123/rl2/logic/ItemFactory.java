package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemType;

/**
 * Factory for fresh {@link Item} instances. Catalog is fully data-driven —
 * every entry lives in {@code assets/data/items.csv} and is loaded into
 * {@link ItemRegistry} at startup. Adding a new item type requires only a CSV
 * row and an {@link ItemType} enum constant; no factory method.
 *
 * <p>{@link ItemType#GEM} is NOT in the registry — gems are built dynamically
 * by {@code GemSystem} per-level with a randomised species and size, so
 * callers needing a gem use that path instead of {@link #build(ItemType)}.
 */
public final class ItemFactory {

    private ItemFactory() {}

    /** Build a fresh item of {@code type} at level 0. Throws
     *  {@link IllegalArgumentException} if the type isn't in {@link ItemRegistry}
     *  (notably {@link ItemType#GEM}, or any type whose CSV row is missing). */
    public static Item build(ItemType type) {
        ItemDefinition def = ItemRegistry.get(type);
        if (def == null) {
            throw new IllegalArgumentException("no items.csv row for type: " + type);
        }
        Item it = new Item();
        def.apply(it);
        return it;
    }
}
