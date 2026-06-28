package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Max damage - range is {@code [damage/2, damage]} scaled by item level. */
    public int damage;
    /** Max armor - range {@code [armor/2, armor]} scaled by item level. */
    public int armor;
    /** Max AP damage - see {@link Item#apDamage}. */
    public int apDamage;
    /** Max magic resistance - CSV column {@code antiMagic}. */
    public int magicResist;
    /** Flat accuracy / evasion bonuses - see {@link Item#accuracy} /
     *  {@link Item#evasion}. */
    public int accuracy;
    public int evasion;
    /** Speed multipliers - see {@link Item#attackSpeed}. */
    public double attackSpeed = Item.ATTACK_SPEED_DEFAULT;
    public double moveSpeed   = Item.MOVE_SPEED_DEFAULT;
    public double lightRadius;
    /** Base AOE tile count for wands and bombs. See {@link Item#effectSize}. */
    public int    effectSize;

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

    /** Base buff / cloud duration in standard turns. See {@link Item#effectDuration}. */
    public int   effectDuration;
    /** Base range for JUMP / CHARGE tools. See {@link Item#effectRange}. */
    public int   effectRange;
    /** Flat magnitude knob (GRAPPLE size cap, POWERUP fractions). See {@link Item#effectPower}. */
    public float effectPower;

    /** Wand-only: charge regenerated per game-tick (or per MANA_UP
     *  pickup application). Read from the {@code chargeGain} CSV column. */
    public float chargeGain;

    /** Maximum charges at item level 0. Each level beyond the base adds 1 more
     *  max charge. 0 means the item has no charges (default). */
    public int baseChargeMax = 0;

    /** Squares to knock the target back on a successful melee hit. 0 = no knockback. */
    public int knockbackSquares;

    /** Levels of x-ray vision granted while equipped (CSV column {@code xRayEyes}).
     *  See {@link Item#xRayEyes}. */
    public int xRayEyes;

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
    /** Cluster size N when this item shows up: when picked, the populator
     *  scatters {@code ceil(N/2)..N} copies on adjacent floor tiles. */
    public int clusterSize = 1;
    /** Multiplier on this row's weight in the random-pool draws (level scatter,
     *  slot rolls, category-typed mob loot). {@code 1.0} is the baseline;
     *  {@code 2.0} makes the row twice as likely to be picked when eligible.
     *  Stacks multiplicatively with {@link #powerMin}/{@link #powerMax}
     *  triangular weighting, so a high-drop-weight row still respects its
     *  depth band. Does not affect guaranteed drops, unique-room/unique-mob
     *  loot, or themed-room explicit item cells. */
    public double dropWeight = 1.0;
    /** Allowed level themes for this item - a HARD gate. {@code null} (or empty
     *  cell) means "appears anywhere". The {@code theme} CSV column accepts: a
     *  single theme ({@code CRYSTAL}); a pipe list ({@code CONCRETE|GOTHIC|CRYSTAL});
     *  or an exclusion ({@code !CONCRETE} = every theme except concrete). An item
     *  NEVER generates on a non-SHINY level it's themed against; on allowed
     *  levels theming has no further effect (no weight bonus). */
    public Set<VisualTheme> allowedThemes;

    /** True when this item may generate on a level of theme {@code t}. SHINY is
     *  the generic theme, so theming never gates a SHINY level; otherwise the
     *  item must have no affinity or list {@code t} among its allowed themes. */
    public boolean allowsTheme(VisualTheme t) {
        if (t == VisualTheme.SHINY) return true;
        return allowedThemes == null || allowedThemes.contains(t);
    }

    /** Copies guaranteed on every eligible level, sampled per level. {@code 0}
     *  means "not guaranteed" (the default). The value may be fractional: the
     *  integer part is always placed and the fraction is the chance of one more
     *  (e.g. {@code 0.5} = a 50% chance of one copy, {@code 2.5} = two copies
     *  plus a 50% chance of a third). Guaranteed copies are placed before the
     *  random clusters are rolled. */
    public double guaranteedPerLevel;

    /** When TRUE, this item is excluded from the random-generator pool
     *  used by mob loot drops and the random-scatter pass on level
     *  population. It can still be placed by the guaranteed-per-level
     *  scatter (CSV column {@code guaranteedPerLevel}) and by explicit
     *  themed-room {@code items} cells - both of which pass an
     *  "include restricted" flag down to {@link ItemGenerator}. Used
     *  for headline rewards (POWER_ORB) so they show up only at
     *  predictable places. */
    public boolean restrictedDrop;

    /** Revive charm flag (Jade Peach) - stamped onto the Item as
     *  {@link Item#revivesOnDeath}. CSV column {@code revivesOnDeath}. */
    public boolean revivesOnDeath;

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

        d.damage      = CsvTable.intCell(row, "damage", 0);
        d.armor       = CsvTable.intCell(row, "armor", 0);
        d.apDamage    = CsvTable.intCell(row, "apDamage", 0);
        d.magicResist = CsvTable.intCell(row, "antiMagic", 0);
        d.accuracy    = CsvTable.intCell(row, "accuracyBonus", 0);
        d.evasion     = CsvTable.intCell(row, "evasion", 0);
        d.attackSpeed = CsvTable.dblCell(row, "attackSpeed", Item.ATTACK_SPEED_DEFAULT);
        d.moveSpeed   = CsvTable.dblCell(row, "moveSpeed",   Item.MOVE_SPEED_DEFAULT);
        d.lightRadius = CsvTable.dblCell(row, "lightRadius", 0);
        d.effectSize  = CsvTable.intCell(row, "effectSize", 0);

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
        d.effectDuration = CsvTable.intCell(row, "effectDuration", 0);
        d.effectRange    = CsvTable.intCell(row, "effectRange",    0);
        d.effectPower    = (float) CsvTable.dblCell(row, "effectPower", 0.0);
        d.chargeGain    = (float) CsvTable.dblCell(row, "chargeGain",    0.0);
        d.baseChargeMax =         CsvTable.intCell(row, "baseChargeMax", 0);

        d.knockbackSquares      = CsvTable.intCell(row, "knockbackSquares", 0);
        d.xRayEyes              = CsvTable.intCell(row, "xRayEyes", 0);
        d.glows                 = CsvTable.boolCell(row, "glows", false);
        d.inventoryCategory     = CsvTable.enumCell(row, "inventoryCategory",
                InventoryCategory.class, null);
        d.powerMin          = CsvTable.dblCell(row, "minPowerLevel", 0.3);
        d.powerMax          = CsvTable.dblCell(row, "maxPowerLevel", 0.7);
        d.clusterSize       = CsvTable.intCell(row, "clusterSize", 1);
        d.dropWeight        = CsvTable.dblCell(row, "dropWeight", 1.0);
        d.allowedThemes     = parseThemeSpec(CsvTable.str(row, "theme", null));
        d.guaranteedPerLevel = CsvTable.dblCell(row, "guaranteedPerLevel", 0);
        d.restrictedDrop     = CsvTable.boolCell(row, "restrictedDrop", false);
        d.revivesOnDeath     = CsvTable.boolCell(row, "revivesOnDeath", false);

        d.spriteCol     = CsvTable.intCell(row, "spriteCol", 0);
        d.spriteRow     = CsvTable.intCell(row, "spriteRow", 0);

        return d;
    }

    /** Parse the {@code theme} CSV cell into an allowed-theme set. Tolerant of
     *  hand-edits: an empty cell, a cell with no recognised themes, or a bare
     *  {@code "!"} all yield {@code null} ("any theme") rather than throwing,
     *  and unknown tokens inside a list are skipped with a warning.
     *  <ul>
     *    <li>{@code ""}                       -> null (any)</li>
     *    <li>{@code CRYSTAL}                  -> {CRYSTAL}</li>
     *    <li>{@code CONCRETE|GOTHIC|CRYSTAL}  -> {CONCRETE, GOTHIC, CRYSTAL}</li>
     *    <li>{@code !CONCRETE}                -> all themes except CONCRETE</li>
     *  </ul> */
    static Set<VisualTheme> parseThemeSpec(String cell) {
        if (cell == null) return null;
        String s = cell.trim();
        if (s.isEmpty()) return null;
        boolean exclude = s.startsWith("!");
        if (exclude) s = s.substring(1).trim();
        Set<VisualTheme> named = EnumSet.noneOf(VisualTheme.class);
        for (String tok : s.split("\\|")) {
            String name = tok.trim();
            if (name.isEmpty()) continue;
            try {
                named.add(VisualTheme.valueOf(name));
            } catch (IllegalArgumentException bad) {
                System.err.println("[items.csv] ignoring unknown theme '" + name
                        + "' in cell '" + cell + "'");
            }
        }
        if (named.isEmpty()) return null;            // nothing valid -> any theme
        if (!exclude) return named;
        Set<VisualTheme> allowed = EnumSet.allOf(VisualTheme.class);
        allowed.removeAll(named);
        return allowed;
    }

    /** Stamp this definition's fields onto a fresh {@link Item}. */
    public void apply(Item it) {
        it.type        = type;
        it.material    = material;
        it.name        = name;
        it.description  = description  == null ? "" : description;
        it.description2 = description2 == null ? "" : description2;

        it.damage       = damage;
        it.armor        = armor;
        it.apDamage     = apDamage;
        it.magicResist  = magicResist;
        it.accuracy     = accuracy;
        it.evasion      = evasion;
        it.attackSpeed  = attackSpeed;
        it.moveSpeed    = moveSpeed;
        it.lightRadius  = lightRadius;
        it.effectSize    = effectSize;

        it.throwEffect    = throwEffect;
        it.throwResult    = throwResult != null ? throwResult : Item.ThrowResult.NOTHING;
        it.useBehavior    = useBehavior;
        it.useVerb        = useVerb;
        it.wandEffect     = wandEffect;
        it.tameOnThrow    = tameOnThrow;
        it.summonsWhenUsed = summonsWhenUsed;

        it.appliesBuff    = new java.util.ArrayList<>(appliesBuff);
        it.effectDuration = effectDuration;
        it.effectRange    = effectRange;
        it.effectPower    = effectPower;
        it.chargeGain     = chargeGain;
        it.baseChargeMax  = baseChargeMax;
        it.knockbackSquares = knockbackSquares;
        it.xRayEyes         = xRayEyes;
        it.glows                 = glows;
        it.inventoryCategory     = inventoryCategory;
        it.minPowerLevel         = powerMin;
        it.revivesOnDeath        = revivesOnDeath;
        if (it.baseChargeMax > 0) {
            it.charge = it.maxCharge();
        }
    }
}
