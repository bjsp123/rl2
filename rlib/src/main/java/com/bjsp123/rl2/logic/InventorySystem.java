package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;

/**
 * Algorithms that operate on an {@link Inventory}. The data class itself is a
 * pure POJO — every mutation that involves stack-merging, position resolution,
 * or capacity checks lives here. Mirrors the way {@code MobSystem} relates to
 * {@link com.bjsp123.rl2.model.Mob}: storage in {@code model/}, behaviour in
 * {@code logic/}.
 *
 * <p>Bag capacity is {@link GameBalance#INVENTORY_BAG_SIZE}; multi-position
 * routing for {@link InventoryCategory#AMULET} (2 positions) and
 * {@link InventoryCategory#GEM} (3) lives in {@link #resolveEquipIndex}.
 */
public final class InventorySystem {

    private InventorySystem() {}

    /**
     * Add {@code it} to {@code inv}'s bag. If an existing entry has a matching
     * stack key ({@link Item#matchesStackKey}), {@code it}'s count merges into
     * it instead of occupying a new bag slot. Returns {@code true} on success,
     * {@code false} when the bag is full and no existing stack can absorb the
     * addition.
     */
    public static boolean addToBag(Inventory inv, Item it) {
        if (inv == null || it == null) return false;
        for (Item existing : inv.bag) {
            if (existing.matchesStackKey(it)) {
                existing.count += Math.max(1, it.count);
                return true;
            }
        }
        if (inv.bag.size() >= GameBalance.INVENTORY_BAG_SIZE) return false;
        inv.bag.add(it);
        return true;
    }

    /** Decrement {@code it}'s stack count by 1, removing it from the bag
     *  entirely if the count hits 0. Returns {@code true} if the item was
     *  found in the bag (and the decrement / remove happened). Equipped
     *  items are not touched here. */
    public static boolean removeOneFromBag(Inventory inv, Item it) {
        if (inv == null || it == null) return false;
        int idx = -1;
        for (int i = 0; i < inv.bag.size(); i++) {
            if (inv.bag.get(i) == it) { idx = i; break; }
        }
        if (idx < 0) return false;
        Item entry = inv.bag.get(idx);
        if (entry.count > 1) {
            entry.count--;
        } else {
            inv.bag.remove(idx);
        }
        return true;
    }

    /**
     * Move {@code it} from the bag into the appropriate equipment position,
     * routing on {@link Item#inventoryCategory}. Multi-position categories
     * (amulets, gems) land in the first empty position of their family, else
     * replace the canonical position 0. Single-position categories (weapon,
     * offhand, armor) swap with whatever's there. Stack-aware: an equipped
     * item is always count-1, so equipping out of a stack of N peels one off
     * and leaves N-1 in the bag.
     */
    public static boolean equip(Inventory inv, Item it) {
        if (inv == null || it == null || !it.isEquippable()) return false;
        InventoryCategory cat = it.inventoryCategory;
        int targetIndex = resolveEquipIndex(inv, cat);
        if (targetIndex < 0) return false;
        Item current = inv.equipped(cat, targetIndex);
        Item toEquip = it;
        if (it.count > 1 && inv.bag.contains(it)) {
            toEquip = splitOneOff(inv, it);
        } else {
            inv.bag.remove(it);
        }
        inv.setEquipped(cat, targetIndex, toEquip);
        // Anything previously equipped goes back to the bag, merging into a
        // matching stack if one exists.
        if (current != null) {
            current.count = Math.max(1, current.count);
            mergeOrAdd(inv, current);
        }
        return true;
    }

    /** Peel a fresh count-1 entry off an existing bag stack {@code stack},
     *  decrementing the original by 1. Returns the new singleton (suitable
     *  for putting in an equipment slot). */
    private static Item splitOneOff(Inventory inv, Item stack) {
        stack.count = Math.max(1, stack.count) - 1;
        Item one = shallowSingleton(stack);
        if (stack.count <= 0) inv.bag.remove(stack);
        return one;
    }

    /** Build a count-1 copy of {@code src} suitable for equipping. Most fields
     *  are shared by reference (immutable strings, enums); transients reset. */
    private static Item shallowSingleton(Item src) {
        Item out = new Item();
        out.type = src.type;
        out.material = src.material;
        out.name = src.name;
        out.description = src.description;
        out.damage = src.damage;
        out.damagePerLevel = src.damagePerLevel;
        out.armor = src.armor;
        out.armorPerLevel = src.armorPerLevel;
        out.lightRadius = src.lightRadius;
        out.foodValue = src.foodValue;
        out.tilesAffected = src.tilesAffected;
        out.tilesAffectedPerLevel = src.tilesAffectedPerLevel;
        out.throwEffect = src.throwEffect;
        out.useBehavior = src.useBehavior;
        out.wandEffect = src.wandEffect;
        out.useVerb = src.useVerb;
        out.level = src.level;
        out.tameOnThrow = src.tameOnThrow;
        out.gemSpecies = src.gemSpecies;
        out.gemSize = src.gemSize;
        out.inventoryCategory = src.inventoryCategory;
        out.silhouetteForCategory = src.silhouetteForCategory;
        out.count = 1;
        return out;
    }

    /** Add {@code it} to the bag, merging into a matching stack if one exists. */
    private static void mergeOrAdd(Inventory inv, Item it) {
        for (Item existing : inv.bag) {
            if (existing.matchesStackKey(it)) {
                existing.count += Math.max(1, it.count);
                return;
            }
        }
        inv.bag.add(it);
    }

    /** Index within {@code category}'s position array that should receive an
     *  incoming equip. Single-position categories return 0. Multi-position
     *  categories return the lowest empty position; if all are full, return 0
     *  (the canonical swap target). Returns {@code -1} for non-equipment. */
    private static int resolveEquipIndex(Inventory inv, InventoryCategory category) {
        if (category == null || !category.isEquipment()) return -1;
        int n = Inventory.positionCount(category);
        for (int i = 0; i < n; i++) {
            if (inv.equipped(category, i) == null) return i;
        }
        return 0;
    }
}
