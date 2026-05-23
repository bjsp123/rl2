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
    /** Optional second-paragraph flavor blurb. CSV column header is
     *  {@code "description 2"} (with a space). Mirrors {@link Item#description2}. */
    public String   description2;

    public MinMax damage = MinMax.ZERO;
    public MinMax damagePerLevel = MinMax.ZERO;
    public MinMax armor  = MinMax.ZERO;
    public MinMax armorPerLevel  = MinMax.ZERO;
    /** AP damage range - see {@link Item#apDamage}. */
    public MinMax apDamage = MinMax.ZERO;
    public MinMax apDamagePerLevel = MinMax.ZERO;
    /** Magic-resistance range - see {@link Item#magicResist}.
     *  CSV column is {@code antiMagic}. */
    public MinMax magicResist = MinMax.ZERO;
    public MinMax magicResistPerLevel = MinMax.ZERO;
    /** Flat accuracy / evasion bonuses - see {@link Item#accuracy} /
     *  {@link Item#evasion}. */
    public int accuracy;
    public int evasion;
    /** Speed multipliers - see {@link Item#attackSpeed}. */
    public double attackSpeed = Item.ATTACK_SPEED_DEFAULT;
    public double moveSpeed   = Item.MOVE_SPEED_DEFAULT;
    public double lightRadius;
    public int    foodValue;
    /** Base AOE tile count for wands and bombs. See {@link Item#tilesAffected}. */
    public int    tilesAffected;
    public int    tilesAffectedPerLevel;

    /** What happens when this item is thrown; null means it just lands on the floor. */
    public ItemEffect throwEffect;
    /** Per-CSV-row throw-result. Blank cell -> {@link Item.ThrowResult#NOTHING}. */
    public Item.ThrowResult throwResult = Item.ThrowResult.NOTHING;
    public UseBehavior useBehavior = UseBehavior.NONE;
    public String      useVerb;
    /** Element a wand applies on impact. Null for summon-style wands. */
    public ItemEffect  wandEffect;

    public java.util.List<String> tameOnThrow = new java.util.ArrayList<>();

    /** When non-null, using this item summons the named mob type adjacent to the
     *  user (scaled to item level). */
    public String summonsWhenUsed;

    /** Pipe-separated list of buffs the item applies to the user / target.
     *  Single-buff CSV entries are read as a 1-element list. Empty list
     *  means the item has no buff component. */
    public java.util.List<Buff.BuffType> appliesBuff = new java.util.ArrayList<>();

    /** Generic magnitude knob - see {@link Item#abilityPower} for the
     *  per-useBehavior interpretation. Float so fractional powers fit. */
    public float abilityPower;

    /** Wand-only: charge regenerated per game-tick (or per MANA_UP
     *  pickup application). Read from the {@code chargeGain} CSV column. */
    public float chargeGain;

    /** Maximum charges at item level 0. Each level beyond the base adds 1 more
     *  max charge. 0 means the item has no charges (default). */
    public int baseChargeMax = 0;

    /** Squares to knock the target back on a successful melee hit. 0 = no knockback. */
    public int knockbackSquares;

    /** Floor-twinkle flag - see {@link Item#glows}. */
    public boolean glows;

    /** Authoritative item-kind tag - see {@link Item#inventoryCategory}. */
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
    /** Minimum copies guaranteed on every eligible level. {@code 0}
     *  means "not guaranteed" (the default); {@code 1+} forces that
     *  many copies to be placed before random clusters are rolled. */
    public int guaranteedPerLevel;

    /** Signature-item flag - see {@link Item#scalesWithUser}. CSV column
     *  {@code scalesWithUser}; blank/FALSE everywhere except jade items. */
    public boolean scalesWithUser;

    /** When TRUE, this item is excluded from the random-generator pool
     *  used by mob loot drops and the random-scatter pass on level
     *  population. It can still be placed by the guaranteed-per-level
     *  scatter (CSV column {@code guaranteedPerLevel}) and by explicit
     *  themed-room {@code items} cells - both of which pass an
     *  "include restricted" flag down to {@link ItemGenerator}. Used
     *  for headline rewards (POWER_ORB) so they show up only at
     *  predictable places. */
    public boolean restrictedDrop;

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
        d.name        = TextCatalog.itemName(d.type, CsvTable.str(row, "name", null));
        d.description  = TextCatalog.itemDescription(d.type, CsvTable.str(row, "description", ""));
        d.description2 = TextCatalog.itemDescription2(d.type, CsvTable.str(row, "description 2", ""));
        d.material    = CsvTable.enumCell(row, "material", Material.class, Material.MAGIC);

        d.damage         = CsvTable.minMaxCell(row, "damage", MinMax.ZERO);
        d.damagePerLevel = CsvTable.minMaxCell(row, "damagePerLevel", MinMax.ZERO);
        d.armor          = CsvTable.minMaxCell(row, "armor", MinMax.ZERO);
        d.armorPerLevel  = CsvTable.minMaxCell(row, "armorPerLevel", MinMax.ZERO);
        d.apDamage          = CsvTable.minMaxCell(row, "apDamage", MinMax.ZERO);
        d.apDamagePerLevel  = CsvTable.minMaxCell(row, "apDamagePerLevel", MinMax.ZERO);
        d.magicResist          = CsvTable.minMaxCell(row, "antiMagic", MinMax.ZERO);
        d.magicResistPerLevel  = CsvTable.minMaxCell(row, "antiMagicPerLevel", MinMax.ZERO);
        d.accuracy       = CsvTable.intCell(row, "accuracy", 0);
        d.evasion        = CsvTable.intCell(row, "evasion", 0);
        d.attackSpeed    = CsvTable.dblCell(row, "attackSpeed", Item.ATTACK_SPEED_DEFAULT);
        d.moveSpeed      = CsvTable.dblCell(row, "moveSpeed",   Item.MOVE_SPEED_DEFAULT);
        d.lightRadius    = CsvTable.dblCell(row, "lightRadius", 0);
        d.foodValue      = CsvTable.intCell(row, "foodValue", 0);
        d.tilesAffected         = CsvTable.intCell(row, "tilesAffected", 0);
        d.tilesAffectedPerLevel = CsvTable.intCell(row, "tilesAffectedPerLevel", 0);

        d.throwEffect = CsvTable.enumCell(row, "throwEffect", ItemEffect.class, null);
        d.throwResult = CsvTable.enumCell(row, "throwResult",
                Item.ThrowResult.class, Item.ThrowResult.NOTHING);
        d.useBehavior = CsvTable.enumCell(row, "useBehavior", UseBehavior.class, UseBehavior.NONE);
        d.useVerb     = CsvTable.str(row, "useVerb", null);
        d.wandEffect  = CsvTable.enumCell(row, "wandEffect", ItemEffect.class, null);
        d.tameOnThrow = new java.util.ArrayList<>(CsvTable.listCell(row, "tameOnThrow"));
        d.summonsWhenUsed = CsvTable.str(row, "summonsWhenUsed", null);

        // Pipe-separated list (e.g. "POISONED|CHILLED") - single-buff
        // entries parse to a 1-element list.
        d.appliesBuff = new java.util.ArrayList<>();
        for (String name : CsvTable.listCell(row, "appliesBuff")) {
            try {
                d.appliesBuff.add(Buff.BuffType.valueOf(name.trim()));
            } catch (IllegalArgumentException ignored) { /* skip bad name */ }
        }
        d.abilityPower  = (float) CsvTable.dblCell(row, "abilityPower",  0.0);
        d.chargeGain    = (float) CsvTable.dblCell(row, "chargeGain",    0.0);
        d.baseChargeMax =         CsvTable.intCell(row, "baseChargeMax", 0);

        d.knockbackSquares      = CsvTable.intCell(row, "knockbackSquares", 0);
        d.glows                 = CsvTable.boolCell(row, "glows", false);
        d.inventoryCategory     = CsvTable.enumCell(row, "inventoryCategory",
                InventoryCategory.class, null);
        double[] power      = CsvTable.dblRangeCell(row, "powerLevel", 0.3, 0.7);
        d.powerMin          = power[0];
        d.powerMax          = power[1];
        d.clusterSize       = CsvTable.minMaxCell(row, "clusterSize", MinMax.of(1));
        d.theme             = CsvTable.enumCell(row, "theme",
                com.bjsp123.rl2.model.Level.VisualTheme.class, null);
        d.guaranteedPerLevel = CsvTable.intCell(row, "guaranteedPerLevel", 0);
        d.restrictedDrop     = CsvTable.boolCell(row, "restrictedDrop", false);
        d.scalesWithUser     = CsvTable.boolCell(row, "scalesWithUser", false);

        d.spriteCol     = CsvTable.intCell(row, "spriteCol", 0);
        d.spriteRow     = CsvTable.intCell(row, "spriteRow", 0);

        return d;
    }

    /** Stamp this definition's fields onto a fresh {@link Item}. */
    public void apply(Item it) {
        it.type        = type;
        it.material    = material;
        it.name        = name;
        it.description  = description  == null ? "" : description;
        it.description2 = description2 == null ? "" : description2;

        it.damage         = damage;
        it.damagePerLevel = damagePerLevel;
        it.armor          = armor;
        it.armorPerLevel  = armorPerLevel;
        it.apDamage           = apDamage;
        it.apDamagePerLevel   = apDamagePerLevel;
        it.magicResist        = magicResist;
        it.magicResistPerLevel = magicResistPerLevel;
        it.accuracy       = accuracy;
        it.evasion        = evasion;
        it.attackSpeed    = attackSpeed;
        it.moveSpeed      = moveSpeed;
        it.lightRadius    = lightRadius;
        it.foodValue      = foodValue;
        it.tilesAffected         = tilesAffected;
        it.tilesAffectedPerLevel = tilesAffectedPerLevel;

        it.throwEffect    = throwEffect;
        it.throwResult    = throwResult != null ? throwResult : Item.ThrowResult.NOTHING;
        it.useBehavior    = useBehavior;
        it.useVerb        = useVerb;
        it.wandEffect     = wandEffect;
        it.tameOnThrow    = tameOnThrow;
        it.summonsWhenUsed = summonsWhenUsed;

        it.appliesBuff    = new java.util.ArrayList<>(appliesBuff);
        it.abilityPower   = abilityPower;
        it.chargeGain     = chargeGain;
        it.baseChargeMax  = baseChargeMax;
        it.knockbackSquares = knockbackSquares;
        it.glows                 = glows;
        it.inventoryCategory     = inventoryCategory;
        it.scalesWithUser        = scalesWithUser;
        if (it.baseChargeMax > 0) {
            it.charge = it.maxCharge();
        }
    }
}
