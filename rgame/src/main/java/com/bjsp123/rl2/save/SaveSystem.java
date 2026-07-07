package com.bjsp123.rl2.save;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.persistence.Persistence;
import com.bjsp123.rl2.logic.TurnSystem;

import java.util.EnumMap;

/** Saves and loads in-progress runs into a small fixed pool of slots. */
public class SaveSystem {

    public static final int SLOTS = 4;

    /** Enum key classes that may appear as an {@code EnumMap} keyType in a save
     *  blob, keyed by class name. Explicit registry instead of {@code Class.forName}
     *  so save loading works identically on the web (TeaVM) build, where dynamic
     *  class lookup is unavailable. Add an entry when persisting a new
     *  EnumMap-typed model field. */
    @SuppressWarnings("rawtypes")
    private static final java.util.Map<String, Class<? extends Enum>> ENUM_MAP_KEY_TYPES =
            java.util.Map.of(
                    com.bjsp123.rl2.model.Perk.class.getName(),
                    com.bjsp123.rl2.model.Perk.class);

    private final Persistence persistence;
    private final Json json;
    /** Reason the most recent {@link #load} failed (exception summary), or null
     *  on success. Surfaced in the save-screen "could not load" notice so the
     *  failure is diagnosable rather than opaque. */
    private String lastLoadError;

    public SaveSystem(Persistence persistence) {
        this.persistence = persistence;
        this.json = buildJson();
    }

    /** Build the configured {@link Json} used for save round-trips. Static so
     *  offline diagnostics ({@code SaveLoadDebugMain}) can reproduce a load with
     *  the identical serializer set. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Json buildJson() {
        Json json = new Json();
        // Point is a record (no setters / final fields); spell out serialization.
        json.setSerializer(Point.class, new Json.Serializer<Point>() {
            @Override
            public void write(Json json, Point p, Class knownType) {
                json.writeObjectStart();
                json.writeValue("x", p.x());
                json.writeValue("y", p.y());
                json.writeObjectEnd();
            }
            @Override
            public Point read(Json json, JsonValue value, Class type) {
                return new Point(value.getDouble("x"), value.getDouble("y"));
            }
        });
        // EnumMap has no no-arg constructor (it needs the key class), so libGDX's
        // default reflective instantiation throws on load. We persist the key class
        // name alongside the entries so the map round-trips for any enum-keyed field.
        // Currently only Mob.perks (EnumMap<Perk, Integer>) uses this.
        //
        // Deliberately reflection-free (web/TeaVM portability): the key class is
        // derived from an entry via getDeclaringClass() on write, and looked up in
        // ENUM_MAP_KEY_TYPES on read - never Class.forName or JDK-internal fields.
        Json.Serializer<EnumMap> enumMapSerializer = new Json.Serializer<EnumMap>() {
            @Override
            public void write(Json json, EnumMap m, Class knownType) {
                json.writeObjectStart();
                json.writeValue("keyType", keyClassOf(m).getName());
                json.writeObjectStart("values");
                for (Object o : m.entrySet()) {
                    java.util.Map.Entry e = (java.util.Map.Entry) o;
                    json.writeValue(((Enum) e.getKey()).name(), e.getValue());
                }
                json.writeObjectEnd();
                json.writeObjectEnd();
            }
            @Override
            public EnumMap read(Json json, JsonValue value, Class type) {
                String keyTypeName = value.getString("keyType");
                Class<? extends Enum> keyClass = ENUM_MAP_KEY_TYPES.get(keyTypeName);
                if (keyClass == null) {
                    throw new com.badlogic.gdx.utils.SerializationException(
                            "EnumMap keyType not registered in SaveSystem.ENUM_MAP_KEY_TYPES: "
                                    + keyTypeName);
                }
                EnumMap result = new EnumMap(keyClass);
                JsonValue values = value.get("values");
                if (values != null) {
                    for (JsonValue child = values.child; child != null; child = child.next) {
                        Enum key = Enum.valueOf(keyClass, child.name);
                        // Codebase only uses EnumMap<?, Integer>; revisit if non-int
                        // values appear.
                        result.put(key, child.asInt());
                    }
                }
                return result;
            }
            private Class<? extends Enum> keyClassOf(EnumMap m) {
                for (Object o : m.keySet()) {
                    return ((Enum) o).getDeclaringClass();
                }
                // Empty map: the key class is unrecoverable without reflection, and
                // the sole persisted EnumMap (Mob.perks) is the sole registry entry
                // whenever this holds. If a second EnumMap field is ever persisted,
                // register it and revisit this fallback.
                if (ENUM_MAP_KEY_TYPES.size() == 1) {
                    return ENUM_MAP_KEY_TYPES.values().iterator().next();
                }
                throw new com.badlogic.gdx.utils.SerializationException(
                        "Cannot determine EnumMap key class for empty map");
            }
        };
        json.setSerializer(EnumMap.class, enumMapSerializer);
        // Save compatibility: skip JSON fields the model no longer declares (e.g. the
        // removed gem-size field) instead of throwing and aborting the whole load.
        json.setIgnoreUnknownFields(true);
        // Tolerate gem species that no longer exist - the RL-47 gem roster changed, so an
        // older save's removed species deserialises to null (a harmless non-gem) rather
        // than throwing a SerializationException that fails the entire load.
        json.setSerializer(com.bjsp123.rl2.model.GemSpecies.class,
                new Json.Serializer<com.bjsp123.rl2.model.GemSpecies>() {
            @Override
            public void write(Json j, com.bjsp123.rl2.model.GemSpecies g, Class knownType) {
                j.writeValue(g == null ? null : g.name());
            }
            @Override
            public com.bjsp123.rl2.model.GemSpecies read(Json j, JsonValue value, Class type) {
                if (value == null || value.isNull()) return null;
                try { return com.bjsp123.rl2.model.GemSpecies.valueOf(value.asString()); }
                catch (Exception e) { return null; }
            }
        });
        return json;
    }

    private static String worldKey(int slot) { return "rl2-save-"  + slot; }
    private static String metaKey(int slot)  { return "rl2-meta-"  + slot; }

    public boolean exists(int slot) { return persistence.exists(worldKey(slot)); }

    public boolean anyExists() {
        for (int i = 0; i < SLOTS; i++) if (exists(i)) return true;
        return false;
    }

    public int firstEmpty() {
        for (int i = 0; i < SLOTS; i++) if (!exists(i)) return i;
        return -1;
    }

    public void save(int slot, World world) {
        persistence.save(worldKey(slot), json.toJson(world));
        persistence.save(metaKey(slot),  json.toJson(metaOf(world)));
    }

    public World load(int slot) {
        lastLoadError = null;
        String raw = persistence.load(worldKey(slot));
        if (raw == null || raw.isEmpty()) { lastLoadError = "save slot is empty"; return null; }
        raw = migrateLegacy(raw);
        try {
            World w = json.fromJson(World.class, raw);
            if (w == null || w.levels == null) { lastLoadError = "save has no level data"; return null; }
            for (Level l : w.levels) if (l != null) l.initTransients();
            w.linkLevels();
            return w;
        } catch (Exception ex) {
            // Capture + log so a broken Resume can be diagnosed instead of
            // silently doing nothing; the save screen shows this summary.
            lastLoadError = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            Gdx.app.error("SaveSystem", "load slot " + slot + " failed", ex);
            return null;
        }
    }

    /** Reason the most recent {@link #load} returned null, or {@code null} if the
     *  last load succeeded. */
    public String lastLoadError() { return lastLoadError; }

