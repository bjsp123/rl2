package com.bjsp123.rl2.model;

public class Item {
    /**
     * What an item does when thrown or when a wand fires. Shared by both
     * {@link #throwEffect} and {@link #wandEffect} so values like {@code FIRE}
     * mean the same thing whether the source is a fire bomb or a fire wand.
     *
     * <p>Null on either field means "no special effect" — thrown items with a
     * null {@code throwEffect} just land on the floor; summon-style wands carry
     * a null {@code wandEffect} and route through {@link #summonsWhenUsed}
     * instead.
     */
    public enum ItemEffect {
        /** Physical projectile: deals damage using the item's damage range. */
        DAMAGE,
        /** Fire / ignition: sets the impact area on fire. */
        FIRE,
        /** Oil splash: coats the impact area with oil. */
        OIL,
        /** Concussive blast: radial damage to all mobs in the blast disc. */
        BLAST,
        /** Freeze: applies CHILLED to mobs in the effect disc; douses fires. */
        FREEZE,
        /** Water (wand): pools water on the target tile. */
        WATER,
        /** Vegetation (wand): grows grass and trees at the target. */
        GRASS,
        /** Fungus (wand): coaxes mushrooms up from the target. */
        FUNGUS,
        /** Detonation (wand): ignites a radial disc with a fireball visual. */
        DETONATION,
        /** Magic missile (wand): single-target direct damage. */
        MISSILE,
        /** Banishment (wand): ray that destroys ghosts on contact. */
        BANISHMENT,
        /** Lightning (wand): direct damage, doubled on wet / water targets. */
        LIGHTNING
    }

    /**
     * Single source of truth for "what kind of item is this?". Drives equip-slot
     * routing, inventory tab grouping, and item-generation bucketing — formerly
     * spread across {@code slot}, {@code useBehavior}, and {@code thrownBehavior}
     * heuristics. Set per row in {@code items.csv}.
     *
     * <p>Equipment categories ({@link #WEAPON}, {@link #OFFHAND}, {@link #ARMOR},
     * {@link #AMULET}, {@link #GEM}) are the ones {@link Item#isEquippable} returns
     * true for. {@code AMULET} items fill one of two amulet positions in the
     * {@link Inventory}; {@code GEM} items fill one of three gem positions; the
     * single-position equipment categories occupy their named field directly.
     *
     * <p>Non-equipment categories ({@link #POTION}, {@link #WAND}, {@link #FOOD},
     * {@link #ORB}, {@link #BOMB}) live in the bag only; their behavior is driven
     * by {@code useBehavior} (potions, food, orbs, wands) and {@code throwEffect} /
     * {@code wandEffect} ({@link ItemEffect}) as orthogonal sub-axes.
     */
    public enum InventoryCategory {
        WEAPON, OFFHAND, ARMOR, AMULET, GEM,
        POTION, WAND, FOOD, ORB, BOMB;

        /** True if items in this category go into a gear strip equipment slot. */
        public boolean isEquipment() {
            return this == WEAPON || this == OFFHAND || this == ARMOR
                    || this == AMULET || this == GEM;
        }
    }

    /** What the "Use" action does for an item. Non-usable items get UI disabled in the menu. */
    public enum UseBehavior {
        /** No use action; the "Use" button is grayed out. */
        NONE,
        /** Consume the item to raise the user's satiety by {@code foodValue}. */
        EAT,
        /** Element wand: fires a projectile that applies {@link Item#wandEffect} on
         *  impact. Summon-style wands (non-null {@link Item#summonsWhenUsed}) bypass
         *  targeting and spawn the named mob on a free adjacent tile. */
        WAND,
        /** Drink a buff-bestowing potion. The potion's effect is dispatched on
         *  {@link Item#type} and applied via {@code BuffSystem}; level and duration
         *  default to the item's level. */
        DRINK,
        /** Consume the item to grant the user a perk point. Used by power orbs. */
        GRANT_PERK
    }

    /** Identifier — string key matching the {@code type} column of a row in
     *  {@code assets/data/items.csv}. Null for procedurally-generated items
     *  (gems) that aren't catalogued in the CSV. Used as the registry lookup
     *  key and as the stack-merge identity. */
    public String type;
    public Mob.Material material;
    public String name;
    /** Free-form flavor text / description shown in the item detail dialog. */
    public String description = "";
    public MinMax damage = MinMax.ZERO;
    /** Per-level damage range increment. Each {@code +N} on this item adds
     *  {@code N * damagePerLevel} to {@link #damage}. Used by weapons (melee
     *  + thrown), wands that deal direct damage (MISSILE, LIGHTNING), and
     *  bombs whose impact deals direct damage. Zero for items that don't
     *  scale damage per level. */
    public MinMax damagePerLevel = MinMax.ZERO;
    public MinMax armor  = MinMax.ZERO;
    /** Per-level armor range increment. Mirrors {@link #damagePerLevel} but
     *  for {@link #armor}. */
    public MinMax armorPerLevel  = MinMax.ZERO;
    public double lightRadius;
    /** Number of tiles affected on use, for wands and bombs with an
     *  area-of-effect impact (water, oil, grass, fungus, fire, detonation,
     *  bombs). Element wands convert this through
     *  {@code MobSystem.radiusForTileCount} into a Euclidean disc radius;
     *  bombs use it directly. Zero for items without an AOE component. */
    public int tilesAffected;
    /** Per-level increment to {@link #tilesAffected}. */
    public int tilesAffectedPerLevel;
    /** Satiety restored when the item is eaten. Zero for non-food. */
    public int foodValue;
    public Point location; // null when in an inventory
    /** What happens when this item is thrown; null means it just lands on the floor. */
    public ItemEffect throwEffect;
    public UseBehavior useBehavior = UseBehavior.NONE;
    /** Element a {@link UseBehavior#WAND} use applies on impact. Null for summon-style wands. */
    public ItemEffect wandEffect;
    /** Verb the UI shows on the Use button and in event-log messages for this item's use
     *  action — "eat" for a pear, "zap" for a staff, "drink" for a potion, etc. Null/empty
     *  for items with no use action. */
    public String useVerb;

