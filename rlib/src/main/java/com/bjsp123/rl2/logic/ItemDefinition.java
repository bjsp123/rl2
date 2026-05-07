package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.MinMax;
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
    public Material material;
    public String   name;
    public String   description;

    public MinMax damage = MinMax.ZERO;
    public MinMax damagePerLevel = MinMax.ZERO;
    public MinMax armor  = MinMax.ZERO;
    public MinMax armorPerLevel  = MinMax.ZERO;
    public double lightRadius;
    public int    foodValue;
    /** Base AOE tile count for wands and bombs. See {@link Item#tilesAffected}. */
    public int    tilesAffected;
    public int    tilesAffectedPerLevel;

    /** What happens when this item is thrown; null means it just lands on the floor. */
    public ItemEffect throwEffect;
    public UseBehavior useBehavior = UseBehavior.NONE;
    public String      useVerb;
    /** Element a wand applies on impact. Null for summon-style wands. */
    public ItemEffect  wandEffect;

    public java.util.List<String> tameOnThrow = new java.util.ArrayList<>();

    /** When non-null, using this item summons the named mob type adjacent to the
     *  user (scaled to item level). */
    public String summonsWhenUsed;

    /** Optional buff applied to the user when this item is eaten or drunk. */
    public Buff.BuffType appliesBuff;

    /** Base buff duration in turns at item-level 0. Effective duration scales as
     *  {@code (1 + item.level) * buffDuration}. */
    public int buffDuration;

    /** Squares to knock the target back on a successful melee hit. 0 = no knockback. */
    public int knockbackSquares;

    /** Floor-twinkle flag — see {@link Item#glows}. */
    public boolean glows;

    /** Category this item silhouettes for when its slot is empty — see
     *  {@link Item#silhouetteForCategory}. */
    public InventoryCategory silhouetteForCategory;

    /** Authoritative item-kind tag — see {@link Item#inventoryCategory}. */
    public InventoryCategory inventoryCategory;

    /** Where in the dungeon this item is meant to appear. Expressed as a
     *  fraction-of-depth window (0 = depth 1, 1 = {@code DUNGEON_DEPTH}); the
     *  populator filters out levels whose depth-fraction falls outside
     *  {@code [powerMin, powerMax]} and weights survivors by closeness to the
     *  midpoint, so an item with {@code 0.3_0.7} peaks at mid-dungeon and
     *  fades away at the band's edges. */
    public double powerMin = 0.3;
    public double powerMax = 0.7;
    /** Cluster size when this item shows up: when picked, the populator drops
     *  this many copies on adjacent floor tiles. {@code 1_1} (or {@code 1}) is
     *  a single-item placement; bombs / oil flasks tend to cluster, so they
     *  use higher ranges. */
    public MinMax clusterSize = MinMax.of(1);
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
        d.material    = CsvTable.enumCell(row, "material", Material.class, Material.MAGIC);

        d.damage         = CsvTable.minMaxCell(row, "damage", MinMax.ZERO);
        d.damagePerLevel = CsvTable.minMaxCell(row, "damagePerLevel", MinMax.ZERO);
        d.armor          = CsvTable.minMaxCell(row, "armor", MinMax.ZERO);
        d.armorPerLevel  = CsvTable.minMaxCell(row, "armorPerLevel", MinMax.ZERO);
        d.lightRadius    = CsvTable.dblCell(row, "lightRadius", 0);
        d.foodValue      = CsvTable.intCell(row, "foodValue", 0);
        d.tilesAffected         = CsvTable.intCell(row, "tilesAffected", 0);
        d.tilesAffectedPerLevel = CsvTable.intCell(row, "tilesAffectedPerLevel", 0);

        d.throwEffect = CsvTable.enumCell(row, "throwEffect", ItemEffect.class, null);
        d.useBehavior = CsvTable.enumCell(row, "useBehavior", UseBehavior.class, UseBehavior.NONE);
        d.useVerb     = CsvTable.str(row, "useVerb", null);
        d.wandEffect  = CsvTable.enumCell(row, "wandEffect", ItemEffect.class, null);
        d.tameOnThrow = new java.util.ArrayList<>(CsvTable.listCell(row, "tameOnThrow"));
        d.summonsWhenUsed = CsvTable.str(row, "summonsWhenUsed", null);

        d.appliesBuff   = CsvTable.enumCell(row, "appliesBuff", Buff.BuffType.class, null);
        d.buffDuration  = CsvTable.intCell(row, "buffDuration", 0);

        d.knockbackSquares      = CsvTable.intCell(row, "knockbackSquares", 0);
        d.glows                 = CsvTable.boolCell(row, "glows", false);
        d.silhouetteForCategory = CsvTable.enumCell(row, "silhouetteForCategory",
                InventoryCategory.class, null);
        d.inventoryCategory     = CsvTable.enumCell(row, "inventoryCategory",
                InventoryCategory.class, null);
        double[] power      = CsvTable.dblRangeCell(row, "powerLevel", 0.3, 0.7);
        d.powerMin          = power[0];
        d.powerMax          = power[1];
        d.clusterSize       = CsvTable.minMaxCell(row, "clusterSize", MinMax.of(1));
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
        it.material    = material;
        it.name        = name;
        it.description = description == null ? "" : description;

        it.damage         = damage;
        it.damagePerLevel = damagePerLevel;
        it.armor          = armor;
        it.armorPerLevel  = armorPerLevel;
        it.lightRadius    = lightRadius;
        it.foodValue      = foodValue;
        it.tilesAffected         = tilesAffected;
        it.tilesAffectedPerLevel = tilesAffectedPerLevel;

        it.throwEffect    = throwEffect;
        it.useBehavior    = useBehavior;
        it.useVerb        = useVerb;
        it.wandEffect     = wandEffect;
        it.tameOnThrow    = tameOnThrow;
        it.summonsWhenUsed = summonsWhenUsed;

        it.appliesBuff    = appliesBuff;
        it.buffDuration   = buffDuration;
        it.knockbackSquares = knockbackSquares;
        it.glows                 = glows;
        it.silhouetteForCategory = silhouetteForCategory;
        it.inventoryCategory     = inventoryCategory;
    }
}
