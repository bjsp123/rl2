package com.bjsp123.rl2.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared backing store for the codebase's CSV-driven registries
 * ({@code MobRegistry}, {@code ItemRegistry}, {@code ThemedRoomRegistry}, ...).
 * Holds an insertion-ordered {@code Map<String, T>} keyed on the definition's
 * id string and provides the three operations every registry exposes:
 * {@link #load}, {@link #get}, {@link #knownTypes}.
 *
 * <p>Composed (not inherited) from each registry: registries instantiate a
 * single static {@code STORE} and forward through static facade methods.
 * That preserves each registry's existing static API ({@code FooRegistry.get(...)})
 * while collapsing the duplicated load/lookup boilerplate into one place.
 *
 * <p>Type-specific helpers (faction indexing, predicate filters, silhouette
 * lookups, etc.) stay in their owning registry - the store deliberately
 * doesn't try to generalise those.
 *
 * @param <T> the definition type held in this store (e.g. {@code ItemDefinition})
 */
public final class CsvRegistryStore<T> {

    private final String fileName;
    private final Function<String, ? extends Iterable<T>> parser;
    private final Function<T, String> idOf;
    private final Map<String, T> defs = new LinkedHashMap<>();

    /**
     * Build a fresh store.
     *
     * @param fileName  short label for error messages (e.g. {@code "items.csv"})
     * @param parser    parses a CSV string into the definition list (typically
     *                  a method reference like {@code FooDefinition::parseAll})
     * @param idOf      extracts the unique id string from a parsed definition
     *                  (typically a lambda like {@code d -> d.type})
     */
    public CsvRegistryStore(String fileName,
                            Function<String, ? extends Iterable<T>> parser,
                            Function<T, String> idOf) {
        this.fileName = fileName;
        this.parser   = parser;
        this.idOf     = idOf;
    }

    /** Parse {@code csv} and replace the store's contents. Calling twice is
     *  idempotent - the previous load is discarded first. Rows whose id
     *  comes back null throw, matching the existing per-registry behaviour. */
    public void load(String csv) {
        defs.clear();
        if (csv == null || csv.isEmpty()) return;
        for (T d : parser.apply(csv)) {
            String id = idOf.apply(d);
            if (id == null) {
                throw new IllegalArgumentException(fileName + " row missing type column");
            }
            defs.put(id, d);
        }
    }

    /** Definition for {@code id}, or {@code null} for unknown / null ids. */
    public T get(String id) {
        if (id == null) return null;
        return defs.get(id);
    }

    /** Read-only view of every id, preserving CSV row order. */
    public Set<String> knownTypes() {
        return Collections.unmodifiableSet(defs.keySet());
    }

    /** Live insertion-ordered map. Exposed for registries that need to do
     *  type-specific indexing across the full population (e.g.
     *  {@code MobRegistry}'s faction-member set, or
     *  {@code ItemRegistry}'s predicate-matched type list). Mutation through
     *  this view is unsupported. */
    public Map<String, T> map() {
        return Collections.unmodifiableMap(defs);
    }
}
