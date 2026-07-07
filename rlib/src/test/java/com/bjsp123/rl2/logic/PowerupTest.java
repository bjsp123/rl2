package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemSystem#powerupWouldApply} must stay in lockstep with
 * {@link ItemSystem#applyPowerup}: the AI / auto-explore planners use the
 * predicate to decide whether a walk-over pill is worth a detour, and a pill
 * that the predicate calls useful but the engine refuses to consume gets
 * re-targeted forever (the RL auto-explore livelock this guards against).
 */
class PowerupTest extends DataFixture {

    private static Mob freshPlayer() {
        return MobFactory.player(new Point(0, 0), Mob.CharacterClass.WARRIOR);
    }

    // -- HP_UP ----------------------------------------------------------------

    @Test
    void hpPillAppliesWhenHurt() {
        Mob p = freshPlayer();
        p.hp = p.effectiveStats().maxHp / 2;
        Item pill = ItemFactory.build("HEALTHPILL");
        assertTrue(ItemSystem.powerupWouldApply(p, pill));
        assertTrue(ItemSystem.applyPowerup(null, p, pill));
    }

    @Test
    void hpPillRefusedAtFullHp() {
        Mob p = freshPlayer();
        p.hp = p.effectiveStats().maxHp;
        Item pill = ItemFactory.build("HEALTHPILL");
        assertFalse(ItemSystem.powerupWouldApply(p, pill));
        assertFalse(ItemSystem.applyPowerup(null, p, pill));
    }

    /** The drift case that livelocked auto-explore: hp a rounding-hair below max
     *  reads as "hurt" to a naive {@code hp < maxHp} check, but the engine rounds
     *  the heal to 0 and leaves the pill. Predicate and engine must agree. */
    @Test
    void hpPillPredicateMatchesEngineWithinRoundingOfFull() {
        Mob p = freshPlayer();
        p.hp = p.effectiveStats().maxHp - 0.4;
        Item pill = ItemFactory.build("HEALTHPILL");
        assertEquals(ItemSystem.applyPowerup(null, freshHalfCopy(p), pill),
                ItemSystem.powerupWouldApply(p, pill));
    }

    private static Mob freshHalfCopy(Mob src) {
        Mob copy = freshPlayer();
        copy.hp = src.hp;
        return copy;
    }

    // -- MANA_UP --------------------------------------------------------------

    @Test
    void chargePillRefusedWithNothingToRecharge() {
        Mob p = freshPlayer();
        p.inventory.bag.clear();
        Item pill = ItemFactory.build("CHARGEPILL");
        assertFalse(ItemSystem.powerupWouldApply(p, pill));
        assertFalse(ItemSystem.applyPowerup(null, p, pill));
    }

    @Test
    void chargePillAppliesWithDrainedWandInBag() {
        Mob p = freshPlayer();
        p.inventory.bag.clear();
        Item wand = ItemFactory.build("WAND_FIRE");
        wand.charge = 0f;
        p.inventory.bag.add(wand);
        Item pill = ItemFactory.build("CHARGEPILL");
        assertTrue(ItemSystem.powerupWouldApply(p, pill));
        assertTrue(ItemSystem.applyPowerup(null, p, pill));
        assertTrue(wand.charge > 0f);
    }

    @Test
    void chargePillRefusedWhenEverythingIsFull() {
        Mob p = freshPlayer();
        p.inventory.bag.clear();
        Item wand = ItemFactory.build("WAND_FIRE");
        wand.charge = ItemStats.effectiveMaxCharge(wand, ItemStats.effectiveLevel(wand, p));
        p.inventory.bag.add(wand);
        Item pill = ItemFactory.build("CHARGEPILL");
        assertFalse(ItemSystem.powerupWouldApply(p, pill));
        assertFalse(ItemSystem.applyPowerup(null, p, pill));
    }

    /** Equipped items count too - absorbManaPills tops up equipped slots, so the
     *  predicate must see a drained EQUIPPED wand as a reason to grab the pill. */
    @Test
    void chargePillAppliesWithDrainedEquippedItem() {
        Mob p = freshPlayer();
        p.inventory.bag.clear();
        // Planted directly in an equip slot - allEquipped() is what both
        // absorbManaPills and the predicate walk, which is the path under test.
        Item wand = ItemFactory.build("WAND_FIRE");
        wand.charge = 0f;
        p.inventory.weapon = wand;
        Item pill = ItemFactory.build("CHARGEPILL");
        assertTrue(ItemSystem.powerupWouldApply(p, pill));
        assertTrue(ItemSystem.applyPowerup(null, p, pill));
        assertTrue(wand.charge > 0f);
    }

    // -- LEVEL_UP -------------------------------------------------------------

    @Test
    void xpPillAlwaysApplies() {
        Mob p = freshPlayer();
        Item pill = ItemFactory.build("XPPILL");
        assertTrue(ItemSystem.powerupWouldApply(p, pill));
        assertTrue(ItemSystem.applyPowerup(null, p, pill));
    }
}
