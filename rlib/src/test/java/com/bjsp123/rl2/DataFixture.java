package com.bjsp123.rl2;

import com.bjsp123.rl2.util.ArenaHarness;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for tests that need the real CSV registries loaded. Loads the same
 * data files {@code Rl2Game.create} reads (strings, config, mobs, items, gems,
 * themed rooms, recipes) via the existing headless {@link ArenaHarness} - no
 * libGDX, no GL. The assets directory is located by walking up from the working
 * directory, so the test runs from any module's project dir.
 */
public abstract class DataFixture {

    /** {@code assets/data} directory, resolved once for the whole run. */
    protected static Path assets;

    @BeforeAll
    static void loadGameData() throws IOException {
        assets = ArenaHarness.locateAssetsDir();
        ArenaHarness.loadData(assets);
    }
}
