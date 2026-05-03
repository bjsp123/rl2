package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemSlot;
import com.bjsp123.rl2.model.Item.ItemType;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.model.Item.ThrownBehavior;
import com.bjsp123.rl2.model.Item.UseBehavior;

public class ItemFactory {

    public static Item sword() {
        Item it = new Item();
        it.type        = ItemType.SWORD;
        it.slot        = ItemSlot.WEAPON;
        it.material    = Material.METAL;
        it.name        = "sword";
        it.description = "A plain iron blade. Heavy enough to bludgeon a goblin with if thrown.";
        it.glyph       = "/";
        it.damageMin   = 2;
        it.damageMax   = 5;
        it.thrownBehavior = ThrownBehavior.DAMAGE;
        return it;
    }

    public static Item shield() {
        Item it = new Item();
        it.type        = ItemType.SHIELD;
        it.slot        = ItemSlot.OFFHAND;
        it.material    = Material.WOOD;
        it.name        = "shield";
        it.description = "A round wooden shield. Blocks blows, but too light to bruise anyone.";
        it.glyph       = ")";
        it.armorMin    = 1;
        it.armorMax    = 2;
        return it;
    }

    public static Item scaleMail() {
        Item it = new Item();
        it.type        = ItemType.SCALE_MAIL;
        it.slot        = ItemSlot.ARMOR;
        it.material    = Material.METAL;
        it.name        = "scale mail";
        it.description = "Overlapping iron scales on a leather backing. Painful to be hit by.";
        it.glyph       = "[";
        it.armorMin    = 5;
        it.armorMax    = 6;
        it.thrownBehavior = ThrownBehavior.DAMAGE;
        return it;
    }

    public static Item dagger() {
        Item it = new Item();
        it.type        = ItemType.DAGGER;
        it.slot        = ItemSlot.WEAPON;
        it.material    = Material.METAL;
        it.name        = "dagger";
        it.description = "A short bladed dagger. Quicker to wield than a sword but less punishing on a hit.";
        it.glyph       = "/";
        it.damageMin   = 1;
        it.damageMax   = 3;
        it.thrownBehavior = ThrownBehavior.DAMAGE;
        return it;
    }

    public static Item amuletOfLight() {
        Item it = new Item();
        it.type        = ItemType.AMULET_OF_LIGHT;
        it.slot        = ItemSlot.AMULET;
        it.material    = Material.MAGIC;
        it.name        = "amulet of light";
        it.description = "A pale gem on a leather cord. Sheds a wide radius of soft light.";
        it.glyph       = "\"";
        it.lightRadius = 10;
        return it;
    }

    public static Item fireBomb() {
        Item it = new Item();
        it.type        = ItemType.FIRE_BOMB;
        it.slot        = null;
        it.material    = Material.MAGIC;
        it.name        = "fire bomb";
        it.description = "A flask of unstable alchemy. On impact, deals 1+level damage and "
                       + "ignites 2+level tiles around the target.";
        it.glyph       = "!";
        it.thrownBehavior = ThrownBehavior.IGNITE;
        return it;
    }

    /** Blast bomb — radial concussion. Damages everything in a disc of radius
     *  {@code 1 + level/2} for {@code 2 + level*2} damage, with a "blast" particle
     *  effect on every affected square. Pushback is described in the spec but not yet
     *  implemented (TODO). */
    public static Item blastBomb() {
        Item it = new Item();
        it.type        = ItemType.BLAST_BOMB;
        it.slot        = null;
        it.material    = Material.MAGIC;
        it.name        = "blast bomb";
        it.description = "A heavy iron sphere that detonates concussively, hurting everything "
                       + "around the impact point.";
        it.glyph       = "*";
        it.thrownBehavior = ThrownBehavior.BLAST;
        return it;
    }

    /** Freeze bomb — radial chill. Damages 1+level on impact, applies CHILLED to
     *  everything in a disc of radius {@code 2 + level} (TODO: ICE surface for water,
     *  fire-removal). */
    public static Item freezeBomb() {
        Item it = new Item();
        it.type        = ItemType.FREEZE_BOMB;
        it.slot        = null;
        it.material    = Material.MAGIC;
        it.name        = "freeze bomb";
        it.description = "A frosted vial that shatters into a freezing burst — chills nearby "
                       + "creatures and douses any flames.";
        it.glyph       = "*";
        it.thrownBehavior = ThrownBehavior.FREEZE;
        return it;
    }

