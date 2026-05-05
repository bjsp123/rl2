package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemSlot;
import com.bjsp123.rl2.model.Item.ThrownBehavior;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Item.WandElement;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/items.csv} parsed into a typed POJO.
 * Mirrors {@link MobDefinition}: {@link #parseAll(String)} parses the CSV,
 * {@link #apply(Item)} stamps the row's fields onto a fresh {@link Item}.
 *
 * <p>The {@code type} cell is the lookup key in {@link ItemRegistry}; case
 * matters and is referenced by string from CSV cells (player kits, summon
 * targets, etc.).
 */
public final class ItemDefinition {

    public String type;
    public ItemSlot slot;
    public Material material;
    public String   name;
    public String   description;

    public int    damageMin;
    public int    damageMax;
    public int    armorMin;
    public int    armorMax;
    public double lightRadius;
    public int    foodValue;
    public int    healAmount;

    public ThrownBehavior thrownBehavior = ThrownBehavior.NOTHING;
    public UseBehavior    useBehavior    = UseBehavior.NONE;
    public String         useVerb;
    public WandElement    wandElement;

    public java.util.List<String> tameOnThrow = new java.util.ArrayList<>();

    /** When non-null, using this item summons the named mob type adjacent to the
     *  user (scaled to item level). */
    public String summonsWhenUsed;

    /** Optional buff applied to the user when this item is eaten or drunk. */
    public Buff.BuffType appliesBuff;

    /** Base buff duration in turns at item-level 0. Effective duration scales as
     *  {@code (1 + item.level) * buffDuration}. */
    public int buffDuration;

    /** Base self-damage on use (poison potion). Effective = base + item.level. */
    public int selfDamageBase;

    /** Floor-twinkle flag — see {@link Item#glows}. */
    public boolean glows;

    /** Slot this item silhouettes for when the slot is empty — see
     *  {@link Item#silhouetteForSlot}. */
    public ItemSlot silhouetteForSlot;

    /** Inventory tab tag — see {@link Item#inventoryCategory}. */
    public String inventoryCategory;

    /** Inclusive dungeon-depth window in which the level populator considers
     *  this item eligible for random scatter. {@code minDepth = maxDepth = 0}
     *  takes the item out of the random pool entirely. */
    public int minDepth = 1;
    public int maxDepth = 10;
    /** Optional theme gate. When non-null, the item is only eligible on levels
     *  whose {@code theme} matches; null means "any theme". */
    public com.bjsp123.rl2.model.Level.VisualTheme theme;

    /** When true, the level populator places one of this item on every floor
     *  (in addition to the random scatter). Used by the pear food-floor rule. */
    public boolean guaranteedPerLevel;

    /** Atlas-cell coordinates on {@code sprites/items.png} (32-px grid). Read by
     *  the rgame-side {@code ItemSprites} loader; rlib code never touches them. */
    public int spriteCol;
    public int spriteRow;

    public static List<ItemDefinition> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<ItemDefinition> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            out.add(parseRow(row));
        }
        return out;
    }

    private static ItemDefinition parseRow(Map<String, String> row) {
        ItemDefinition d = new ItemDefinition();
        d.type        = CsvTable.str(row, "type", null);
        d.name        = CsvTable.str(row, "name", null);
        d.description = CsvTable.str(row, "description", "");
        d.slot        = CsvTable.enumCell(row, "slot", ItemSlot.class, null);
        d.material    = CsvTable.enumCell(row, "material", Material.class, Material.MAGIC);

        d.damageMin   = CsvTable.intCell(row, "damageMin", 0);
        d.damageMax   = CsvTable.intCell(row, "damageMax", 0);
        d.armorMin    = CsvTable.intCell(row, "armorMin", 0);
        d.armorMax    = CsvTable.intCell(row, "armorMax", 0);
        d.lightRadius = CsvTable.dblCell(row, "lightRadius", 0);
        d.foodValue   = CsvTable.intCell(row, "foodValue", 0);
        d.healAmount  = CsvTable.intCell(row, "healAmount", 0);

        d.thrownBehavior = CsvTable.enumCell(row, "thrownBehavior",
                ThrownBehavior.class, ThrownBehavior.NOTHING);
        d.useBehavior    = CsvTable.enumCell(row, "useBehavior",
                UseBehavior.class, UseBehavior.NONE);
        d.useVerb        = CsvTable.str(row, "useVerb", null);
        d.wandElement    = CsvTable.enumCell(row, "wandElement",
                WandElement.class, null);
        d.tameOnThrow    = new java.util.ArrayList<>(CsvTable.listCell(row, "tameOnThrow"));
        d.summonsWhenUsed = CsvTable.str(row, "summonsWhenUsed", null);

        d.appliesBuff   = CsvTable.enumCell(row, "appliesBuff",
                Buff.BuffType.class, null);
        d.buffDuration  = CsvTable.intCell(row, "buffDuration", 0);
        d.selfDamageBase = CsvTable.intCell(row, "selfDamageBase", 0);

        d.glows             = CsvTable.boolCell(row, "glows", false);
        d.silhouetteForSlot = CsvTable.enumCell(row, "silhouetteForSlot",
                ItemSlot.class, null);
        d.inventoryCategory = CsvTable.str(row, "inventoryCategory", null);
        d.minDepth          = CsvTable.intCell(row, "minDepth", 1);
        d.maxDepth          = CsvTable.intCell(row, "maxDepth", 10);
        d.theme             = CsvTable.enumCell(row, "theme",
                com.bjsp123.rl2.model.Level.VisualTheme.class, null);
        d.guaranteedPerLevel = CsvTable.boolCell(row, "guaranteedPerLevel", false);

        d.spriteCol     = CsvTable.intCell(row, "spriteCol", 0);
        d.spriteRow     = CsvTable.intCell(row, "spriteRow", 0);

        return d;
    }

    /** Stamp this definition's fields onto a fresh {@link Item}. */
    public void apply(Item it) {
        it.type        = type;
        it.slot        = slot;
        it.material    = material;
        it.name        = name;
        it.description = description == null ? "" : description;

        it.damageMin   = damageMin;
        it.damageMax   = damageMax;
        it.armorMin    = armorMin;
        it.armorMax    = armorMax;
        it.lightRadius = lightRadius;
        it.foodValue   = foodValue;
        it.healAmount  = healAmount;

        it.thrownBehavior = thrownBehavior;
        it.useBehavior    = useBehavior;
        it.useVerb        = useVerb;
        it.wandElement    = wandElement;
        it.tameOnThrow    = tameOnThrow;
        it.summonsWhenUsed = summonsWhenUsed;

        it.appliesBuff    = appliesBuff;
        it.buffDuration   = buffDuration;
        it.selfDamageBase = selfDamageBase;
        it.glows          = glows;
        it.silhouetteForSlot = silhouetteForSlot;
        it.inventoryCategory = inventoryCategory;
    }
}