    /** Rewrite save JSON written by older builds so it still deserialises.
     *  Immutable collections (from {@code List.of(...)} / {@code Stream.toList()})
     *  were serialised with their concrete class - which libGDX can't
     *  instantiate on load (no no-arg constructor). Retag them as the mutable
     *  equivalent so the {@code items:[...]} array reads into an ArrayList.
     *  Cheap string pass; the class names never occur as save data values. */
    private static String migrateLegacy(String raw) {
        if (raw.indexOf("java.util.ImmutableCollections$") < 0) return raw;
        return raw
                .replace("java.util.ImmutableCollections$ListN",  "java.util.ArrayList")
                .replace("java.util.ImmutableCollections$List12", "java.util.ArrayList")
                .replace("java.util.ImmutableCollections$SetN",   "java.util.HashSet")
                .replace("java.util.ImmutableCollections$Set12",  "java.util.HashSet")
                .replace("java.util.ImmutableCollections$MapN",   "java.util.HashMap")
                .replace("java.util.ImmutableCollections$Map1",   "java.util.HashMap");
    }

    public SaveMetadata metadata(int slot) {
        String raw = persistence.load(metaKey(slot));
        if (raw == null || raw.isEmpty()) return null;
        try { return json.fromJson(SaveMetadata.class, raw); }
        catch (Exception ex) { return null; }
    }

    public void clear(int slot) {
        persistence.delete(worldKey(slot));
        persistence.delete(metaKey(slot));
    }

    private SaveMetadata metaOf(World world) {
        SaveMetadata m = new SaveMetadata();
        Mob player = TurnSystem.findPlayer(world.currentLevel());
        if (player != null) {
            m.charClass      = player.characterClass != null
                    ? player.characterClass.displayName()
                    : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
            m.characterLevel = player.characterLevel;
            m.score          = player.score;
            m.hp             = (int) Math.round(player.hp);
            m.maxHp          = (int) Math.round(player.effectiveStats().maxHp);
        }
        m.depth           = world.currentLevel().depth;
        m.timestampMillis = System.currentTimeMillis();
        m.version         = com.bjsp123.rl2.util.AppVersion.VERSION;
        m.build           = com.bjsp123.rl2.util.AppVersion.BUILD;
        return m;
    }

    /** Lightweight summary of a saved game, persisted alongside each slot for
     *  quick listing. Nested in its sole producer {@link SaveSystem}; external
     *  readers import {@code SaveSystem.SaveMetadata}. */
    public static class SaveMetadata {
        public String charClass = "";
        public int    characterLevel;
        public int    depth;
        public int    score;
        public int    hp;
        public int    maxHp;
        public long   timestampMillis;
        /** App version that wrote this save (e.g. "0.1"). Empty on legacy/unstamped
         *  saves, which the loader then treats as incompatible. */
        public String version = "";
        /** App build that wrote this save. {@code -1} on legacy/unstamped saves. */
        public int    build   = -1;
    }
}
