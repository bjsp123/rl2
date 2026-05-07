package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.util.CsvRegistryStore;

import java.util.Set;

/**
 * Static registry of every themed room known to the generator. Loaded once at
 * startup from {@code assets/data/themedrooms.csv} via {@link #load(String)};
 * mirrors {@link MobRegistry} / {@link ItemRegistry}. All plumbing is
 * delegated to {@link CsvRegistryStore}; this registry has no themed-room-specific
 * helpers (yet).
 */
public final class ThemedRoomRegistry {

    private static final CsvRegistryStore<ThemedRoomDefinition> STORE =
            new CsvRegistryStore<>("themedrooms.csv", ThemedRoomDefinition::parseAll, d -> d.type);

    private ThemedRoomRegistry() {}

    public static void load(String csv) { STORE.load(csv); }

    public static ThemedRoomDefinition get(String type) { return STORE.get(type); }

    public static Set<String> knownTypes() { return STORE.knownTypes(); }
}
