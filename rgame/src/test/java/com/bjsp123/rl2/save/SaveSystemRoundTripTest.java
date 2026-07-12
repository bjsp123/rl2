package com.bjsp123.rl2.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;
import com.bjsp123.rl2.util.ArenaHarness;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Headless save/load round-trip over a real generated world. SaveSystem is
 * gdx-static-free on the happy path (all I/O behind {@link
 * com.bjsp123.rl2.persistence.Persistence}, {@code Json} needs no Gdx.app),
 * so the full serialise-deserialise cycle - including the bespoke Point /
 * EnumMap / GemSpecies serializers - runs as a plain JUnit test. This is the
 * only automated coverage of run persistence: a model-field change that
 * breaks saves fails here, not on a playtester's Resume button.
 */
class SaveSystemRoundTripTest {

    @BeforeAll
    static void loadGameData() throws IOException {
        ArenaHarness.loadData(ArenaHarness.locateAssetsDir());
    }

    /** Same recipe as rai's AutoplayGame.newRun, minus the SMART agent. */
    private static World newWorld(long seed) {
        com.bjsp123.rl2.util.SimRng.reseedAll(seed);
        Random rng = new Random(seed);
        World world = new World();
        world.unique = new UniqueTracker();
        world.seed = seed;
        world.levels = WorldTopology.build(48, 48, rng, world.unique);
        world.currentLevelIndex = 0;
        world.linkLevels();
        Level start = world.currentLevel();
        Mob player = MobFactory.player(firstFreeFloor(start), Mob.CharacterClass.WARRIOR);
        player.perks.put(Perk.HURLER, 1);   // exercise the EnumMap serializer with entries
        start.mobs.add(player);
        return world;
    }

    private static Point firstFreeFloor(Level level) {
        for (int x = 1; x < level.width - 1; x++) {
            for (int y = 1; y < level.height - 1; y++) {
                if (level.tiles[x][y].isFloorLike()) return new Point(x, y);
            }
        }
        throw new IllegalStateException("generated level has no floor tile");
    }

    @Test
    void saveLoadRoundTripPreservesTheRun() {
        SaveSystem saves = new SaveSystem(new InMemoryPersistence());
        World world = newWorld(42L);
        Mob player = TurnSystem.findPlayer(world.currentLevel());
        assertNotNull(player);
        player.score = 1234;

        saves.save(0, world);
        assertTrue(saves.exists(0));
        assertTrue(saves.anyExists());
        assertEquals(1, saves.firstEmpty());

        SaveSystem.SaveMetadata meta = saves.metadata(0);
        assertNotNull(meta, "metadata must be written alongside the world");
        assertEquals(world.currentLevel().depth, meta.depth);
        assertEquals(1234, meta.score);
        assertEquals((int) Math.round(player.hp), meta.hp);

        World loaded = saves.load(0);
        assertNotNull(loaded, "load failed: " + saves.lastLoadError());
        assertNull(saves.lastLoadError());

        assertEquals(world.seed, loaded.seed);
        assertEquals(world.levels.length, loaded.levels.length);
        assertEquals(world.currentLevelIndex, loaded.currentLevelIndex);
        for (int i = 0; i < world.levels.length; i++) {
            assertEquals(world.levels[i].mobs.size(), loaded.levels[i].mobs.size(),
                    "mob count differs on level " + i);
            assertEquals(world.levels[i].items.size(), loaded.levels[i].items.size(),
                    "item count differs on level " + i);
        }

        Mob loadedPlayer = TurnSystem.findPlayer(loaded.currentLevel());
        assertNotNull(loadedPlayer, "player must survive the round trip");
        assertTrue(loadedPlayer.isPlayer);
        assertEquals(player.hp, loadedPlayer.hp);
        assertEquals(player.position, loadedPlayer.position);
        assertEquals(player.characterLevel, loadedPlayer.characterLevel);
        assertEquals(1234, loadedPlayer.score);
        assertEquals(player.perks, loadedPlayer.perks);
        assertEquals(player.inventory.bag.size(), loadedPlayer.inventory.bag.size());
        for (int i = 0; i < player.inventory.bag.size(); i++) {
            assertEquals(player.inventory.bag.get(i).type,
                    loadedPlayer.inventory.bag.get(i).type,
                    "bag item " + i + " changed type in the round trip");
        }
    }

    @Test
    void secondRoundTripIsStable() {
        // Save the LOADED world again and reload: catches serializers that
        // only survive one pass (e.g. a transient rebuilt into a different
        // concrete collection type that then fails to serialise).
        SaveSystem saves = new SaveSystem(new InMemoryPersistence());
        saves.save(0, newWorld(7L));
        World once = saves.load(0);
        assertNotNull(once, "first load failed: " + saves.lastLoadError());
        saves.save(1, once);
        World twice = saves.load(1);
        assertNotNull(twice, "second round trip failed: " + saves.lastLoadError());
        assertEquals(once.levels.length, twice.levels.length);
        assertEquals(once.currentLevel().mobs.size(), twice.currentLevel().mobs.size());
    }

    @Test
    void emptySlotLoadReportsCleanly() {
        SaveSystem saves = new SaveSystem(new InMemoryPersistence());
        assertFalse(saves.exists(2));
        assertNull(saves.load(2));
        assertEquals("save slot is empty", saves.lastLoadError());
    }

    @Test
    void clearEmptiesTheSlot() {
        SaveSystem saves = new SaveSystem(new InMemoryPersistence());
        saves.save(0, newWorld(9L));
        assertTrue(saves.exists(0));
        saves.clear(0);
        assertFalse(saves.exists(0));
        assertEquals(0, saves.firstEmpty());
    }
}
