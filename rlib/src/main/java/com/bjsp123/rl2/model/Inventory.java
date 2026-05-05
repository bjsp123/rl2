package com.bjsp123.rl2.model;

import com.bjsp123.rl2.model.Item.ItemSlot;

import java.util.ArrayList;
import java.util.List;

public class Inventory {
    /** 6 × 6 = 36-slot bag (one cell per item in the UI grid). */
    public static final int BAG_SIZE = 36;

    public final List<Item> bag = new ArrayList<>();
    public Item weapon, offhand, armor, ring1, ring2, amulet;
    /** Equipped gems. Same model as rings — multi-slot, items tagged with the canonical
     *  {@link ItemSlot#GEM1} land in the first empty gem slot or swap GEM1 out when full. */
    public Item gem1, gem2, gem3;

    /**
     * Add {@code it} to the bag. If an existing bag entry has a matching stack key
     * ({@link Item#matchesStackKey}), {@code it}'s count merges into it instead of
     * occupying a new bag slot. Returns true on success, false if the bag is full and
     * no existing stack can absorb the addition.
     */
    public boolean addToBag(Item it) {
        if (it == null) return false;
        for (Item existing : bag) {
            if (existing.matchesStackKey(it)) {
                existing.count += Math.max(1, it.count);
                return true;
            }
        }
        if (bag.size() >= BAG_SIZE) return false;
        bag.add(it);
        return true;
    }

    /** Decrement {@code it}'s stack count by 1, removing it from the bag entirely if
     *  the count hits 0. Returns true if the item was found in the bag (and the
     *  decrement / remove happened). Equipped items are not touched here — for those
     *  use {@link #unequip}. */
    public boolean removeOneFromBag(Item it) {
        if (it == null) return false;
        int idx = -1;
        for (int i = 0; i < bag.size(); i++) {
            if (bag.get(i) == it) { idx = i; break; }
        }
        if (idx < 0) return false;
        Item entry = bag.get(idx);
        if (entry.count > 1) {
            entry.count--;
        } else {
            bag.remove(idx);
        }
        return true;
    }

    public Item equipped(ItemSlot s) {
        return switch (s) {
            case WEAPON  -> weapon;
            case OFFHAND -> offhand;
            case ARMOR   -> armor;
            case RING1   -> ring1;
            case RING2   -> ring2;
            case AMULET  -> amulet;
            case GEM1    -> gem1;
            case GEM2    -> gem2;
            case GEM3    -> gem3;
        };
    }

    public void setEquipped(ItemSlot s, Item it) {
        switch (s) {
            case WEAPON  -> weapon  = it;
            case OFFHAND -> offhand = it;
            case ARMOR   -> armor   = it;
            case RING1   -> ring1   = it;
            case RING2   -> ring2   = it;
            case AMULET  -> amulet  = it;
            case GEM1    -> gem1    = it;
            case GEM2    -> gem2    = it;
            case GEM3    -> gem3    = it;
        }
    }

    /**
     * Move item from bag into the appropriate slot. Multi-slot kinds (rings, gems) land
     * in the first empty slot of their family, else replace the canonical slot (RING1 /
     * GEM1); single-slot kinds swap with whatever's in their slot.
     */
    public boolean equip(Item it) {
        if (it.slot == null) return false;
        ItemSlot target = resolveTargetSlot(it);
        if (target == null) return false;
        Item current = equipped(target);
        // Stack-aware equip — if the bag entry is a stack of N, peel one off into the
        // slot and leave the rest in the bag rather than equipping the whole stack.
        Item toEquip = it;
        if (it.count > 1 && bag.contains(it)) {
            toEquip = splitOneOff(it);
        } else {
            bag.remove(it);
        }
        setEquipped(target, toEquip);
        // Anything previously equipped goes back to the bag, merging into a matching
        // stack if one exists.
        if (current != null) {
            current.count = Math.max(1, current.count);
            mergeOrAdd(current);
        }
        return true;
    }

    /** Peel a fresh count-1 entry off an existing bag stack {@code stack}, decrementing
     *  the original by 1. Returns the new singleton (suitable for putting in an
     *  equipment slot or another data structure). */
    private Item splitOneOff(Item stack) {
        stack.count = Math.max(1, stack.count) - 1;
        Item one = shallowSingleton(stack);
        if (stack.count <= 0) bag.remove(stack);
        return one;
    }

    /** Build a count-1 copy of {@code src} suitable for equipping. We share most fields
     *  by reference (immutable strings, enums); transients reset. */
    private static Item shallowSingleton(Item src) {
        Item out = new Item();
        out.type = src.type;
        out.slot = src.slot;
        out.material = src.material;
        out.name = src.name;
        out.description = src.description;
        out.damageMin = src.damageMin;
        out.damageMax = src.damageMax;
        out.armorMin = src.armorMin;
        out.armorMax = src.armorMax;
        out.lightRadius = src.lightRadius;
        out.foodValue = src.foodValue;
        out.thrownBehavior = src.thrownBehavior;
        out.useBehavior = src.useBehavior;
        out.healAmount = src.healAmount;
        out.wandElement = src.wandElement;
        out.useVerb = src.useVerb;
        out.level = src.level;
        out.tameOnThrow = src.tameOnThrow;
        out.gemSpecies = src.gemSpecies;
        out.gemSize = src.gemSize;
        out.count = 1;
        return out;
    }

    /** Add {@code it} to the bag, merging into a matching stack if one exists. */
    private void mergeOrAdd(Item it) {
        for (Item existing : bag) {
            if (existing.matchesStackKey(it)) {
                existing.count += Math.max(1, it.count);
                return;
            }
        }
        bag.add(it);
    }

    /** Move item from slot to bag. Fails if bag is full. */
    public boolean unequip(ItemSlot s) {
        Item it = equipped(s);
        if (it == null) return false;
        if (bag.size() >= BAG_SIZE) return false;
        setEquipped(s, null);
        bag.add(it);
        return true;
    }

    private ItemSlot resolveTargetSlot(Item it) {
        if (ItemSlot.isRing(it.slot)) {
            if (ring1 == null) return ItemSlot.RING1;
            if (ring2 == null) return ItemSlot.RING2;
            return ItemSlot.RING1; // both full — swap RING1 out
        }
        if (ItemSlot.isGem(it.slot)) {
            if (gem1 == null) return ItemSlot.GEM1;
            if (gem2 == null) return ItemSlot.GEM2;
            if (gem3 == null) return ItemSlot.GEM3;
            return ItemSlot.GEM1; // all full — swap the oldest (GEM1) out
        }
        return it.slot;
    }
}
