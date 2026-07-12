package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.util.ArenaHarness;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Innate ranged shots (crossbow bolts, imp sparks) must follow the
 * animation-gated lifecycle: {@code tryRangedShot} queues a pending impact on
 * the level, and the headless drain resolves it through
 * {@link MobSystem#applyRangedShotImpact} - hit roll, resist, damage. Before
 * the lifecycle migration the resolution lived only in the rgame Animator, so
 * headless ranged mobs dealt zero damage; these tests pin the fix.
 */
class RangedShotLifecycleTest extends DataFixture {

    private static final Point SHOOTER_AT = new Point(3, 8);
    private static final Point VICTIM_AT  = new Point(7, 8);

    /** Spawn a fresh victim for the shot - classified by stats, not name:
     *  skip anything with the WRAITH_DODGE ability (it blink-dodges the hit
     *  and moves off the locked impact tile, which is correct behaviour but
     *  useless as a damage sponge). */
    private static Mob victimMob() {
        for (String type : ArenaHarness.fightableMobs(false)) {
            Mob m = MobFactory.spawn(type, VICTIM_AT);
            if (m == null || hasWraithDodge(m)) continue;
            return m;
        }
        return null;
    }

    private static boolean hasWraithDodge(Mob m) {
        if (m.abilities == null) return false;
        for (Mob.MobAbility ab : m.abilities) {
            if (ab != null && ab.kind == Mob.MobAbility.AbilityKind.WRAITH_DODGE) {
                return true;
            }
        }
        return false;
    }

    /** One arena set up so {@code shooter} is hostile to {@code victim} with
     *  clear line of sight at Chebyshev 4. */
    private static Level arenaWith(Mob shooter, Mob victim) {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(7));
        CombatArena.placeMobs(level, List.of(shooter, victim),
                List.of(SHOOTER_AT, VICTIM_AT));
        CombatArena.seedTeamHostility(List.of(shooter), List.of(victim));
        return level;
    }

    @Test
    void aiRangedShotQueuesPendingImpact() {
        // Data-driven sweep: some species with rangedDamage may still prefer
        // other actions this turn, so accept the first species whose brain
        // actually fires. The assertion is that at least one ranged mob in
        // mobs.csv queues a lifecycle impact instead of resolving nothing.
        boolean queued = false;
        String firedBy = null;
        for (String type : ArenaHarness.fightableMobs(false)) {
            Mob shooter = MobFactory.spawn(type, SHOOTER_AT);
            if (shooter == null) continue;
            var ss = shooter.effectiveStats();
            if (ss.rangedDamage.max() <= 0) continue;
            if (ss.rangedDistance > 0 && ss.rangedDistance < 4) continue;
            Mob victim = victimMob();
            assertNotNull(victim, "no fightable mob species loaded");
            Level level = arenaWith(shooter, victim);
            shooter.stateOfMind = Mob.StateOfMind.AWAKE;   // skip the sleep gate
            shooter.ticksTillMove = 0;
            MobCombat.snapshotVisibleMobsAtTurnStart(level, shooter);
            MobAiBehavior.processAiTurn(shooter, level);
            if (level.pendingImpactCount > 0) {
                queued = true;
                firedBy = type;
                // Draining must clear the gate (whether the roll hits or not).
                MobSystem.drainPendingImpactsImmediate(level);
                assertEquals(0, level.pendingImpactCount,
                        "drain left the pending-impact gate up for " + firedBy);
                break;
            }
        }
        assertTrue(queued, "no ranged mob species queued a pending impact");
    }

    @Test
    void rangedShotImpactDealsDamageHeadless() {
        Mob shooter = null;
        for (String type : ArenaHarness.fightableMobs(false)) {
            Mob m = MobFactory.spawn(type, SHOOTER_AT);
            if (m != null && m.effectiveStats().rangedDamage.max() > 0) {
                shooter = m;
                break;
            }
        }
        assertNotNull(shooter, "no ranged mob species in mobs.csv");
        Mob victim = victimMob();
        assertNotNull(victim, "no fightable mob species loaded");
        Level level = arenaWith(shooter, victim);
        victim.hp = 1000;
        double before = victim.hp;

        // The resolve rolls accuracy-vs-evasion, so a single shot may miss;
        // repeat queue+drain until one lands. 200 attempts at any plausible
        // hit chance makes a full whiff astronomically unlikely.
        final Mob shooterF = shooter;
        for (int i = 0; i < 200 && victim.hp >= before; i++) {
            MobSystem.queuePendingImpact(level,
                    () -> MobAiBehavior.applyRangedShotImpact(level, shooterF, VICTIM_AT, 40));
            assertEquals(1, level.pendingImpactCount);
            MobSystem.drainPendingImpactsImmediate(level);
            assertEquals(0, level.pendingImpactCount);
        }
        assertTrue(victim.hp < before,
                "ranged impact never dealt damage headless (200 attempts):"
                + " shooter=" + shooter.mobType
                + " acc=" + shooter.effectiveStats().accuracy
                + " victim=" + victim.mobType
                + " eva=" + victim.effectiveStats().evasion
                + " mobAt=" + (MobQueries.mobAt(level, VICTIM_AT) != null)
                + " victimPos=" + victim.position
                + " hp=" + victim.hp);
    }
}
