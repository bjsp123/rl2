package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.util.ArenaHarness;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Thrown items and wand shots must follow the animation-gated lifecycle:
 * inventory/charge and action cost are billed at fire time, the impact tile is
 * locked at fire time, the world-state mutation is queued via
 * {@link MobSystem#queuePendingImpact}, and the pending-impact gate guarantees
 * it resolves before any other mob's brain runs. These tests pin the
 * historical "warrior bomb dud" / wand-fizzle regression: routing the gameplay
 * mutation through the rgame Animator without the freeze gives defenders free
 * ticks to step out of the AoE. See the {@code MobSystem.throwItem} javadoc.
 */
class ThrowAndWandLifecycleTest extends DataFixture {

    private static final Point THROWER_AT = new Point(3, 8);
    private static final Point VICTIM_AT  = new Point(6, 8);

    /** First damaging bomb in items.csv - selected by stats, not by name. */
    private static Item damagingBomb() {
        List<String> types = Registries.itemTypesMatching(d ->
                d.inventoryCategory == Item.InventoryCategory.BOMB && d.damage > 0);
        return types.isEmpty() ? null : ItemFactory.build(types.get(0));
    }

    /** First direct-damage wand - selected by stats, not by name. DETONATION
     *  is the element whose impact deals immediate hp damage; FIRE and the
     *  surface elements mutate tiles instead. */
    private static Item damagingWand() {
        List<String> types = Registries.itemTypesMatching(d ->
                d.useBehavior == Item.UseBehavior.WAND
                        && d.damage > 0
                        && d.wandEffect == Item.ItemEffect.DETONATION);
        return types.isEmpty() ? null : ItemFactory.build(types.get(0));
    }

    private static Mob victimMob() {
        for (String type : ArenaHarness.fightableMobs(false)) {
            Mob m = MobFactory.spawn(type, VICTIM_AT);
            if (m == null) continue;
            boolean dodges = false;
            if (m.abilities != null) {
                for (Mob.MobAbility ab : m.abilities) {
                    if (ab != null && ab.kind == Mob.MobAbility.AbilityKind.WRAITH_DODGE) {
                        dodges = true;
                        break;
                    }
                }
            }
            if (!dodges) return m;
        }
        return null;
    }

    private static Level arenaWith(Mob attacker, Mob victim) {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(7));
        CombatArena.placeMobs(level, List.of(attacker, victim),
                List.of(THROWER_AT, VICTIM_AT));
        CombatArena.seedTeamHostility(List.of(attacker), List.of(victim));
        return level;
    }

    @Test
    void throwBillsAtFireTimeAndQueuesImpact() {
        Mob thrower = MobFactory.player(THROWER_AT, Mob.CharacterClass.WARRIOR);
        Mob victim = victimMob();
        assertNotNull(victim, "no fightable mob species loaded");
        Level level = arenaWith(thrower, victim);
        Item bomb = damagingBomb();
        assertNotNull(bomb, "no damaging BOMB item in items.csv");
        thrower.inventory.bag.add(bomb);
        thrower.ticksTillMove = 0;

        MobSystem.throwItem(level, thrower, bomb, VICTIM_AT);

        // Everything except the world mutation happens synchronously at fire
        // time: item consumed, action cost billed, impact queued behind the gate.
        assertFalse(thrower.inventory.bag.contains(bomb),
                "thrown item must leave the bag at fire time");
        assertTrue(thrower.ticksTillMove > 0,
                "throw action cost must be billed at fire time");
        assertEquals(1, level.pendingImpactCount,
                "throw must queue exactly one pending impact");
    }

    @Test
    void thrownBombImpactDealsDamageHeadless() {
        Mob thrower = MobFactory.player(THROWER_AT, Mob.CharacterClass.WARRIOR);
        Mob victim = victimMob();
        assertNotNull(victim, "no fightable mob species loaded");
        Level level = arenaWith(thrower, victim);
        Item bomb = damagingBomb();
        assertNotNull(bomb, "no damaging BOMB item in items.csv");
        thrower.inventory.bag.add(bomb);
        victim.hp = 1000;
        double before = victim.hp;

        MobSystem.throwItem(level, thrower, bomb, VICTIM_AT);
        MobSystem.drainPendingImpactsImmediate(level);

        // Bombs are AoE with no to-hit roll, so a single drain must land.
        assertEquals(0, level.pendingImpactCount, "drain left the gate up");
        assertTrue(victim.hp < before,
                "thrown bomb dealt no damage headless - the warrior-bomb-dud"
                + " regression (bomb=" + bomb.type + " victim=" + victim.mobType + ")");
    }

    @Test
    void impactTileIsLockedAtFireTime() {
        Mob thrower = MobFactory.player(THROWER_AT, Mob.CharacterClass.WARRIOR);
        Mob victim = victimMob();
        assertNotNull(victim, "no fightable mob species loaded");
        Level level = arenaWith(thrower, victim);
        Item bomb = damagingBomb();
        assertNotNull(bomb, "no damaging BOMB item in items.csv");
        thrower.inventory.bag.add(bomb);
        victim.hp = 1000;
        double before = victim.hp;

        MobSystem.throwItem(level, thrower, bomb, VICTIM_AT);
        // Illegally move the victim far off the locked tile before the drain.
        // In real play the pending-impact freeze forbids exactly this - the
        // test asserts the impact resolves on the fire-time tile regardless.
        victim.position = new Point(12, 3);
        MobSystem.drainPendingImpactsImmediate(level);

        assertEquals(before, victim.hp,
                "impact must resolve at the fire-time-locked tile, not follow the victim");
    }

    @Test
    void fireWandBillsChargeAtFireTimeAndDealsDamage() {
        Mob caster = MobFactory.player(THROWER_AT, Mob.CharacterClass.MAGE);
        Mob victim = victimMob();
        assertNotNull(victim, "no fightable mob species loaded");
        Level level = arenaWith(caster, victim);
        Item wand = damagingWand();
        assertNotNull(wand, "no damaging missile wand in items.csv");
        wand.charge = 500f;
        caster.inventory.bag.add(wand);
        victim.hp = 100000;
        double before = victim.hp;

        ItemSystem.fireWand(level, caster, wand, VICTIM_AT);
        assertEquals(499f, wand.charge, 0.001,
                "wand charge must be spent at fire time");
        assertEquals(1, level.pendingImpactCount,
                "fireWand must queue exactly one pending impact");
        MobSystem.drainPendingImpactsImmediate(level);
        assertEquals(0, level.pendingImpactCount);

        // Wand impacts roll to-hit, so one shot may miss; 200 attempts make a
        // full whiff astronomically unlikely (same idiom as RangedShotLifecycleTest).
        for (int i = 0; i < 200 && victim.hp >= before; i++) {
            caster.ticksTillMove = 0;
            ItemSystem.fireWand(level, caster, wand, VICTIM_AT);
            MobSystem.drainPendingImpactsImmediate(level);
        }
        assertTrue(victim.hp < before,
                "wand impact never dealt damage headless - the wand-fizzle"
                + " regression (wand=" + wand.type + " victim=" + victim.mobType + ")");
    }

    @Test
    void pendingImpactResolvesBeforeAnyBrainRuns() {
        // A ready, awake hostile stands adjacent to its target. A queued
        // impact removes the hostile. If the drain at the top of
        // processAllAiTurns runs first (the invariant), the hostile never
        // acts; if a brain ran first, the hostile would have been billed an
        // action cost before vanishing.
        Mob hostile = null;
        for (String type : ArenaHarness.fightableMobs(false)) {
            hostile = MobFactory.spawn(type, THROWER_AT);
            if (hostile != null) break;
        }
        assertNotNull(hostile, "no fightable mob species loaded");
        Mob victim = victimMob();
        assertNotNull(victim, "no fightable mob species loaded");
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(7));
        Point adjacent = new Point(THROWER_AT.tileX() + 1, THROWER_AT.tileY());
        CombatArena.placeMobs(level, List.of(hostile, victim),
                List.of(THROWER_AT, adjacent));
        CombatArena.seedTeamHostility(List.of(hostile), List.of(victim));
        hostile.stateOfMind = Mob.StateOfMind.AWAKE;
        hostile.ticksTillMove = 0;
        victim.ticksTillMove = 50;   // not its turn - only the hostile is ready

        final Mob hostileFinal = hostile;
        MobSystem.queuePendingImpact(level, () -> level.mobs.remove(hostileFinal));
        MobAi.processAllAiTurns(level);

        assertFalse(level.mobs.contains(hostile),
                "queued impact must have resolved during processAllAiTurns");
        assertEquals(0, hostile.ticksTillMove,
                "hostile acted before the pending impact drained - the"
                + " pending-impact gate must resolve impacts before any brain runs");
    }
}
