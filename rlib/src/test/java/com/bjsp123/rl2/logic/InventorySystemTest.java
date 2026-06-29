package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InventorySystem} - bag add / stack-merge / capacity,
 * equip / unequip / slot routing, and removal. Covers the documented invariants
 * (only POTION/BOMB/FOOD/THROWN stack; equipment never stacks; per-group caps)
 * and the null / over-capacity / wrong-category unhappy paths.
 */
class InventorySystemTest {

    private static Item item(InventoryCategory cat, String type, int level) {
        Item it = new Item();
        it.inventoryCategory = cat;
        it.type = type;
        it.name = type;
        it.level = level;
        it.count = 1;
        return it;
    }

    // -- addToBag: happy -----------------------------------------------------

    @Test
    void addToBagStoresItem() {
        Inventory inv = new Inventory();
        assertTrue(InventorySystem.addToBag(inv, item(InventoryCategory.WEAPON, "SWORD", 0)));
        assertEquals(1, inv.bag.size());
    }

    @Test
    void stackableItemsMergeIntoOneEntry() {
        Inventory inv = new Inventory();
        InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "HEAL", 0));
        InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "HEAL", 0));
        assertEquals(1, inv.bag.size());
        assertEquals(2, inv.bag.get(0).count);
    }

    @Test
    void stacksDoNotMergeAcrossDifferentLevels() {
        Inventory inv = new Inventory();
        InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "HEAL", 0));
        InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "HEAL", 1));
        assertEquals(2, inv.bag.size());
    }

    @Test
    void nonStackableItemsTakeSeparateSlots() {
        Inventory inv = new Inventory();
        InventorySystem.addToBag(inv, item(InventoryCategory.WEAPON, "SWORD", 0));
        InventorySystem.addToBag(inv, item(InventoryCategory.WEAPON, "SWORD", 0));
        assertEquals(2, inv.bag.size());
    }

    // -- addToBag: unhappy / capacity ----------------------------------------

    @Test
    void addToBagRejectsNulls() {
        Inventory inv = new Inventory();
        assertFalse(InventorySystem.addToBag(null, item(InventoryCategory.WEAPON, "SWORD", 0)));
        assertFalse(InventorySystem.addToBag(inv, null));
    }

    @Test
    void addToBagRefusesWhenCategoryGroupFull() {
        Inventory inv = new Inventory();
        int limit = InventorySystem.bagLimitFor(InventoryCategory.WEAPON);
        // Fill the equipment group to the cap with non-stackable singletons.
        for (int i = 0; i < limit; i++) {
            assertTrue(InventorySystem.addToBag(inv, item(InventoryCategory.WEAPON, "SWORD" + i, 0)));
        }
        // One more must be refused.
        assertFalse(InventorySystem.addToBag(inv, item(InventoryCategory.WEAPON, "OVERFLOW", 0)));
    }

    @Test
    void overCapacityStillMergesIntoExistingStack() {
        // Even when the consumable group is at its slot cap, an item that
        // matches an existing stack merges rather than being refused.
        Inventory inv = new Inventory();
        int limit = InventorySystem.bagLimitFor(InventoryCategory.POTION);
        for (int i = 0; i < limit; i++) {
            assertTrue(InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "P" + i, 0)));
        }
        // Group is full; a brand-new type is refused...
        assertFalse(InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "NEWTYPE", 0)));
        // ...but a duplicate of an existing stack merges.
        assertTrue(InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "P0", 0)));
    }

    // -- equip / unequip: happy ----------------------------------------------

    @Test
    void equipMovesItemFromBagToSlot() {
        Inventory inv = new Inventory();
        Item sword = item(InventoryCategory.WEAPON, "SWORD", 0);
        InventorySystem.addToBag(inv, sword);
        assertTrue(InventorySystem.equip(inv, sword));
        assertSame(sword, inv.weapon);
        assertTrue(inv.bag.isEmpty());
        assertTrue(InventorySystem.isEquipped(inv, sword));
    }

    @Test
    void equippingSecondWeaponReturnsFirstToBag() {
        Inventory inv = new Inventory();
        Item a = item(InventoryCategory.WEAPON, "AXE", 0);
        Item b = item(InventoryCategory.WEAPON, "MACE", 0);
        InventorySystem.addToBag(inv, a);
        InventorySystem.equip(inv, a);
        InventorySystem.addToBag(inv, b);
        InventorySystem.equip(inv, b);
        assertSame(b, inv.weapon);
        assertTrue(inv.bag.contains(a));
    }

    @Test
    void amuletsFillBothPositions() {
        Inventory inv = new Inventory();
        Item a1 = item(InventoryCategory.AMULET, "RING_A", 0);
        Item a2 = item(InventoryCategory.AMULET, "RING_B", 0);
        InventorySystem.addToBag(inv, a1);
        InventorySystem.addToBag(inv, a2);
        InventorySystem.equip(inv, a1);
        InventorySystem.equip(inv, a2);
        assertSame(a1, inv.amulets[0]);
        assertSame(a2, inv.amulets[1]);
    }

    @Test
    void unequipReturnsItemToBag() {
        Inventory inv = new Inventory();
        Item armor = item(InventoryCategory.ARMOR, "MAIL", 0);
        InventorySystem.addToBag(inv, armor);
        InventorySystem.equip(inv, armor);
        assertSame(armor, inv.armor);
        assertTrue(InventorySystem.unequip(inv, armor));
        assertNull(inv.armor);
        assertTrue(inv.bag.contains(armor));
    }

    // -- equip / unequip: unhappy --------------------------------------------

    @Test
    void equipRejectsNonEquippableItem() {
        Inventory inv = new Inventory();
        Item potion = item(InventoryCategory.POTION, "HEAL", 0);
        InventorySystem.addToBag(inv, potion);
        assertFalse(InventorySystem.equip(inv, potion));
        assertTrue(inv.bag.contains(potion));
    }

    @Test
    void equipRejectsNulls() {
        Inventory inv = new Inventory();
        assertFalse(InventorySystem.equip(null, item(InventoryCategory.WEAPON, "SWORD", 0)));
        assertFalse(InventorySystem.equip(inv, null));
    }

    @Test
    void unequipRejectsItemThatIsNotEquipped() {
        Inventory inv = new Inventory();
        Item sword = item(InventoryCategory.WEAPON, "SWORD", 0);
        InventorySystem.addToBag(inv, sword); // in bag, never equipped
        assertFalse(InventorySystem.unequip(inv, sword));
    }

    @Test
    void unequipRejectsNonEquipmentCategory() {
        Inventory inv = new Inventory();
        Item potion = item(InventoryCategory.POTION, "HEAL", 0);
        assertFalse(InventorySystem.unequip(inv, potion));
    }

    // -- removal -------------------------------------------------------------

    @Test
    void removeOneDecrementsStackThenRemoves() {
        Inventory inv = new Inventory();
        Item p = item(InventoryCategory.POTION, "HEAL", 0);
        InventorySystem.addToBag(inv, p);
        InventorySystem.addToBag(inv, item(InventoryCategory.POTION, "HEAL", 0)); // merges -> count 2
        assertTrue(InventorySystem.removeOneFromBag(inv, inv.bag.get(0)));
        assertEquals(1, inv.bag.get(0).count);
        assertTrue(InventorySystem.removeOneFromBag(inv, inv.bag.get(0)));
        assertTrue(inv.bag.isEmpty());
    }

    @Test
    void removeOneRejectsItemNotInBag() {
        Inventory inv = new Inventory();
        assertFalse(InventorySystem.removeOneFromBag(inv, item(InventoryCategory.POTION, "HEAL", 0)));
        assertFalse(InventorySystem.removeOneFromBag(null, item(InventoryCategory.POTION, "HEAL", 0)));
    }

    @Test
    void removeEntirelyClearsBagOrSlot() {
        Inventory inv = new Inventory();
        Item bagItem = item(InventoryCategory.WAND, "WAND_FIRE", 0);
        InventorySystem.addToBag(inv, bagItem);
        assertTrue(InventorySystem.removeEntirely(inv, bagItem));
        assertTrue(inv.bag.isEmpty());

        Item equipped = item(InventoryCategory.WEAPON, "SWORD", 0);
        InventorySystem.addToBag(inv, equipped);
        InventorySystem.equip(inv, equipped);
        assertTrue(InventorySystem.removeEntirely(inv, equipped));
        assertNull(inv.weapon);
    }

    @Test
    void removeEntirelyRejectsAbsentItem() {
        Inventory inv = new Inventory();
        assertFalse(InventorySystem.removeEntirely(inv, item(InventoryCategory.WEAPON, "GHOST", 0)));
        assertFalse(InventorySystem.removeEntirely(null, item(InventoryCategory.WEAPON, "GHOST", 0)));
    }

    @Test
    void leastValuableInGroupPicksLowestValue() {
        Inventory inv = new Inventory();
        Item cheap = item(InventoryCategory.WEAPON, "STICK", 0);   // value 0
        Item dear  = item(InventoryCategory.WEAPON, "BLADE", 5);   // value > 0 (level)
        InventorySystem.addToBag(inv, dear);
        InventorySystem.addToBag(inv, cheap);
        assertSame(cheap, InventorySystem.leastValuableInGroup(inv, InventoryCategory.WEAPON));
        assertNull(InventorySystem.leastValuableInGroup(new Inventory(), InventoryCategory.WEAPON));
    }
}