    public static Item healingPotion() {
        Item it = new Item();
        it.type        = Item.ItemType.HEALING_POTION;
        it.material    = Material.MAGIC;
        it.name        = "healing potion";
        it.description = "A vial of bluish elixir. Restores HP on a draught — more at higher "
                       + "potion levels.";
        it.glyph       = "P";
        it.useBehavior = UseBehavior.HEAL;
        it.useVerb     = "drink";
        // Baseline; the actual heal applied at use-time = BASIC + level*INCREMENT, so
        // a level-0 starter potion heals 20 and a level-2 dungeon find heals 40. The
        // healAmount field on Item caches the BASIC value for legacy callers; the
        // DRINK-path in PlayScreen reads it via {@link MobSystem#healAmountFor}.
        it.healAmount  = GameBalance.BASIC_HEAL_VALUE;
        return it;
    }

    private static Item baseWand(Item.ItemType type, String name, String description,
                                 Item.WandElement element) {
        Item it = new Item();
        it.type        = type;
        it.material    = Material.WOOD;
        it.name        = name;
        it.description = description;
        it.glyph       = "~";
        it.useBehavior = UseBehavior.WAND;
        it.useVerb     = "zap";
        it.wandElement = element;
        return it;
    }

    public static Item wandOfWater() {
        return baseWand(Item.ItemType.WAND_WATER, "wand of water",
                "A pale blue wand. Pools water on the target tile.",
                Item.WandElement.WATER);
    }

    public static Item wandOfOil() {
        return baseWand(Item.ItemType.WAND_OIL, "wand of oil",
                "A dark wand slick to the touch. Slicks oil onto the target tile.",
                Item.WandElement.OIL);
    }

    public static Item wandOfVegetation() {
        return baseWand(Item.ItemType.WAND_GRASS, "wand of vegetation",
                "A green wand bound with vine. Sprouts grass and trees at the target.",
                Item.WandElement.GRASS);
    }

    public static Item wandOfFungus() {
        return baseWand(Item.ItemType.WAND_FUNGUS, "wand of fungus",
                "A spongy red-brown wand. Coaxes mushrooms up from the target.",
                Item.WandElement.FUNGUS);
    }

    public static Item wandOfFire() {
        return baseWand(Item.ItemType.WAND_FIRE, "wand of fire",
                "A blackened wand crackling with embers. Ignites the target.",
                Item.WandElement.FIRE);
    }

    public static Item wandOfDog() {
        Item it = baseWand(Item.ItemType.WAND_DOG, "wand of dog",
                "A wand carved from antler. On a zap, summons a loyal dog beside you.",
                Item.WandElement.DOG_SPAWN);
        it.useVerb = "summon";
        return it;
    }

    /** Wand of detonation — fires a heavy magic missile that explodes on impact, igniting
     *  a Euclidean disc of radius {@code 2 + level} and spawning a radial particle burst.
     *  Level scales the blast radius: level 1 = radius 3 (matches a blazing firemouse's
     *  death explosion), level 2 = radius 4, level 3 = radius 5, etc. */
    public static Item wandOfDetonation() {
        return baseWand(Item.ItemType.WAND_DETONATION, "wand of detonation",
                "A blackened iron wand inlaid with red runes. Detonates the target tile in a "
              + "ball of fire.",
                Item.WandElement.DETONATION);
    }

    /** Wand of magic missile — fires a plain magic missile that damages whatever it lands
     *  on. Same useBehavior as the staff, just packaged as a wand so it lives in the
     *  wand grid. Damage is rolled from {@code damageMin..damageMax} on use. */
    public static Item wandOfMagicMissile() {
        Item it = new Item();
        it.type         = Item.ItemType.WAND_MAGIC_MISSILE;
        it.material     = Material.WOOD;
        it.name         = "wand of magic missile";
        it.description  = "A short wand topped with a pale chip. Fires a magic missile.";
        it.glyph        = "~";
        it.useBehavior  = UseBehavior.MAGIC_MISSILE;
        it.useVerb      = "zap";
        it.damageMin    = 3;
        it.damageMax    = 6;
        return it;
    }

    /** Wand of banishment — fires a straight beam along the line from caster to target.
     *  Any GHOST sitting on the beam is instantly destroyed (banishment-on-impact in
     *  {@code MobSystem.applyWandImpact}). Other mobs are unaffected. */
    public static Item wandOfBanishment() {
        return baseWand(Item.ItemType.WAND_BANISHMENT, "wand of banishment",
                "A wand bound in tarnished silver. A bright beam of light leaves a banished "
              + "ghost as a wisp of memory.",
                Item.WandElement.BANISHMENT);
    }

    public static Item oilBomb() {
        Item it = new Item();
        it.type        = ItemType.OIL_BOMB;
        it.slot        = null;
        it.material    = Material.MAGIC;
        it.name        = "oil bomb";
        it.description = "A glass sphere of dark oil. Shatters and slicks the floor on impact.";
        it.glyph       = "*";
        it.thrownBehavior = ThrownBehavior.OIL_SPLASH;
        return it;
    }

