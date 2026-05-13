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
        LIGHTNING,
        /** Apply every buff in {@link Item#appliesBuff} to mobs in a
         *  Chebyshev radius 1 disc around the impact tile. */
        APPLYBUFFS,
        /** Drop a poison cloud covering {@link Item#tilesAffected} tiles
         *  around the impact (Euclidean disc). Cloud lifetime in turns
         *  is taken from {@link Item#abilityPower}. */
        POISONCLOUD,
        /** Tear a void at the impact: pulls every mob within
         *  {@code (effectiveLevel / 2) + 1} tiles (Chebyshev) toward
         *  the centre, and converts floor-like tiles + small statues
         *  inside the disc to {@link com.bjsp123.rl2.model.Tile#CHASM}.
         *  Pulled mobs play the standard knockback-slide visual. */
        VOID,
        /** Reshape an area: every floor-like tile in the disc has a
         *  50% chance to reroll to one of {FLOOR, FLOOR_WOOD,
         *  FLOOR_SPECIAL, CHASM}, and every non-unique mob in the disc
         *  is replaced by a random species whose intrinsic size is
         *  within ±1 of the original's. */
        POLYMORPH,
        /** {@link UseBehavior#POWERUP} sub-effect: gain one character
         *  level when picked up. */
        LEVEL_UP,
        /** {@link UseBehavior#POWERUP} sub-effect: restore
         *  {@code maxHP * abilityPower} HP when picked up. */
        HP_UP,
        /** {@link UseBehavior#POWERUP} sub-effect: every wand-charging
         *  item in the player's inventory gains its own
         *  {@link Item#chargeGain} of charge (capped at max charge). */
        MANA_UP,
        /** Wand effect: teleport the caster to the target tile instantly. */
        TELEPORT
    }

    /** What happens to a thrown item AFTER its throw-effect resolves.
     *  Read from the {@code throwResult} CSV column; blank cells parse
     *  to {@link #NOTHING}. */
    public enum ThrowResult {
        /** Item lies on the ground at the impact tile (the historical
         *  default for non-bomb throws). */
        NOTHING,
        /** Item ceases to exist (bombs, potions that shatter on impact). */
        CONSUME,
        /** Item bounces back to the thrower and lands on a tile adjacent
         *  to them, ready to be picked up. */
        RETURN
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
        WEAPON, OFFHAND, ARMOR, AMULET, GEM, TOOL,
        POTION, WAND, FOOD, ORB, BOMB,
       
        ITEM;

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
        GRANT_PERK,
        /** Throw a grappling rope — pick a target tile, then yank everything
         *  on it (mob and any floor items) onto the nearest non-wall tile
         *  adjacent to the user. Mobs whose {@code size} exceeds the item's
         *  {@link Item#abilityPower} (overloaded as the max-size cap for
         *  GRAPPLE items) are too heavy: the rope flashes and fades without
         *  pulling anything. Items that land on a chasm fall in. */
        GRAPPLE,
        /** Quick-jump tool — picks a target tile within
         *  {@link Item#abilityPower} squares (Chebyshev) of the user and
         *  teleports them there for one {@code moveCost}. */
        JUMP,
        /** Pickup-trigger consumable: the moment the player walks onto
         *  the item's tile it's destroyed and its {@link Item#wandEffect}
         *  is applied to the player. Effects: {@code LEVEL_UP} grants one
         *  character level; {@code HP_UP} restores
         *  {@code maxHP * abilityPower} HP; {@code MANA_UP} adds each
         *  inventory item's own {@code chargeGain} to its current
         *  charge. POWERUP items are never picked up into the bag. */
        POWERUP
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
    /** Optional secondary flavor paragraph — rendered immediately below
     *  {@link #description} in the encyclopedia entry, separated by a
     *  blank line. Use it for in-fiction lore or quotes that don't fit
     *  on the inventory popup; the look popup deliberately ignores this
     *  to keep its silhouette compact. Empty when the CSV cell is blank. */
    public String description2 = "";
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
    /** Armor-piercing damage range — bypasses the target's armor on a hit.
     *  Composed by sum into the wielder's {@code apDamage} stat in
     *  {@link com.bjsp123.rl2.logic.ItemSystem#contributeInto}. */
    public MinMax apDamage = MinMax.ZERO;
    /** Per-level increment to {@link #apDamage}. */
    public MinMax apDamagePerLevel = MinMax.ZERO;
    /** Magic-resistance range — reduces magical / energy damage taken when
     *  the item is equipped. Read from the CSV {@code antiMagic} column.
     *  Composed by sum into the wielder's {@code magicResist} stat. */
    public MinMax magicResist = MinMax.ZERO;
    /** Per-level increment to {@link #magicResist}. */
    public MinMax magicResistPerLevel = MinMax.ZERO;
    /** Bonus to the wielder's {@code accuracy}. Composed by sum. */
    public int accuracy;
    /** Bonus to the wielder's {@code evasion}. Composed by sum. */
    public int evasion;
    /** Multiplier on the wielder's base attack cost — values below 1.0 mean
     *  faster attacks, above 1.0 mean slower. {@link #ATTACK_SPEED_DEFAULT}
     *  (= 1.0) is "no change". Read from the CSV {@code attackSpeed} column.
     *  Wired into the wielder's {@code attackCost} via
     *  {@link com.bjsp123.rl2.logic.ItemSystem#contributeInto}. */
    public double attackSpeed = ATTACK_SPEED_DEFAULT;
    /** Multiplier on the wielder's base move cost. Mirrors
     *  {@link #attackSpeed} for movement; CSV column is {@code moveSpeed}. */
    public double moveSpeed = MOVE_SPEED_DEFAULT;
    /** Identity values for the speed multipliers — items without a speed
     *  modifier carry these so {@link com.bjsp123.rl2.logic.ItemSystem#contributeInto}
     *  contributes zero delta, and the lore renderer can suppress the row. */
    public static final double ATTACK_SPEED_DEFAULT = 1.0;
    public static final double MOVE_SPEED_DEFAULT   = 1.0;
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
    /** What happens to this item after its throw-effect resolves. See
     *  {@link ThrowResult}. Defaults to {@link ThrowResult#NOTHING}. */
    public ThrowResult throwResult = ThrowResult.NOTHING;
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

    /** Buffs to apply to the user / target when this item is consumed or
     *  thrown with the {@link ItemEffect#APPLYBUFFS} throw-effect. Empty
     *  for items with no buff component. The CSV column accepts a single
     *  buff name OR a pipe-separated list ({@code POISONED|CHILLED}); the
     *  list semantics let an APPLYBUFFS thrown item layer multiple buffs
     *  in one impact. */
    public java.util.List<Buff.BuffType> appliesBuff = new java.util.ArrayList<>();

    /** Convenience — first entry of {@link #appliesBuff} or {@code null}.
     *  Used by call sites that historically treated the field as a single
     *  buff (animator tinting, eat / drink hooks). */
    public Buff.BuffType primaryBuff() {
        return appliesBuff.isEmpty() ? null : appliesBuff.get(0);
    }

    /** Generic "magnitude" knob — overloaded by useBehavior:
     *  <ul>
     *    <li>{@code APPLYBUFFS} / {@code DRINK} / {@code EAT}: base buff
     *        duration in turns at item-level 0; effective duration scales
     *        with item level via {@code (1 + item.level) * abilityPower}.</li>
     *    <li>{@code POISONCLOUD}: cloud lifetime in turns at level 0.</li>
     *    <li>{@code GRAPPLE}: max grappable target size cap.</li>
     *    <li>{@code JUMP}: jump radius in squares.</li>
     *    <li>{@code POWERUP}+{@code HP_UP}: fraction of max HP healed
     *        on pickup (0.5 = half max HP).</li>
     *  </ul>
     *  Stored as a float so fractional powers (HP_UP, future scalars)
     *  fit cleanly; integer call sites cast as needed. */
    public float abilityPower;

    /** Wand-only: current charge level. Each {@link UseBehavior#WAND} fire
     *  consumes 1 charge; the wand refuses to fire when {@code < 1}.
     *  Stored as float so {@link #chargeGain} can drip charge in fractional
     *  increments (e.g. mana pickups). Capped at {@link #maxCharge()}. */
    public float charge;

    /** Wand-only: charge regenerated per game-tick (or per
     *  {@code MANA_UP} pickup application). Read from the {@code chargeGain}
     *  CSV column. Default 0 (no regen). */
    public float chargeGain;

    /** Base maximum charges at item level 0. Each level beyond the base
     *  adds 1 max charge. 0 means the item has no charges (default). */
    public int baseChargeMax = 0;

    /** Intrinsic maximum charge — {@code baseChargeMax + level}. Use
     *  {@code ItemSystem.effectiveMaxCharge(item, holder)} when the holder
     *  is available so WANDMASTER / BOMB_JACK bonuses apply. */
    public int maxCharge() { return Math.max(1, baseChargeMax + level); }

    /** Squares to knock the target back on a successful melee hit. 0 = no knockback. */
    public int knockbackSquares;

    /** When true, an item lying on the floor emits an attention-catching twinkle
     *  particle stream so the player notices it. Power orbs use it; future
     *  glowing items just set the flag. */
    public boolean glows;

    /** Brand applied to this item at generation time, or {@code null} for unbranded items.
     *  Brands contribute stat bonuses via
     *  {@link com.bjsp123.rl2.logic.ItemSystem#contributeInto} and add an
     *  "of [name]" suffix to the display name. */
    public com.bjsp123.rl2.logic.BrandDefinition brand;

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

    /** True only for categories that may form stacks: potions, bombs, and food.
     *  All other item types (weapons, wands, gems, tools, …) are always singletons. */
    public boolean isStackable() {
        return inventoryCategory == InventoryCategory.POTION
                || inventoryCategory == InventoryCategory.BOMB
                || inventoryCategory == InventoryCategory.FOOD;
    }

    /** True if {@code other} is "exactly identical" for stacking purposes — same type,
     *  both stackable. Bombs ignore level so same-type bombs from different depths
     *  merge freely; potions and food still require matching levels. */
    public boolean matchesStackKey(Item other) {
        if (other == null || other == this) return false;
        if (!isStackable() || !other.isStackable()) return false;
        if (!java.util.Objects.equals(type, other.type)) return false;
        if (level != other.level) return false;
        return true;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(name);
        if (level > 0)        sb.append(" +").append(level);
        if (damage.max() > 0) sb.append(" (").append(damage.min()).append("-").append(damage.max()).append(" dmg)");
        if (armor.max()  > 0) sb.append(" (").append(armor.min()).append("-").append(armor.max()).append(" armor)");
        if (lightRadius > 0) sb.append(" (light ").append((int) lightRadius).append(")");
        return sb.toString();
    }
}
