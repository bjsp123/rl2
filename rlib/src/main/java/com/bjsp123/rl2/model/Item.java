package com.bjsp123.rl2.model;

public class Item {
    /** Distinct kind of item. Mostly informational — code branches on flags rather than this
     *  tag, but renderers and the encyclopedia look it up. */
    public enum ItemType {
        SWORD, DAGGER, SHIELD, SCALE_MAIL, AMULET_OF_LIGHT,
        // Food
        PEAR, PEAR_SCRUMPTIOUS, PEAR_SILVERY, PEAR_CONFERENCE,
        FISH,
        // Bombs
        FIRE_BOMB, OIL_BOMB, BLAST_BOMB, FREEZE_BOMB,
        // Potions
        HEALING_POTION, POTION_SORCERY, POTION_GHOSTLINESS, POTION_INVISIBILITY, POTION_POISON,
        // Wands
        WAND_WATER, WAND_OIL, WAND_GRASS, WAND_FUNGUS, WAND_FIRE, WAND_DOG, WAND_DETONATION,
        WAND_MAGIC_MISSILE, WAND_BANISHMENT,
        // Gems — single ItemType, with species + size carried on the Item instance.
        GEM
    }

    /** Element a wand applies on use. The {@link #DOG_SPAWN} variant is special — instead
     *  of targeting a tile it summons a dog on a free cell next to the user. */
    public enum WandElement {
        WATER, OIL, GRASS, FUNGUS, FIRE, DOG_SPAWN,
        /** Wand of detonation — ignites a Euclidean disc around the impact tile and
         *  spawns a radial particle burst for the visual. Radius scales with item level. */
        DETONATION,
        /** Wand of banishment — fires a ray (new effect type) along the line of sight;
         *  any ghost the ray touches is instantly destroyed. */
        BANISHMENT
    }

    /**
     * Equipment slots. The first six (weapon → offhand → armor → ring1 → ring2 → amulet)
     * make up the gear strip rendered across the top of the inventory; the trailing three
     * gem slots aren't shown there — equipped gems bob around the player's head in the
     * world view instead and live in their own inventory tab.
     *
     * <p>Order matters: callers iterating {@code values()} get the rendered order for the
     * gear strip, then the gem slots appended after. Rendering code that wants
     * "gear-strip slots only" stops at {@link #AMULET}.
     */
    public enum ItemSlot {
        WEAPON, OFFHAND, ARMOR, RING1, RING2, AMULET,
        GEM1, GEM2, GEM3;

        /** True if an item with slot tag {@code itemSlot} can occupy this equipment slot.
         *  Rings fit either ring slot; gems fit any of the three gem slots. */
        public boolean accepts(ItemSlot itemSlot) {
            if (itemSlot == null) return false;
            if (itemSlot == this) return true;
            if (isRing(this) && isRing(itemSlot)) return true;
            if (isGem(this)  && isGem(itemSlot))  return true;
            return false;
        }

        public static boolean isRing(ItemSlot s) { return s == RING1 || s == RING2; }
        public static boolean isGem(ItemSlot s)  { return s == GEM1 || s == GEM2 || s == GEM3; }
    }

    /** What happens when a thrown item lands on a target tile. */
    public enum ThrownBehavior {
        /** Just lands on the floor where thrown; no effect on mobs. */
        NOTHING,
        /** Applies direct damage (using the item's damage range) to any mob on the target square. */
        DAMAGE,
        /** Sets the target tile (and any mob standing on it) on fire. Item is consumed. */
        IGNITE,
        /** Splashes oil onto the target tile multiple times, spreading via {@code SurfaceSystem}. */
        OIL_SPLASH,
        /** Blast bomb — radial damage to every mob inside the blast disc (plus the
         *  target tile itself), with a "blast" particle burst per affected square.
         *  Pushback is documented but not yet implemented. */
        BLAST,
        /** Freeze bomb — damage on the target tile, applies CHILLED to mobs in the
         *  freeze disc, turns water surfaces to ice (TODO: ICE surface type), removes
         *  fire vegetation. */
        FREEZE
    }

    /** What the "Use" action does for an item. Non-usable items get UI disabled in the menu. */
    public enum UseBehavior {
        /** No use action; the "Use" button is grayed out. */
        NONE,
        /** Fires a magic missile at a target square (resolved via the targeting overlay). */
        MAGIC_MISSILE,
        /** Consume the item to raise the user's satiety by {@code foodValue}. */
        EAT,
        /** Restore HP to the user (up to {@link #healAmount}) and consume the item. */
        HEAL,
        /** Element wand: fires a magic-missile-style projectile that, on impact,
         *  applies {@link Item#wandElement} to the target tile (or, for
         *  {@link WandElement#DOG_SPAWN}, summons a dog adjacent to the user). */
        WAND,
        /** Drink a buff-bestowing potion. The potion's effect is dispatched on
         *  {@link Item#type} and applied via {@code BuffSystem}; level and duration
         *  default to the item's level. */
        DRINK
    }

    public ItemType type;
    public ItemSlot slot;
    public Mob.Material material;
    public String name;
    /** Free-form flavor text / description shown in the item detail dialog. */
    public String description = "";
    public String glyph;
    public int damageMin, damageMax;
    public int armorMin, armorMax;
    public double lightRadius;
    /** Satiety restored when the item is eaten. Zero for non-food. */
    public int foodValue;
    public Point location; // null when in an inventory
    public ThrownBehavior thrownBehavior = ThrownBehavior.NOTHING;
    public UseBehavior    useBehavior    = UseBehavior.NONE;
    /** HP restored by a {@link UseBehavior#HEAL} use. Zero for non-healing items. */
    public int healAmount;
    /** Element a {@link UseBehavior#WAND} use applies on impact. Ignored otherwise. */
    public WandElement wandElement;
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

    /** When true, throwing this item at a {@code CAT} or {@code DOG} converts that mob
     *  into a tame ally of the thrower (sets {@link Mob#owner} to the thrower). Used by
     *  the delicious fish — feed it to a stray and it joins the party. */
    public boolean tameOnThrow;

    /** Gem species — non-null iff {@code type == ItemType.GEM}. Drives icon colour, theme
     *  shape (triangle for crystal, square for concrete), and same-kind recipe matching. */
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

    public boolean isGem() { return type == ItemType.GEM; }
    public boolean isEquippable() { return slot != null; }
    public boolean isUsable()     { return useBehavior != null && useBehavior != UseBehavior.NONE; }

    /** True if {@code other} is "exactly identical" for stacking purposes — same type,
     *  same level, same gem species + size, same material. Equipped status is not
     *  consulted; callers ensure they're stacking on bag items. */
    public boolean matchesStackKey(Item other) {
        if (other == null || other == this) return false;
        if (type != other.type) return false;
        if (level != other.level) return false;
        if (gemSpecies != other.gemSpecies) return false;
        if (gemSize != other.gemSize) return false;
        if (material != other.material) return false;
        return true;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(name);
        if (level > 1)       sb.append(" +").append(level - 1);
        if (damageMax > 0)   sb.append(" (").append(damageMin).append("-").append(damageMax).append(" dmg)");
        if (armorMax > 0)    sb.append(" (").append(armorMin).append("-").append(armorMax).append(" armor)");
        if (lightRadius > 0) sb.append(" (light ").append((int) lightRadius).append(")");
        return sb.toString();
    }
}