    public static Item pear() {
        Item it = new Item();
        it.type        = ItemType.PEAR;
        it.slot        = null;
        it.material    = Material.FLESH;
        it.name        = "pear";
        it.description = "A ripe green pear. Juicy enough to take the edge off a hungry belly.";
        it.glyph       = "%";
        it.foodValue   = 10000;
        it.useBehavior = UseBehavior.EAT;
        it.useVerb     = "eat";
        return it;
    }

    /** Delicious fish — heartier food than a pear, and a tame-on-throw projectile when
     *  hurled at a stray cat or dog. Sets {@link Item#tameOnThrow} so the throw path can
     *  recognise the special case. */
    public static Item fish() {
        Item it = new Item();
        it.type        = ItemType.FISH;
        it.slot        = null;
        it.material    = Material.FLESH;
        it.name        = "delicious fish";
        it.description = "A small fresh fish. So tasty that a stray cat or dog hit by it "
                       + "will follow you for the rest of its life.";
        it.glyph       = "%";
        it.foodValue   = 30000;
        it.useBehavior = UseBehavior.EAT;
        it.useVerb     = "eat";
        it.tameOnThrow = true;
        it.thrownBehavior = ThrownBehavior.NOTHING;
        return it;
    }

    /** Scrumptious pear — five times the food value of an ordinary pear. */
    public static Item scrumptiousPear() {
        Item it = pear();
        it.type        = ItemType.PEAR_SCRUMPTIOUS;
        it.name        = "scrumptious pear";
        it.description = "A specially ripe pear, dripping with juice. Five times more filling "
                       + "than the ordinary kind.";
        it.foodValue   = 50000;
        return it;
    }

    /** Silvery pear — visually distinct from a plain pear but mechanically identical for
     *  now. Reserved for a future bonus effect. */
    public static Item silveryPear() {
        Item it = pear();
        it.type        = ItemType.PEAR_SILVERY;
        it.name        = "silvery pear";
        it.description = "A pear with skin that shimmers like polished silver. Mysterious.";
        return it;
    }

    /** Conference pear — visually distinct from a plain pear but mechanically identical
     *  for now. Reserved for a future bonus effect. */
    public static Item conferencePear() {
        Item it = pear();
        it.type        = ItemType.PEAR_CONFERENCE;
        it.name        = "conference pear";
        it.description = "A long elegant pear, reportedly the kind eaten at high meetings.";
        return it;
    }

    // ── Potions ────────────────────────────────────────────────────────────────────

    /** Build a base potion item — drink-on-use, MAGIC material, glyph {@code P}. The
     *  per-potion factories layer the specific name + description + use behaviour on
     *  top. */
    private static Item basePotion(Item.ItemType type, String name, String description) {
        Item it = new Item();
        it.type        = type;
        it.material    = Material.MAGIC;
        it.name        = name;
        it.description = description;
        it.glyph       = "P";
        it.useVerb     = "drink";
        return it;
    }

    /** Potion of sorcery — applies {@link com.bjsp123.rl2.model.Buff.BuffType#SORCERY}
     *  at {@code level = potion.level} for {@code potion.level} turns. */
    public static Item potionOfSorcery() {
        Item it = basePotion(Item.ItemType.POTION_SORCERY, "potion of sorcery",
                "A glittering blue brew. Empowers every wand the drinker carries.");
        it.useBehavior = UseBehavior.DRINK;
        return it;
    }

    /** Potion of ghostliness — applies {@link com.bjsp123.rl2.model.Buff.BuffType#GHOSTLY}.
     *  Flying + wall-pass for {@code potion.level} turns. */
    public static Item potionOfGhostliness() {
        Item it = basePotion(Item.ItemType.POTION_GHOSTLINESS, "potion of ghostliness",
                "A swirling pearl-grey liquid. The drinker temporarily becomes ethereal — "
              + "flies and slips through walls.");
        it.useBehavior = UseBehavior.DRINK;
        return it;
    }

    /** Potion of invisibility — applies {@link com.bjsp123.rl2.model.Buff.BuffType#INVISIBLE}
     *  for {@code potion.level * 2} turns. */
    public static Item potionOfInvisibility() {
        Item it = basePotion(Item.ItemType.POTION_INVISIBILITY, "potion of invisibility",
                "A clear potion that hides the imbiber from sight for a short while.");
        it.useBehavior = UseBehavior.DRINK;
        return it;
    }

    /** Potion of poison — drink: applies {@link com.bjsp123.rl2.model.Buff.BuffType#POISONED}
     *  to the drinker. Throw: damages whoever's on the impact tile (poison cloud is
     *  TODO). */
    public static Item potionOfPoison() {
        Item it = basePotion(Item.ItemType.POTION_POISON, "potion of poison",
                "A sickly green elixir. Vile to drink, devastating to throw.");
        it.useBehavior = UseBehavior.DRINK;
        it.thrownBehavior = ThrownBehavior.DAMAGE;
        it.damageMin   = 4;
        it.damageMax   = 8;
        return it;
    }
}
