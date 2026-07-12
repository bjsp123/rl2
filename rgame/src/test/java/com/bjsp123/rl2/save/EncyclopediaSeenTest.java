package com.bjsp123.rl2.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.persistence.Persistence;
import com.bjsp123.rl2.save.Achievements.Achievement;
import com.bjsp123.rl2.ui.v2.V2Encyclopedia;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Encyclopedia seen-gating persistence + masking. The seen-sets ride the
 * same {@link AchievementsStore} blob as the unlock set, so a round-trip
 * here is the coverage that keeps a player's revealed encyclopedia from
 * silently resetting across sessions.
 */
class EncyclopediaSeenTest {

    @Test
    void seenSetsSurviveSaveLoadRoundTrip() {
        Persistence p = new InMemoryPersistence();
        Achievements a = new Achievements();
        a.unlocked.add(Achievement.FIRST_BLOOD);
        a.killedMobTypes.add("RAT");
        a.seenMobTypes.add("RAT");
        a.seenMobTypes.add("KOBOLD_FIGHTER");
        a.seenItemTypes.add("SHORT_SWORD");
        AchievementsStore.save(p, a);

        Achievements b = AchievementsStore.load(p);
        assertEquals(Set.of(Achievement.FIRST_BLOOD), b.unlocked);
        assertEquals(Set.of("RAT"), b.killedMobTypes);
        assertEquals(Set.of("RAT", "KOBOLD_FIGHTER"), b.seenMobTypes);
        assertEquals(Set.of("SHORT_SWORD"), b.seenItemTypes);
    }

    /** Pre-gating save blobs have no seen-set fields - they must load to
     *  empty sets (nothing revealed), not fail. */
    @Test
    void legacyBlobWithoutSeenSetsLoadsEmpty() {
        Persistence p = new InMemoryPersistence();
        p.save("rl2-achievements",
                "{\"unlocked\":[\"FIRST_BLOOD\"],\"killedMobTypes\":[\"RAT\"]}");
        Achievements a = AchievementsStore.load(p);
        assertEquals(Set.of(Achievement.FIRST_BLOOD), a.unlocked);
        assertEquals(Set.of("RAT"), a.killedMobTypes);
        assertTrue(a.seenMobTypes.isEmpty());
        assertTrue(a.seenItemTypes.isEmpty());
    }

    /** Masking replaces every non-whitespace char with '?', keeping the
     *  text's shape (spaces, newlines, tabs) intact. */
    @Test
    void maskUnseenPreservesWhitespace() {
        assertEquals("????? ?????", V2Encyclopedia.maskUnseen("giant snail"));
        assertEquals("???\n?? ?", V2Encyclopedia.maskUnseen("rat\nof x"));
        assertEquals("", V2Encyclopedia.maskUnseen(""));
        assertNull(V2Encyclopedia.maskUnseen(null));
        assertEquals(" \t\n", V2Encyclopedia.maskUnseen(" \t\n"));
    }
}
