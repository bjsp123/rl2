package com.bjsp123.rl2.model;

import com.bjsp123.rl2.model.Item.InventoryCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-mob inventory: a bag (variable-size stack list) plus equipment slots
 * keyed by {@link InventoryCategory}. Pure data - every algorithmic operation
 * (add-to-bag with stack-merge, equip with slot-resolution + stack-split, etc.)
 * lives in {@code com.bjsp123.rl2.logic.InventorySystem}.
 *
 * <h3>Slot model</h3>
 * Single-position categories ({@link InventoryCategory#WEAPON},
 * {@link InventoryCategory#OFFHAND}, {@link InventoryCategory#ARMOR}) live in
 * direct fields. Multi-position categories ({@link InventoryCategory#AMULET}
 * occupies 2; {@link InventoryCategory#GEM} occupies 3) are arrays addressed
 * by zero-based index. Non-equipment categories never appear here.
 */
public class Inventory {

    /** Loose items the mob is carrying. Each entry is one stack; stack size lives
     *  on {@link Item#count}. Capacity is enforced per category group (equipment,
     *  gems, food, items) by {@link com.bjsp123.rl2.logic.InventorySystem#bagLimitFor}. */
    public final List<Item> bag = new ArrayList<>();

    public Item weapon;
    public Item offhand;
    public Item armor;
    /** Two amulet positions. Equip prefers {@code [0]} when free, falls back to
     *  {@code [1]}. */
    public final Item[] amulets = new Item[2];
    /** Three gem positions. Equip prefers the first free slot. */
    public final Item[] gems    = new Item[3];

    /** Number of positions for {@code category} - 1 for single-position
     *  equipment, 2 for amulets, 3 for gems, 0 for non-equipment categories. */
    public static int positionCount(InventoryCategory category) {
        if (category == null) return 0;
        return switch (category) {
            case WEAPON, OFFHAND, ARMOR, TOOL -> 1;
            case AMULET -> 2;
            case GEM    -> 3;
            case POTION, WAND, FOOD, ORB, BOMB, ITEM -> 0;
        };
    }

    /** Item currently equipped at {@code (category, index)}, or {@code null}
     *  when the slot is empty / category is non-equipment / index is out of
     *  range. */
    public Item equipped(InventoryCategory category, int index) {
        if (category == null) return null;
        return switch (category) {
            case WEAPON  -> index == 0 ? weapon  : null;
            case OFFHAND -> index == 0 ? offhand : null;
            case ARMOR   -> index == 0 ? armor   : null;
            case AMULET  -> index >= 0 && index < amulets.length ? amulets[index] : null;
            case GEM     -> index >= 0 && index < gems.length    ? gems[index]    : null;
            case POTION, WAND, FOOD, ORB, BOMB, ITEM, TOOL-> null;
        };
    }

    /** Set the item at {@code (category, index)}. Out-of-range / non-equipment
     *  categories are silently ignored. */
    public void setEquipped(InventoryCategory category, int index, Item it) {
        if (category == null) return;
        switch (category) {
            case WEAPON  -> { if (index == 0) weapon  = it; }
            case OFFHAND -> { if (index == 0) offhand = it; }
            case ARMOR   -> { if (index == 0) armor   = it; }
            case AMULET  -> { if (index >= 0 && index < amulets.length) amulets[index] = it; }
            case GEM     -> { if (index >= 0 && index < gems.length)    gems[index]    = it; }
            case POTION, WAND, FOOD, ORB, BOMB -> { /* not equippable */ }
        }
    }

    /** Snapshot of every currently-equipped item across all positions. Used by
     *  stat-bucketing ({@code MobSystem.writeEffectiveStats}) and look-ups that
     *  need to iterate gear without caring which position items occupy. */
    public List<Item> allEquipped() {
        List<Item> out = new ArrayList<>(8);
        if (weapon  != null) out.add(weapon);
        if (offhand != null) out.add(offhand);
        if (armor   != null) out.add(armor);
        for (Item a : amulets) if (a != null) out.add(a);
        for (Item g : gems)    if (g != null) out.add(g);
        return out;
    }
}
