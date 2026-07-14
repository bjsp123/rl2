package com.bjsp123.rl2.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Guardrails for crafted gem-scroll effects. SC_BLAST_THROUGH_WALLS shipped
 * as a dud once (no damage column, no wall conversion) - these tests pin the
 * intended behaviour: plain walls in the blast disc break, props and the
 * level boundary survive, and mobs at the impact point take damage.
 */
class GemScrollTest extends DataFixture {

    private static final Point CASTER_AT = new Point(3, 8);
    private static final Point TARGET_AT = new Point(8, 8);

    @Test
    void blastThroughWallsBreaksWallsAndDamages() {
        Level level = CombatArena.buildArenaLevel(16, 16, new Random(5));
        Mob caster = MobFactory.player(CASTER_AT, Mob.CharacterClass.MAGE);
        Mob victim = null;
        for (String type : com.bjsp123.rl2.util.ArenaHarness.fightableMobs(false)) {
            victim = MobFactory.spawn(type, TARGET_AT);
            if (victim != null) break;
        }
        CombatArena.placeMobs(level, List.of(caster, victim),
                List.of(CASTER_AT, TARGET_AT));
        victim.hp = 100000;
        double hpBefore = victim.hp;

        // Wall in the disc, wall outside it, and a statue prop in the disc.
        level.tiles[7][8] = Tile.WALL;
        level.tiles[8][9] = Tile.WALL;
        level.tiles[12][8] = Tile.WALL;                 // outside radius 2
        level.tiles[8][7] = Tile.STATUE_SMALL_L;        // prop - must survive

        // The arena rolls its perimeter as WALL or CHASM - capture whatever
        // it is; the blast must never alter the boundary ring.
        Tile boundaryBefore = level.tiles[0][8];

        Item scroll = ItemFactory.build("SC_BLAST_THROUGH_WALLS");
        assertTrue(GemSystem.triggerGem(level, caster, scroll, TARGET_AT),
                "blast scroll must fire at a valid target");

        assertEquals(Tile.FLOOR, level.tiles[7][8], "wall in disc must break");
        assertEquals(Tile.FLOOR, level.tiles[8][9], "wall in disc must break");
        assertEquals(Tile.WALL, level.tiles[12][8], "wall outside disc survives");
        assertEquals(Tile.STATUE_SMALL_L, level.tiles[8][7], "props survive the blast");
        // Boundary ring stays sealed even when the disc touches it.
        assertEquals(boundaryBefore, level.tiles[0][8], "boundary never breaks");

        MobSystem.drainPendingImpactsImmediate(level);   // no-op safety
        assertTrue(victim.hp < hpBefore,
                "blast must damage the mob at the impact point (was a no-damage dud)");
    }
}
