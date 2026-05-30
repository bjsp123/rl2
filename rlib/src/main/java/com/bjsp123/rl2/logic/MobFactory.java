package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Point;

import java.util.Random;

/**
 * Factory for spawning {@link Mob} instances. Both NPCs and players are data-
 * driven - every row in {@code assets/data/mobs.csv} produces a
 * {@link MobDefinition} in {@link MobRegistry}; {@link #spawn(String, Point)}
 * looks up the definition and applies it. The {@code PLAYER_*} rows hold the
 * per-class kits; {@link #player(Point, CharacterClass)} picks the right one
 * and seeds the dynamic hostility set.
 */
public class MobFactory {

    private MobFactory() {}

    public static Mob player(Point pos, CharacterClass cls) {
        String key = "PLAYER_" + cls.name();
        MobDefinition def = Registries.mob(key);
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
        for (String type : Registries.mobTypes()) {
            MobDefinition def = Registries.mob(type);
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
        MobDefinition def = Registries.mob(type);
        if (def == null) return null;
        if (def.behavior == Behavior.PLAYER) return null;
        Mob m = new Mob();
        def.apply(m, pos);
        return m;
    }

    /** Build a mirror-match enemy out of a {@code PLAYER_*} kit row: the mob
     *  carries the class's full perk + inventory loadout but its behaviour is
     *  flipped to {@link Behavior#MOB} and its faction shifted to {@code BEACON}
     *  so it (a) reads as a PLAYER-hostile to the real player via
     *  {@code enemyFactions={PLAYER}} and (b) shares the BEACON faction with
     *  the wraiths spawned alongside it, so they treat each other as allies
     *  per the shared-faction rule in {@link MobSystem#getAttitudeToMob}.
     *  {@link Mob#characterClass} is left intact so the renderer keeps using
     *  the per-class player sprite. Used by the beacon-room encounter spawner;
     *  returns {@code null} for unknown / non-player rows. */
    public static Mob spawnMirror(String type, Point pos) {
        if (type == null) return null;
        MobDefinition def = Registries.mob(type);
        if (def == null || def.behavior != Behavior.PLAYER) return null;
        Mob m = new Mob();
        def.apply(m, pos);
        m.behavior = Behavior.MOB;
        m.faction  = "BEACON";
        m.enemyFactions.clear();
        m.enemyFactions.add("PLAYER");
        // {@code def.apply} doesn't propagate the per-class identity onto the
        // Mob - that's normally {@link #player}'s job - so the per-class
        // sprite renderer (MobSprites.regionFor) would miss without this. We
        // derive the class from the row key ("PLAYER_WARRIOR" -> WARRIOR);
        // unknown suffixes leave characterClass null and the renderer falls
        // back to the type-keyed lookup.
        if (type.startsWith("PLAYER_")) {
            String suffix = type.substring("PLAYER_".length());
            try {
                m.characterClass = CharacterClass.valueOf(suffix);
            } catch (IllegalArgumentException ignored) { /* unknown class */ }
        }
        return m;
    }

    /** Hand a mirror player a small randomised depth-appropriate kit on top of
     *  its CSV starting gear: one random equippable (weapon/armor/offhand),
     *  plus class-specific extras (mage = 2 wands, rogue = 2 bombs, warrior =
     *  nothing extra). Also raises the mirror's character level to
     *  {@code characterLevel} and auto-spends its perk points via
     *  {@link MobProgression#autoLevelUpPerks}, so the mirror reads as the
     *  level-{@code D} version of the class for the encounter at dungeon
     *  depth {@code D}. Silent no-op for a null mob. */
    public static void equipMirrorForDepth(Mob mirror, double powerLevel,
                                           Level.VisualTheme theme,
                                           int characterLevel, Random rng) {
        if (mirror == null || mirror.inventory == null) return;
        Item eq = ItemGenerator.generateItem(powerLevel, theme,
                ItemGenerator.LootCategory.EQUIPMENT, rng);
        if (eq != null) {
            InventorySystem.addToBag(mirror.inventory, eq);
            InventorySystem.equip(mirror.inventory, eq);
        }
        if (mirror.characterClass == CharacterClass.MAGE) {
            for (int i = 0; i < 2; i++) {
                Item wand = rollWand(powerLevel, theme, rng);
                if (wand != null) InventorySystem.addToBag(mirror.inventory, wand);
            }
        } else if (mirror.characterClass == CharacterClass.ROGUE) {
            for (int i = 0; i < 2; i++) {
                Item bomb = ItemGenerator.generateItem(powerLevel, theme,
                        ItemGenerator.LootCategory.BOMBS, rng);
                if (bomb != null) InventorySystem.addToBag(mirror.inventory, bomb);
            }
        }
        MobProgression.setSpawnLevel(mirror, characterLevel);
        MobProgression.autoLevelUpPerks(mirror, rng);
    }

    /** Roll until we get a wand from the wand+amulet bucket (no wand-only
     *  category exists in {@link ItemGenerator.LootCategory}). Bounded to 8
     *  retries; a theme with no eligible wand returns null and the caller
     *  silently skips the slot. */
    private static Item rollWand(double powerLevel, Level.VisualTheme theme, Random rng) {
        for (int tries = 0; tries < 8; tries++) {
            Item it = ItemGenerator.generateItem(powerLevel, theme,
                    ItemGenerator.LootCategory.MAGIC_ITEMS, rng);
            if (it == null) return null;
            if (it.inventoryCategory == Item.InventoryCategory.WAND) return it;
        }
        return null;
    }
}
