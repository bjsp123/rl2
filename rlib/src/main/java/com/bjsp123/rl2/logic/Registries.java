package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.util.CsvRegistryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Single access point for CSV-backed game-data registries. */
public final class Registries {
    private static final CsvRegistryStore<ItemDefinition> ITEMS =
            new CsvRegistryStore<>("items.csv", ItemDefinition::parseAll, d -> d.type);
    private static final CsvRegistryStore<MobDefinition> MOBS =
            new CsvRegistryStore<>("mobs.csv", MobDefinition::parseAll, d -> d.type);
    private static final CsvRegistryStore<BrandDefinition> BRANDS =
            new CsvRegistryStore<>("brands.csv", BrandDefinition::parseAll, d -> d.brand);
    private static final CsvRegistryStore<ThemedRoomDefinition> THEMED_ROOMS =
            new CsvRegistryStore<>("themedrooms.csv", ThemedRoomDefinition::parseAll, d -> d.type);
    private static final CsvRegistryStore<GemRecipe> RECIPES =
            new CsvRegistryStore<>("recipes.csv", GemRecipe::parseAll, r -> r.output);
    private static final CsvRegistryStore<GemDefinition> GEMS =
            new CsvRegistryStore<>("gems.csv", GemDefinition::parseAll, d -> d.species.name());

    private static final Map<String, Set<String>> mobFactionMembers = new LinkedHashMap<>();
    private static final Set<String> EMPTY_SET = Collections.emptySet();

    private Registries() {}

    public static void loadItems(String csv) { ITEMS.load(csv); }
    public static ItemDefinition item(String type) { return ITEMS.get(type); }
    public static Set<String> itemTypes() { return ITEMS.knownTypes(); }

    public static List<String> itemTypesMatching(Predicate<ItemDefinition> predicate) {
        List<String> out = new ArrayList<>();
        if (predicate == null) return out;
        for (Map.Entry<String, ItemDefinition> e : ITEMS.map().entrySet()) {
            if (predicate.test(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    public static int itemTypeOrder(String type) {
        if (type == null) return Integer.MAX_VALUE;
        int i = 0;
        for (String t : ITEMS.map().keySet()) {
            if (t.equals(type)) return i;
            i++;
        }
        return Integer.MAX_VALUE;
    }

    public static void loadMobs(String csv) {
        MOBS.load(csv);
        mobFactionMembers.clear();
        for (MobDefinition d : MOBS.map().values()) {
            if (d.faction != null && !d.faction.isEmpty()) {
                mobFactionMembers
                        .computeIfAbsent(d.faction, k -> new LinkedHashSet<>())
                        .add(d.type);
            }
        }
    }

    public static MobDefinition mob(String mobType) { return MOBS.get(mobType); }
    public static Set<String> mobTypes() { return MOBS.knownTypes(); }

    public static Set<String> mobsInFaction(String faction) {
        if (faction == null || faction.isEmpty()) return EMPTY_SET;
        Set<String> s = mobFactionMembers.get(faction);
        return s == null ? EMPTY_SET : Collections.unmodifiableSet(s);
    }

    public static void loadBrands(String csv) { BRANDS.load(csv); }
    public static List<BrandDefinition> brands() { return List.copyOf(BRANDS.map().values()); }

    public static List<BrandDefinition> brandsForCategory(Item.InventoryCategory cat) {
        return brands().stream().filter(b -> b.itemTypes.contains(cat)).toList();
    }

    public static void loadThemedRooms(String csv) { THEMED_ROOMS.load(csv); }
    public static ThemedRoomDefinition themedRoom(String type) { return THEMED_ROOMS.get(type); }
    public static Set<String> themedRoomTypes() { return THEMED_ROOMS.knownTypes(); }

    public static void loadRecipes(String csv) { RECIPES.load(csv); }
    public static GemRecipe recipe(String output) { return RECIPES.get(output); }
    public static Set<String> recipeTypes() { return RECIPES.knownTypes(); }
    public static List<GemRecipe> recipes() { return List.copyOf(RECIPES.map().values()); }

    public static void loadGems(String csv) { GEMS.load(csv); }
    /** Gem data row for {@code species}, or {@code null} if gems.csv wasn't
     *  loaded or omits it. */
    public static GemDefinition gem(com.bjsp123.rl2.model.GemSpecies species) {
        return species == null ? null : GEMS.get(species.name());
    }
    public static List<GemDefinition> gems() { return List.copyOf(GEMS.map().values()); }
}
