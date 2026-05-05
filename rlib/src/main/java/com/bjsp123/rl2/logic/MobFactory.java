package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Point;

/**
 * Factory for spawning {@link Mob} instances. Both NPCs and players are data-
 * driven — every row in {@code assets/data/mobs.csv} produces a
 * {@link MobDefinition} in {@link MobRegistry}; {@link #spawn(String, Point)}
 * looks up the definition and applies it. The {@code PLAYER_*} rows hold the
 * per-class kits; {@link #player(Point, CharacterClass)} picks the right one
 * and seeds the dynamic hostility set.
 */
public class MobFactory {

    private MobFactory() {}

    public static Mob player(Point pos, CharacterClass cls) {
        String key = "PLAYER_" + cls.name();
        MobDefinition def = MobRegistry.get(key);
        if (def == null) {
            throw new IllegalStateException("missing mobs.csv row: " + key);
        }
        Mob m = new Mob();
        def.apply(m, pos);
        // mobType keeps the row key. The "is this the player?" question is
        // answered by behavior == PLAYER (or characterClass != null), so no
        // engine-reserved string is needed.
        m.characterClass = cls;
        seedPlayerHostility(m);
        return m;
    }

    /** Walk every mob type in the {@link MobRegistry} and copy any species
     *  whose {@code enemyFactions} includes the player's faction into the
     *  player's own {@link Mob#attackTypes} set. Other player rows are
     *  skipped. */
    private static void seedPlayerHostility(Mob player) {
        if (player.faction == null) return;
        for (String type : MobRegistry.knownTypes()) {
            MobDefinition def = MobRegistry.get(type);
            if (def == null || def.behavior == Behavior.PLAYER) continue;
            if (def.enemyFactions.contains(player.faction)) {
                player.attackTypes.add(type);
            }
        }
    }

    /** Build a fresh mob of {@code type} at {@code pos}. Looks up the species
     *  in {@link MobRegistry} and applies the row's data onto a new {@link Mob}.
     *  Returns {@code null} for any {@code PLAYER_*} kit row (those are reached
     *  only via {@link #player}) or for any unknown type. */
    public static Mob spawn(String type, Point pos) {
        if (type == null) return null;
        MobDefinition def = MobRegistry.get(type);
        if (def == null) return null;
        if (def.behavior == Behavior.PLAYER) return null;
        Mob m = new Mob();
        def.apply(m, pos);
        return m;
    }
}
