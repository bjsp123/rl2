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

    private final Persistence persistence;
    private final Json json;

    public SaveSystem(Persistence persistence) {
        this.persistence = persistence;
        this.json = new Json();
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
        @SuppressWarnings({"rawtypes", "unchecked"})
        Json.Serializer<EnumMap> enumMapSerializer = new Json.Serializer<EnumMap>() {
            @Override
            public void write(Json json, EnumMap m, Class knownType) {
                Class<? extends Enum> keyClass = keyClassOf(m);
                json.writeObjectStart();
                json.writeValue("keyType", keyClass.getName());
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
                Class<? extends Enum> keyClass;
                try {
                    keyClass = (Class<? extends Enum>)
                            Class.forName(value.getString("keyType"));
                } catch (ClassNotFoundException ex) {
                    throw new com.badlogic.gdx.utils.SerializationException(
                            "Unknown EnumMap keyType", ex);
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
                // Empty maps still need to round-trip - get the class via reflection
                // on EnumMap's private keyType field. Falls back to inspecting the
                // first entry if the JVM blocks reflective access (unlikely on the
                // default --add-opens-free runtime but defensive).
                try {
                    java.lang.reflect.Field f = EnumMap.class.getDeclaredField("keyType");
                    f.setAccessible(true);
                    return (Class<? extends Enum>) f.get(m);
                } catch (Exception ignored) {
                    for (Object o : m.keySet()) {
                        return ((Enum) o).getDeclaringClass();
                    }
                    throw new com.badlogic.gdx.utils.SerializationException(
                            "Cannot determine EnumMap key class for empty map");
                }
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
        String raw = persistence.load(worldKey(slot));
        if (raw == null || raw.isEmpty()) return null;
        try {
            World w = json.fromJson(World.class, raw);
            if (w == null || w.levels == null) return null;
            for (Level l : w.levels) if (l != null) l.initTransients();
            w.linkLevels();
            return w;
        } catch (Exception ex) {
            // Log so a broken Resume can be diagnosed instead of silently doing nothing.
            Gdx.app.error("SaveSystem", "load slot " + slot + " failed", ex);
            return null;
        }
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
        return m;
    }
}