    /** Power level of this specific instance. {@code 0} is baseline (no bonus); each
     *  level above adds a fixed increment from {@link com.bjsp123.rl2.logic.GameBalance}
     *  — wider area, more damage, larger heal, etc. Player starter items are always
     *  level 0; dungeon-generated items roll a random level in {@code [0, depth]}.
     *  Food is always level 0. Display: when level > 0, the renderer paints "+N" on
     *  the top-right of the floor sprite. */
    public int level = 0;

    /** Mob types this item tames when thrown at one — sets {@link Mob#owner} to the
     *  thrower. Empty for items that don't tame. The delicious fish lists
     *  {@code CAT|DOG}; future taming items just declare their own list. */
    public java.util.List<String> tameOnThrow = new java.util.ArrayList<>();

    /** Mob type to summon when this item is used. Non-null for the wand of dog
     *  (summons {@code DOG}); null for items that don't summon. The summon is
     *  scaled to the item's level via {@code MobProgression.setSpawnLevel}. */
    public String summonsWhenUsed;

    /** Buff to apply to the user when this item is eaten or drunk. Null for items
     *  with no buff effect. Generalises the legacy per-pear / per-potion switches
     *  in {@code ItemSystem.eat} and {@code ItemSystem.drinkPotion}. */
    public Buff.BuffType appliesBuff;

    /** Base buff duration in turns at item-level 0. Effective duration scales with
     *  item level via {@code (1 + item.level) * buffDuration}. Ignored when
     *  {@link #appliesBuff} is null. */
    public int buffDuration;

    /** Squares to knock the target back on a successful melee hit. 0 = no knockback. */
    public int knockbackSquares;

    /** When true, an item lying on the floor emits an attention-catching twinkle
     *  particle stream so the player notices it. Power orbs use it; future
     *  glowing items just set the flag. */
    public boolean glows;

    /** Category for which this item is the "empty-slot silhouette" art shown in
     *  the inventory's gear strip. Set per item in {@code items.csv} via the
     *  {@code silhouetteForCategory} column; null for items that aren't a
     *  placeholder. */
    public InventoryCategory silhouetteForCategory;

    /** Authoritative item-kind tag — see {@link InventoryCategory}. Drives
     *  equip routing, inventory tab grouping, and {@code ItemGenerator}
     *  bucketing. Null only for procedural items (gems) which set the
     *  category in {@code GemSystem.createGem}. */
    public InventoryCategory inventoryCategory;

    /** Gem species — non-null iff this item is a gem. Drives icon colour, theme
     *  shape (triangle for crystal, square for concrete), and same-kind recipe matching.
     *  {@link #isGem()} reads off this field. */
    public GemSpecies gemSpecies;
    /** Gem size 1–9 (tiny, small, medium, large, fine, impressive, mighty, sublime,
     *  exquisite). Combining two gems of the same {@link #gemSpecies} and matching size
     *  yields one gem of the next size up. Ignored for non-gems. */
    public int gemSize;

    /** Count of identical items represented by this entry. {@code 1} for a singleton.
     *  Inventory operations merge new items into the existing stack via
     *  {@link com.bjsp123.rl2.model.Inventory#addToBag}; consumption helpers decrement
     *  the count and only drop the entry once it hits 0. Equipped items are always
     *  count 1 — equipping pulls one out of a bag stack. */
    public int count = 1;

    public boolean isGem() { return gemSpecies != null; }
    public boolean isEquippable() {
        return inventoryCategory != null && inventoryCategory.isEquipment();
    }
    public boolean isUsable()     { return useBehavior != null && useBehavior != UseBehavior.NONE; }

    /** True if {@code other} is "exactly identical" for stacking purposes — same type,
     *  same level, same gem species + size, same material. Equipped status is not
     *  consulted; callers ensure they're stacking on bag items. */
    public boolean matchesStackKey(Item other) {
        if (other == null || other == this) return false;
        if (!java.util.Objects.equals(type, other.type)) return false;
        if (level != other.level) return false;
        if (gemSpecies != other.gemSpecies) return false;
        if (gemSize != other.gemSize) return false;
        if (material != other.material) return false;
        return true;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(name);
        if (level > 1)       sb.append(" +").append(level - 1);
        if (damage.max() > 0) sb.append(" (").append(damage.min()).append("-").append(damage.max()).append(" dmg)");
        if (armor.max()  > 0) sb.append(" (").append(armor.min()).append("-").append(armor.max()).append(" armor)");
        if (lightRadius > 0) sb.append(" (light ").append((int) lightRadius).append(")");
        return sb.toString();
    }
}
