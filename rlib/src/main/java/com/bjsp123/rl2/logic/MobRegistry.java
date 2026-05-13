package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.util.CsvRegistryStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Static registry of every mob species known to the game. Loaded once at
 * startup from {@code assets/data/mobs.csv} via {@link #load(String)}.
 *
 * <p>The registry holds {@link MobDefinition}s rather than building Mobs
 * directly so callers (factories, level populators, encyclopaedia) can read
 * fields without spawning a throwaway template.
 *
 * <p>Common load/get/knownTypes plumbing lives in {@link CsvRegistryStore};
 * the faction-member secondary index is mob-specific and stays here.
 */
public final class MobRegistry {

    private static final CsvRegistryStore<MobDefinition> STORE =
            new CsvRegistryStore<>("mobs.csv", MobDefinition::parseAll, d -> d.type);

    /** {@code faction tag -> mob types carrying that tag}. Built by {@link #load}
     *  so attitude logic can ask "who else is in this mob's faction?" without
     *  scanning every definition each lookup. */
    private static final Map<String, Set<String>> factionMembers = new LinkedHashMap<>();
    private static final Set<String> EMPTY_SET = Collections.emptySet();

    private MobRegistry() {}

    /** Parse {@code csv} (the contents of {@code assets/data/mobs.csv}) and
     *  populate the registry. Replaces any prior contents - calling twice is
     *  idempotent. */
    public static void load(String csv) {
        STORE.load(csv);
        factionMembers.clear();
        for (MobDefinition d : STORE.map().values()) {
            if (d.faction != null && !d.faction.isEmpty()) {
                factionMembers
                        .computeIfAbsent(d.faction, k -> new LinkedHashSet<>())
                        .add(d.type);
            }
        }
    }

    /** Lookup a definition by mob-type string. Returns {@code null} for unknown
     *  types. The CSV does include the {@code PLAYER_*} kit rows - callers that
     *  want to skip them filter on {@code def.behavior == Mob.Behavior.PLAYER}. */
    public static MobDefinition get(String mobType) { return STORE.get(mobType); }

    /** Read-only view of every mob-type string in the CSV. Order matches the
     *  CSV's row order (insertion-order map). The {@code PLAYER_*} kit rows
     *  are included - callers that only want random-encounter species filter
     *  by {@code def.behavior != PLAYER}. */
    public static Set<String> knownTypes() { return STORE.knownTypes(); }

    /** Mob types tagged with the same {@code faction} string. Empty set when
     *  the faction is null/empty/unknown. Used by {@code MobSystem}'s
     *  ally-defense rule to walk a mob's faction-mates without an O(n) scan. */
    public static Set<String> mobsInFaction(String faction) {
        if (faction == null || faction.isEmpty()) return EMPTY_SET;
        Set<String> s = factionMembers.get(faction);
        return s == null ? EMPTY_SET : Collections.unmodifiableSet(s);
    }
}
