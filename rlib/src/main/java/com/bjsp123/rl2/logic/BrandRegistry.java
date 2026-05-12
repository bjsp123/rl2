package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.util.CsvRegistryStore;

import java.util.List;

/**
 * Static registry of every brand loaded from {@code assets/data/brands.csv}.
 * Loaded once at startup via {@link #load(String)}.
 */
public final class BrandRegistry {

    private static final CsvRegistryStore<BrandDefinition> STORE =
            new CsvRegistryStore<>("brands.csv", BrandDefinition::parseAll, d -> d.brand);

    private BrandRegistry() {}

    /** Parse {@code csv} and populate the registry. Replaces any prior contents. */
    public static void load(String csv) { STORE.load(csv); }

    /** All loaded brands, in CSV row order. */
    public static List<BrandDefinition> all() { return List.copyOf(STORE.map().values()); }

    /** Brands eligible for the given item category, in CSV row order. */
    public static List<BrandDefinition> forCategory(Item.InventoryCategory cat) {
        return all().stream().filter(b -> b.itemTypes.contains(cat)).toList();
    }
}
