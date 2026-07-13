package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MobProgression} - the XP cost schedule, spawn-level
 * seeding, level-ups (and their cap), and AI perk auto-spend. Needs the
 * registries loaded so {@code MobFactory} can build real fighters.
 */
class MobProgressionTest extends DataFixture {

    private static Mob freshPlayer() {
        return MobFactory.player(new Point(0, 0), Mob.CharacterClass.WARRIOR);
    }

    // -- XP schedule: happy --------------------------------------------------

    @Test
    void xpToReachFollowsTriangularSchedule() {
        int step = GameBalance.XP_PER_LEVEL_STEP;
        assertEquals(0, MobProgression.xpToReach(1));
        assertEquals(step, MobProgression.xpToReach(2));            // step*(1*2/2)
        assertEquals(step * 3, MobProgression.xpToReach(3));        // step*(2*3/2)
        assertEquals(step * 6, MobProgression.xpToReach(4));        // step*(3*4/2)
    }

    @Test
    void xpToAdvanceScalesWithLevel() {
        int step = GameBalance.XP_PER_LEVEL_STEP;
        assertEquals(step, MobProgression.xpToAdvanceFrom(1));
        assertEquals(step * 5, MobProgression.xpToAdvanceFrom(5));
    }

    // -- XP schedule: edge ---------------------------------------------------

    @Test
    void xpToReachClampsAtOrBelowLevelOne() {
        assertEquals(0, MobProgression.xpToReach(0));
        assertEquals(0, MobProgression.xpToReach(-5));
    }

    @Test
    void xpToAdvanceFromZeroIsAtLeastOne() {
        assertEquals(1, MobProgression.xpToAdvanceFrom(0));
    }

    // -- setSpawnLevel -------------------------------------------------------

    @Test
    void setSpawnLevelSeedsLevelXpAndHp() {
        Mob m = freshPlayer();
        MobProgression.setSpawnLevel(m, 5);
        assertEquals(5, m.characterLevel);
        assertEquals(MobProgression.xpToReach(5), m.xp);
        assertTrue(m.hp > 0);
    }

    @Test
    void setSpawnLevelClampsToValidRange() {
        Mob m = freshPlayer();
        MobProgression.setSpawnLevel(m, 0);
        assertEquals(1, m.characterLevel);          // floored at 1
        MobProgression.setSpawnLevel(m, 99999);
        assertEquals(GameBalance.MAX_CHARACTER_LEVEL, m.characterLevel); // capped
    }

    @Test
    void setSpawnLevelNullMobIsNoOp() {
        MobProgression.setSpawnLevel(null, 5); // must not throw
    }

    // -- awardXp -------------------------------------------------------------

    @Test
    void awardXpLevelsUpWhenThresholdCrossed() {
        Mob m = freshPlayer();
        assertEquals(1, m.characterLevel);
        int gained = MobProgression.awardXp(null, m, MobProgression.xpToReach(2));
        assertTrue(gained >= 1);
        assertTrue(m.characterLevel >= 2);
    }

    @Test
    void awardXpNonPositiveIsNoOp() {
        Mob m = freshPlayer();
        assertEquals(0, MobProgression.awardXp(null, m, 0));
        assertEquals(0, MobProgression.awardXp(null, m, -100));
        assertEquals(1, m.characterLevel);
    }

    @Test
    void awardXpDoesNotExceedLevelCap() {
        Mob m = freshPlayer();
        MobProgression.setSpawnLevel(m, GameBalance.MAX_CHARACTER_LEVEL);
        int gained = MobProgression.awardXp(null, m, 10_000_000);
        assertEquals(0, gained);
        assertEquals(GameBalance.MAX_CHARACTER_LEVEL, m.characterLevel);
    }

    @Test
    void awardXpNullMobIsNoOp() {
        assertEquals(0, MobProgression.awardXp(null, null, 100));
    }

    // -- autoLevelUpPerks ----------------------------------------------------

    @Test
    void autoLevelUpPerksSpendsAllPointsWithinCap() {
        Mob m = freshPlayer();
        MobProgression.setSpawnLevel(m, 6);
        MobProgression.autoLevelUpPerks(m, new Random(42));
        // Every point spent, none left over.
        assertEquals(0, m.perkPoints);
        // No single perk pushed past its cap (signature perks cap higher
        // than open perks - check each against its own resolved cap).
        for (var e : m.perks.entrySet()) {
            assertTrue(e.getValue() <= MobProgression.perkCap(m, e.getKey()),
                    "perk over cap: " + e.getKey() + "=" + e.getValue());
        }
        // Some investment actually happened at level 6.
        int total = m.perks.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(total >= (6 - 1) * GameBalance.PERK_POINTS_PER_LEVEL);
    }

    @Test
    void autoLevelUpPerksNullMobIsNoOp() {
        MobProgression.autoLevelUpPerks(null, new Random(1)); // must not throw
    }

    @Test
    void autoLevelUpPerksAtLevelOneSpendsNothing() {
        Mob m = freshPlayer(); // level 1
        int before = m.perks.values().stream().mapToInt(Integer::intValue).sum();
        MobProgression.autoLevelUpPerks(m, new Random(1));
        int after = m.perks.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(before, after);
        assertEquals(0, m.perkPoints);
    }
}
