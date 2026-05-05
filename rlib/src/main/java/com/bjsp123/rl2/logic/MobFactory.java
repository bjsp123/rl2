package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Point;

/**
 * Factory for spawning {@link Mob} instances. Both NPCs and players are data-
 * driven — every row in {@code assets/data/mobs.csv} produces a
 * {@link MobDefinition} in {@link MobRegistry}; {@link #spawn(String, Point)}
 * looks up the definition and applies it. The three {@code PLAYER_WARRIOR} /
 * {@code PLAYER_ROGUE} / {@code PLAYER_MAGE} rows hold the per-class kits;
 * {@link #player(Point, CharacterClass)} picks the right one and stamps on the
 * engine-reserved {@link Mob#TYPE_PLAYER} marker plus the dynamic hostility set.
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
        // The CSV's row key is "PLAYER_WARRIOR" etc., but mobs reference the
        // canonical "PLAYER" string in their attackTypes — pin it on the player
        // mob so faction lookups match.
        m.mobType        = Mob.TYPE_PLAYER;
        m.characterClass = cls;
        seedPlayerHostility(m);
        return m;
    }

    /** Walk every mob type in the {@link MobRegistry} and copy any species that
     *  lists PLAYER in its default {@link Mob#attackTypes} into the new player's
     *  own attackTypes. Other player rows are skipped. New hostile species
     *  added later are picked up automatically by the loop. */
    private static void seedPlayerHostility(Mob player) {
        Point dummy = new Point(0, 0);
        for (String type : MobRegistry.knownTypes()) {
            MobDefinition def = MobRegistry.get(type);
            if (def == null || def.behavior == Behavior.PLAYER) continue;
            Mob template = spawn(type, dummy);
            if (template == null) continue;
            // Hostile species: anything that lists PLAYER as an attack target.
            if (template.attackTypes != null && template.attackTypes.contains(Mob.TYPE_PLAYER)) {
                player.attackTypes.add(type);
            }
            // Inanimate spawners (ant hills, …) — flagged hostile so the player can bump-
            // attack the structure even though it never attacks back. Read off the
            // turn-spawn flag rather than enumerating species so future spawners get
            // picked up automatically.
            if (template.behavior == Behavior.INANIMATE
                    && template.turnSpawnType != null) {
                player.attackTypes.add(type);
            }
        }
    }

    /** Backwards-compatible default — Warrior. */
    public static Mob player(Point pos) {
        return player(pos, CharacterClass.WARRIOR);
    }

    /** Build a fresh mob of {@code type} at {@code pos}. Looks up the species
     *  in {@link MobRegistry} and applies the row's data onto a new {@link Mob}.
     *  Returns {@code null} for the player ({@link Mob#TYPE_PLAYER}, which has
     *  its own constructor with a class argument), for any {@code PLAYER_*} kit
     *  row (those are reached only via {@link #player}), or for any unknown
     *  type. */
    public static Mob spawn(String type, Point pos) {
        if (type == null) return null;
        if (Mob.TYPE_PLAYER.equals(type)) return null;
        MobDefinition def = MobRegistry.get(type);
        if (def == null) return null;
        if (def.behavior == Behavior.PLAYER) return null;
        Mob m = new Mob();
        def.apply(m, pos);
        return m;
    }
}
