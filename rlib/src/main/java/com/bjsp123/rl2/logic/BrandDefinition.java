package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * One row from {@code assets/data/brands.csv}. Brands are elemental or
 * stat-modifying affixes that can be applied to weapons, offhands, armor,
 * and amulets at generation time.
 *
 * <p>{@link #element} reuses {@link Item.ItemEffect} — only the values
 * {@code FIRE}, {@code LIGHTNING}, {@code FREEZE}, and {@code POISONCLOUD}
 * are meaningful here; a null element means "stat brand only".
 */
public final class BrandDefinition {

    /** CSV key (e.g. {@code "FLAME"}). */
    public String brand;
    /** Display suffix appended to the item name (e.g. {@code "flame"} → "sword of flame"). */
    public String name;
    /** Relative weight for random selection. Higher = more common. */
    public double rarity = 1.0;
    /** Item categories this brand may appear on. Parsed from a pipe-separated column. */
    public EnumSet<Item.InventoryCategory> itemTypes =
            EnumSet.noneOf(Item.InventoryCategory.class);
    /** Flavor text shown in the item lore panel. */
    public String description = "";

    // ── Stat bonuses ─────────────────────────────────────────────────────────
    public int    accuracy;
    public int    evasion;
    /** Flat damage bonus (added to dst.damage as a fixed MinMax). */
    public int    damage;
    /** Flat armor bonus. */
    public int    armor;
    /** Flat magic-resist bonus (CSV column: {@code antimagic}). */
    public int    antimagic;
    /** Move-cost multiplier. {@code 1.0} = no change; {@code 0.9} = 10% faster. */
    public double moveSpeed   = 1.0;
    /** Attack-cost multiplier. {@code 1.0} = no change; {@code 0.9} = 10% faster. */
    public double attackSpeed = 1.0;
    /** Knockback squares added to the wielder's knockback stat. */
    public int    knockback;

    // ── Elemental on-hit ─────────────────────────────────────────────────────
    /** On-hit elemental effect. Null = stat brand only. Meaningful values:
     *  FIRE, LIGHTNING, FREEZE, POISONCLOUD. */
    public Item.ItemEffect element;
    /** Magnitude passed to the on-hit effect (e.g. poison cloud duration,
     *  lightning chain damage cap). */
    public int elementpower;

    // ── Resistance / utility flags (stored for future use) ───────────────────
    public boolean resistFire;
    public boolean resistPoison;
    public boolean sorcery;

    /** Packed RGB color for particle and outline-pulse effects (0xRRGGBB). Default: white. */
    public int colorHex = 0xFFFFFF;

    // ── Parsing ──────────────────────────────────────────────────────────────

    public static List<BrandDefinition> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<BrandDefinition> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            out.add(parseRow(row));
        }
        return out;
    }

    private static BrandDefinition parseRow(Map<String, String> row) {
        BrandDefinition d = new BrandDefinition();
        d.brand       = CsvTable.str(row, "brand", null);
        d.name        = CsvTable.str(row, "name", "");
        d.rarity      = CsvTable.dblCell(row, "rarity", 1.0);
        d.description = CsvTable.str(row, "description", "");

        // itemTypes: pipe-separated InventoryCategory names
        String types = CsvTable.str(row, "itemTypes", "");
        if (types != null && !types.isEmpty()) {
            for (String tok : types.split("\\|")) {
                tok = tok.trim();
                if (tok.isEmpty()) continue;
                try {
                    d.itemTypes.add(Item.InventoryCategory.valueOf(tok));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        d.accuracy    = CsvTable.intCell(row, "accuracy",    0);
        d.evasion     = CsvTable.intCell(row, "evasion",     0);
        d.damage      = CsvTable.intCell(row, "damage",      0);
        d.armor       = CsvTable.intCell(row, "armor",       0);
        d.antimagic   = CsvTable.intCell(row, "antimagic",   0);
        d.moveSpeed   = CsvTable.dblCell(row, "moveSpeed",   1.0);
        d.attackSpeed = CsvTable.dblCell(row, "attackSpeed", 1.0);
        d.knockback   = CsvTable.intCell(row, "knockback",   0);

        d.element     = CsvTable.enumCell(row, "element", Item.ItemEffect.class, null);
        d.elementpower = CsvTable.intCell(row, "elementpower", 1);

        d.resistFire   = CsvTable.intCell(row, "resistFire",   0) != 0;
        d.resistPoison = CsvTable.intCell(row, "resistPoison", 0) != 0;
        d.sorcery      = CsvTable.intCell(row, "sorcery",      0) != 0;

        String colorStr = CsvTable.str(row, "color", "");
        if (colorStr != null && !colorStr.isEmpty()) {
            String hex = colorStr.startsWith("#") ? colorStr.substring(1) : colorStr;
            try { d.colorHex = Integer.parseInt(hex, 16); } catch (NumberFormatException ignored) {}
        }

        return d;
    }
}
