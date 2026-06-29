package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.util.ArenaHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MobFactory} - player construction per class, the
 * isPlayer-vs-behavior identity contract, and the spawn() routing rules
 * (PLAYER_* rows and unknown types return null).
 */
class MobFactoryTest extends DataFixture {

    // -- player(): happy -----------------------------------------------------

    @Test
    void playerBuildsForEveryClass() {
        for (Mob.CharacterClass cls : Mob.CharacterClass.values()) {
            Mob p = MobFactory.player(new Point(2, 3), cls);
            assertNotNull(p, "no player built for " + cls);
            assertTrue(p.isPlayer, "isPlayer flag not set for " + cls);
            assertSame(cls, p.characterClass);
            assertNotNull(p.position);
            assertTrue(p.hp > 0, "player spawned with non-positive HP: " + cls);
        }
    }

    // -- spawn(): happy ------------------------------------------------------

    @Test
    void spawnBuildsRealMonster() {
        List<String> fightable = ArenaHarness.fightableMobs(false);
        assertFalse(fightable.isEmpty(), "no fightable mobs loaded");
        Mob m = MobFactory.spawn(fightable.get(0), new Point(0, 0));
        assertNotNull(m);
        assertFalse(m.isPlayer);
        assertNotNull(m.mobType);
        assertTrue(m.hp > 0);
    }

    // -- spawn(): unhappy ----------------------------------------------------

    @Test
    void spawnReturnsNullForPlayerRows() {
        // PLAYER_* kit rows are reachable only via player(); spawn() refuses them.
        assertNull(MobFactory.spawn("PLAYER_WARRIOR", new Point(0, 0)));
    }

    @Test
    void spawnReturnsNullForUnknownType() {
        assertNull(MobFactory.spawn("NO_SUCH_MOB_TYPE_XYZ", new Point(0, 0)));
    }

    @Test
    void spawnReturnsNullForNullType() {
        assertNull(MobFactory.spawn(null, new Point(0, 0)));
    }
}
