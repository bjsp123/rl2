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
 * <p>The bag is divided into four capacity groups, each capped independently by
 * a constant in {@link GameBalance}: equipment ({@code BAG_EQUIPMENT_SIZE}),
 * gems ({@code BAG_GEMS_SIZE}), food ({@code BAG_FOOD_SIZE}), and all other
 * consumables / tools ({@code BAG_ITEMS_SIZE}).
 *
 * <p>Only {@link InventoryCategory#POTION}, {@link InventoryCategory#BOMB}, and
 * {@link InventoryCategory#FOOD} items can merge into stacks; all other item
 * types are always singletons.
 *
 * <p>Multi-position equipment routing for {@link InventoryCategory#AMULET}
 * (2 positions) and {@link InventoryCategory#GEM} (3) lives in
 * {@link #resolveEquipIndex}.
 */
public final class InventorySystem {

    private InventorySystem() {}

    // ── Capacity groups ───────────────────────────────────────────────────────

    /** The four bag-capacity buckets that partition every {@link InventoryCategory}. */
    private enum BagGroup { EQUIPMENT, GEMS, FOOD, ITEMS }

    private static BagGroup groupOf(InventoryCategory cat) {
        if (cat == null) return BagGroup.ITEMS;
        return switch (cat) {
            case WEAPON, OFFHAND, ARMOR, AMULET, WAND, ITEM, TOOL -> BagGroup.EQUIPMENT;
            case GEM                                         -> BagGroup.GEMS;
            case FOOD                                        -> BagGroup.FOOD;
            case POTION, BOMB, ORB                           -> BagGroup.ITEMS;
        };
    }

    /** Bag slot limit that applies to items in the same capacity group as {@code cat}. */
    public static int bagLimitFor(InventoryCategory cat) {
        return switch (groupOf(cat)) {
            case EQUIPMENT -> GameBalance.BAG_EQUIPMENT_SIZE;
            case GEMS      -> GameBalance.BAG_GEMS_SIZE;
            case FOOD      -> GameBalance.BAG_FOOD_SIZE;
            case ITEMS     -> GameBalance.BAG_ITEMS_SIZE;
        };
    }

    /** Number of bag entries currently occupying the same capacity group as {@code cat}. */
    private static int bagCountFor(Inventory inv, InventoryCategory cat) {
        BagGroup target = groupOf(cat);
        int n = 0;
        for (Item it : inv.bag) {
            if (groupOf(it.inventoryCategory) == target) n++;
        }
        return n;
    }

    /**
     * Add {@code it} to {@code inv}'s bag. Stackable items (potions, bombs, food)
     * merge into an existing stack with a matching key instead of occupying a new
     * slot. Returns {@code true} on success, {@code false} when the category's
     * capacity is full and no existing stack can absorb the addition.
     */
    public static boolean addToBag(Inventory inv, Item it) {
        if (inv == null || it == null) return false;
        if (it.isStackable()) {
            for (Item existing : inv.bag) {
                if (existing.matchesStackKey(it)) {
                    existing.count += Math.max(1, it.count);
                    return true;
                }
            }
        }
        if (bagCountFor(inv, it.inventoryCategory) >= bagLimitFor(it.inventoryCategory)) return false;
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

    /** Move {@code it} from its equipment slot back into the bag. No-op
     *  when {@code it} isn't currently equipped or when the bag has no
     *  room for another stack (and merging into an existing matching
     *  stack isn't possible — though for typical equipment that case is
     *  vanishingly rare since equipped items always carry count == 1).
     *  Returns {@code true} on successful unequip. */
    public static boolean unequip(Inventory inv, Item it) {
        if (inv == null || it == null) return false;
        InventoryCategory cat = it.inventoryCategory;
        if (cat == null || !cat.isEquipment()) return false;
        int slot = findEquippedSlot(inv, it);
        if (slot < 0) return false;
        // Capacity check — refuse the unequip if the category is full and no
        // matching stack can absorb it. Equipment is never stackable, so the
        // canAbsorb path only matters for stackable items unequipped by mistake.
        boolean canAbsorb = false;
        if (it.isStackable()) {
            for (Item existing : inv.bag) {
                if (existing.matchesStackKey(it)) { canAbsorb = true; break; }
            }
        }
        if (!canAbsorb && bagCountFor(inv, it.inventoryCategory) >= bagLimitFor(it.inventoryCategory)) {
            return false;
        }
        inv.setEquipped(cat, slot, null);
        it.count = Math.max(1, it.count);
        mergeOrAdd(inv, it);
        return true;
    }

    /** {@code true} when {@code it} is currently sitting in one of
     *  {@code inv}'s equipment positions. Used by UI surfaces that need
     *  to render Equip vs. Unequip on the same affordance. */
    public static boolean isEquipped(Inventory inv, Item it) {
        return findEquippedSlot(inv, it) >= 0;
    }

    /** Index within {@code it}'s category's position array where it is
     *  currently equipped, or {@code -1} when not equipped. Single-slot
     *  categories return {@code 0}. */
    private static int findEquippedSlot(Inventory inv, Item it) {
        if (inv == null || it == null) return -1;
        InventoryCategory cat = it.inventoryCategory;
        if (cat == null || !cat.isEquipment()) return -1;
        int n = Inventory.positionCount(cat);
        for (int i = 0; i < n; i++) {
            if (inv.equipped(cat, i) == it) return i;
        }
        return -1;
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
        out.count = 1;
        return out;
    }

    /** Add {@code it} to the bag, merging into a matching stack if the item is
     *  stackable and a match exists. No capacity check — only called after the
     *  caller has already verified there is room (or for equipped→bag returns
     *  where item loss would be worse than overflow). */
    private static void mergeOrAdd(Inventory inv, Item it) {
        if (it.isStackable()) {
            for (Item existing : inv.bag) {
                if (existing.matchesStackKey(it)) {
                    existing.count += Math.max(1, it.count);
                    return;
                }
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
