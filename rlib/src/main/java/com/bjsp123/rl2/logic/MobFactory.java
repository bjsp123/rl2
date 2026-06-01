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
     *  only via {@link #player}) or for any unknown type.
     *
     *  <p>{@code ENEMY_PLAYER_*} rows get an automatic {@code characterClass}
     *  derived from the row-key suffix - the renderer uses that to pick the
     *  right per-class sprite out of {@code sprites/player.png} (ghost
     *  variant column). Without this assignment the renderer would fall back
     *  to the type-keyed mobs.png lookup and miss. */
    public static Mob spawn(String type, Point pos) {
        if (type == null) return null;
        MobDefinition def = Registries.mob(type);
        if (def == null) return null;
        if (def.behavior == Behavior.PLAYER) return null;
        Mob m = new Mob();
        def.apply(m, pos);
        if (type.startsWith("ENEMY_PLAYER_")) {
            String suffix = type.substring("ENEMY_PLAYER_".length());
            try {
                m.characterClass = CharacterClass.valueOf(suffix);
            } catch (IllegalArgumentException ignored) { /* unknown class */ }
        }
        return m;
    }

    /**
     * Equip a mob with a full level-appropriate kit: weapon + armor + amulet +
     * one damage wand + two damage bombs. Used by any mobs.csv row that lists
     * {@code LEVEL_APPROPRIATE} in its starting inventory (currently the
     * {@code ENEMY_PLAYER_*} family). Slot-bearing items auto-equip; bombs
     * land in the bag. Rolls are bounded so a theme with no eligible item in
     * a given category just leaves that slot empty rather than spinning
     * forever.
     *
     * <p>The mob's character level is also raised to {@code characterLevel}
     * and its perk points auto-spent, mirroring the legacy mirror-encounter
     * pipeline so the kit reads as the depth-N version of the kit it carries.
     */
    public static void equipLevelAppropriateKit(Mob mob, double powerLevel,
                                                Level.VisualTheme theme,
                                                int characterLevel, Random rng) {
        if (mob == null || mob.inventory == null) return;
        addBySlot(mob, Item.InventoryCategory.WEAPON,
                ItemGenerator.LootCategory.EQUIPMENT, powerLevel, theme, rng);
        addBySlot(mob, Item.InventoryCategory.ARMOR,
                ItemGenerator.LootCategory.EQUIPMENT, powerLevel, theme, rng);
        addBySlot(mob, Item.InventoryCategory.AMULET,
                ItemGenerator.LootCategory.MAGIC_ITEMS, powerLevel, theme, rng);
        Item wand = rollDamageWand(powerLevel, theme, rng);
        if (wand != null) {
            InventorySystem.addToBag(mob.inventory, wand);
            // Wands aren't equipped to a body slot - they live in the bag /
            // action bar - so addToBag is the whole story here.
        }
        for (int i = 0; i < 2; i++) {
            Item bomb = rollDamageBomb(powerLevel, theme, rng);
            if (bomb != null) InventorySystem.addToBag(mob.inventory, bomb);
        }
        MobProgression.setSpawnLevel(mob, characterLevel);
        MobProgression.autoLevelUpPerks(mob, rng);
    }

    /** Roll one item out of the requested loot category, retry a few times
     *  for one whose {@link Item#inventoryCategory} matches the requested
     *  body slot, then add + auto-equip. Silent no-op if no match in
     *  {@value #SLOT_ROLL_RETRIES} tries (theme lacks any item of that
     *  slot). */
    private static void addBySlot(Mob mob,
                                  Item.InventoryCategory targetSlot,
                                  ItemGenerator.LootCategory pool,
                                  double powerLevel,
                                  Level.VisualTheme theme,
                                  Random rng) {
        for (int tries = 0; tries < SLOT_ROLL_RETRIES; tries++) {
            Item it = ItemGenerator.generateItem(powerLevel, theme, pool, rng);
            if (it == null) return;
            if (it.inventoryCategory == targetSlot) {
                InventorySystem.addToBag(mob.inventory, it);
                if (it.isEquippable()) InventorySystem.equip(mob.inventory, it);
                return;
            }
        }
    }

    private static final int SLOT_ROLL_RETRIES = 8;

    /** Roll until we get a wand that actually deals damage (some wand types
     *  - polymorph, banishment, teleport - have zero damage). Bounded by
     *  {@value #SLOT_ROLL_RETRIES}. Returns null when nothing eligible
     *  surfaces in time. */
    private static Item rollDamageWand(double powerLevel, Level.VisualTheme theme, Random rng) {
        for (int tries = 0; tries < SLOT_ROLL_RETRIES; tries++) {
            Item it = ItemGenerator.generateItem(powerLevel, theme,
                    ItemGenerator.LootCategory.MAGIC_ITEMS, rng);
            if (it == null) return null;
            if (it.inventoryCategory == Item.InventoryCategory.WAND
                    && it.damage > 0) {
                return it;
            }
        }
        return null;
    }

    /** Roll until we get a bomb that actually deals damage (some bomb types
     *  - oil bomb, water bomb - lay down a surface but do no direct damage).
     *  Bounded by {@value #SLOT_ROLL_RETRIES}. */
    private static Item rollDamageBomb(double powerLevel, Level.VisualTheme theme, Random rng) {
        for (int tries = 0; tries < SLOT_ROLL_RETRIES; tries++) {
            Item it = ItemGenerator.generateItem(powerLevel, theme,
                    ItemGenerator.LootCategory.BOMBS, rng);
            if (it == null) return null;
            if (it.damage > 0) return it;
        }
        return null;
    }
}
