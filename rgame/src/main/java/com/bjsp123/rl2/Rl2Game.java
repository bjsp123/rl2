package com.bjsp123.rl2;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.bjsp123.rl2.save.ArenaHallOfFame;
import com.bjsp123.rl2.save.ArenaHallOfFameStore;
import com.bjsp123.rl2.save.Achievements;
import com.bjsp123.rl2.save.AchievementsStore;
import com.bjsp123.rl2.save.AchievementSystem;
import com.bjsp123.rl2.save.HallOfFame;
import com.bjsp123.rl2.save.HallOfFameStore;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.persistence.Persistence;
import com.bjsp123.rl2.audio.MusicPlayer;
import com.bjsp123.rl2.audio.SoundManager;
import com.bjsp123.rl2.screen.PlayScreen;
import com.bjsp123.rl2.ui.skin.Settings;
import com.bjsp123.rl2.ui.v2.UiCtx;
import com.bjsp123.rl2.ui.v2.V2Title;

public class Rl2Game extends Game {

    /** Persistence key for the most recent desktop window size, formatted as "WxH". */
    public static final String WINDOW_SIZE_KEY = "desktop-window";
    /** Minimum gap between window-size persistence writes during a resize drag. */
    private static final long  RESIZE_PERSIST_THROTTLE_MS = 250;

    public final Persistence persistence;
    public HallOfFame      hallOfFame;
    public ArenaHallOfFame arenaHallOfFame;
    /** Cross-run achievement unlock state. Loaded once at boot; persisted
     *  via {@link AchievementsStore} whenever a new entry is unlocked. */
    public Achievements    achievements;
    /** Observation-driven achievement firing. Holds {@link #achievements}
     *  and the persistence handle; PlayScreen wires its toast / log
     *  listener via {@link AchievementSystem#setListener}. */
    public AchievementSystem achievementSystem;
    public SaveSystem  saveSystem;
    /** Shared V2 UI rendering context - fonts, ShapeRenderer, SpriteBatch.
     *  Held by {@link Rl2Game} so every V2 screen reuses the same instance. */
    public UiCtx       ui;
    public SoundManager sounds;
    public MusicPlayer  music;

    /** The currently in-progress run, if any. Null means "no game to resume". */
    public PlayScreen currentPlay;

    private long lastResizePersistMs;
    private int  lastPersistedW, lastPersistedH;

    @Override
    public void setScreen(com.badlogic.gdx.Screen screen) {
        if (screen instanceof com.bjsp123.rl2.ui.v2.V2Screen vs && sounds != null)
            vs.setSounds(sounds);
        super.setScreen(screen);
    }

    /** Navigate forward - push a "restore previous screen" entry onto the
     *  shared {@link com.bjsp123.rl2.ui.v2.WindowStack} (in {@link #ui})
     *  and switch to {@code next}. The current screen instance is captured
     *  so a later {@link #popScreen} returns to the exact same state.
     *  Popups push onto the SAME stack, so back unwinds screens and popups
     *  uniformly, one window per back press. */
    public void pushScreen(com.badlogic.gdx.Screen next) {
        com.badlogic.gdx.Screen cur = getScreen();
        if (cur != null && cur != next) {
            // Freeze the play screen as the backdrop for the about-to-be-
            // pushed V2 screen so menus (Map, Settings, ...) opened from
            // in-game show the player's actual world instead of the
            // title-screen attract demo. The snapshot is the FB AS IT IS
            // NOW, i.e. after the last play-screen render.
            if (currentPlay != null && cur == currentPlay) {
                ui.captureBackdropFromFrameBuffer();
            }
            com.badlogic.gdx.Screen prev = cur;
            ui.stack.push(() -> setScreen(prev));
        }
        setScreen(next);
    }

    /** Navigate back - pop the most recent entry from the shared
     *  {@link com.bjsp123.rl2.ui.v2.WindowStack}. The popped runnable
     *  closes the current window and restores whatever was beneath it
     *  (could be a screen or a popup). No-op when the stack is empty.
     *  Returns {@code true} when something was popped. */
    public boolean popScreen() {
        boolean popped = ui.stack.back();
        // If we've unwound back to the live play screen, the frozen
        // backdrop snapshot is no longer needed - the world is being
        // rendered fresh again.
        if (popped && currentPlay != null && getScreen() == currentPlay) {
            ui.clearBackdrop();
        }
        return popped;
    }

    /** Navigate to a root screen - clear the entire back-history stack
     *  (screens AND popup callbacks) and switch. Used for "Title" /
     *  "Quit to Title" / "Begin game" / "Game Over" - destinations
     *  where back-navigation shouldn't return to the previous window. */
    public void setRootScreen(com.badlogic.gdx.Screen root) {
        if (ui != null) {
            ui.stack.clear();
            // Root-screen transitions (Title, Game Over, Begin Game) leave
            // any in-game context behind; the snapshot would be stale.
            ui.clearBackdrop();
        }
        setScreen(root);
    }

    public Rl2Game(Persistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public void create() {
        // Data-driven configs - read once at startup, before any factory call or
        // gameplay code reads a balance number. Game-balance loads first because
        // some Mob field initializers reference GameBalance constants.
        loadStrings();
        loadGameBalance();
        loadUiVars();
        loadAnimationVars();
        loadMobConfig();
        loadItemConfig();
        loadBrandConfig();
        loadThemedRoomConfig();
        loadRecipeConfig();
        loadTipsConfig();
        loadHelpConfig();
        Settings.init(persistence);
        loadSoundsConfig();
        music = new MusicPlayer();
        // Plug the rgame-side icon-pref toggle into the in-world Animator so its
        // BuffApplied event handler renders an icon when the user wants icons and a
        // text float otherwise.
        com.bjsp123.rl2.world.anim.Animator.setIconPreferenceSupplier(
                com.bjsp123.rl2.ui.skin.Settings::useBuffIcons);
        // Same pattern: the Animator advances {@code framesPerRender()} animation
        // frames per render frame so the user-facing animation-speed setting (1x,
        // 2x, 4x) shortens authored durations uniformly.
        com.bjsp123.rl2.world.anim.Animator.setAnimationSpeedSupplier(
                Settings::framesPerRender);
        hallOfFame      = HallOfFameStore.load(persistence);
        arenaHallOfFame = ArenaHallOfFameStore.load(persistence);
        achievements    = AchievementsStore.load(persistence);
        achievementSystem = new AchievementSystem(achievements, persistence);
        saveSystem = new SaveSystem(persistence);
        // V2 UI - single shared UiCtx (fonts, ShapeRenderer, SpriteBatch) used
        // by every V2 screen and in-game popup. The full V2 menu chain is now
        // wired in: title -> saves -> character select -> game; settings,
        // hall-of-fame, arena, credits, map, level-info all V2 too.
        ui = new UiCtx();
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        setScreen(new V2Title(this, ui));
    }


    private void loadStrings() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/strings.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.logic.TextCatalog.load(fh.readString());
    }

    /** Read {@code assets/data/config.csv} and override matching
     *  {@link com.bjsp123.rl2.ui.v2.UIVars} fields. Missing file is non-fatal. */
    private void loadUiVars() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/config.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.ui.v2.UIVars.load(fh.readString());
    }

    /** Read {@code assets/data/config.csv} and override matching
     *  {@link com.bjsp123.rl2.logic.GameBalance} fields. Missing file is non-fatal -
     *  the Java-side baselines stand. */
    private void loadGameBalance() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/config.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.logic.GameBalance.load(fh.readString());
    }

    private void loadAnimationVars() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/config.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.world.anim.AnimationVars.load(fh.readString());
    }

    /** Read {@code assets/data/mobs.csv} and feed it into both the gameplay-side
     *  {@link com.bjsp123.rl2.logic.MobRegistry} and the renderer-side
     *  {@link com.bjsp123.rl2.world.render.MobSprites}. Same file, two consumers
     *  - rlib reads the gameplay columns, rgame reads the sprite columns. */
    private void loadMobConfig() {
        String csv = com.badlogic.gdx.Gdx.files.internal("data/mobs.csv").readString();
        com.bjsp123.rl2.logic.Registries.loadMobs(csv);
        com.bjsp123.rl2.world.render.MobSprites.loadFromCsv(csv);
    }

    /** Read {@code assets/data/items.csv} into {@link com.bjsp123.rl2.logic.ItemRegistry}.
     *  Sprite columns are NOT in this file - those live in the rgame-side
     *  {@code item_sprites.csv} loaded by {@code ItemSprites}. */
    private void loadItemConfig() {
        String csv = com.badlogic.gdx.Gdx.files.internal("data/items.csv").readString();
        com.bjsp123.rl2.logic.Registries.loadItems(csv);
    }

    /** Read {@code assets/data/brands.csv} into {@link com.bjsp123.rl2.logic.BrandRegistry}. */
    private void loadBrandConfig() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/brands.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.logic.Registries.loadBrands(fh.readString());
    }

    /** Read {@code assets/data/themedrooms.csv} into
     *  {@link com.bjsp123.rl2.logic.ThemedRoomRegistry}. Missing file is non-fatal -
     *  the registry stays empty and themed-room stamping silently no-ops. */
    private void loadThemedRoomConfig() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/themedrooms.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.logic.Registries.loadThemedRooms(fh.readString());
    }

    /** Read {@code assets/data/recipes.csv} into the gem-recipe registry (RL-50).
     *  Missing file is non-fatal - the registry stays empty and the forge shows
     *  no recipes. */
    private void loadRecipeConfig() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/recipes.csv");
        if (!fh.exists()) return;
        com.bjsp123.rl2.logic.Registries.loadRecipes(fh.readString());
    }

    private void loadTipsConfig() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/tips.csv");
        if (!fh.exists()) return;
        TipsRegistry.load(fh.readString());
    }

    private void loadSoundsConfig() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/sounds.csv");
        if (!fh.exists()) return;
        sounds = new SoundManager(com.bjsp123.rl2.util.CsvTable.parse(fh.readString()));
    }

    private void loadHelpConfig() {
        com.badlogic.gdx.files.FileHandle fh =
                com.badlogic.gdx.Gdx.files.internal("data/help.csv");
        if (!fh.exists()) return;
        GuideRegistry.load(fh.readString());
    }

    @Override
    public void pause() {
        super.pause();
        if (currentPlay != null) currentPlay.persist();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (persistence == null || width <= 0 || height <= 0) return;
        long now = System.currentTimeMillis();
        // Drag-resizes fire at frame rate; throttle so we don't hammer disk. Also skip
        // duplicate writes when nothing actually changed.
        if (width == lastPersistedW && height == lastPersistedH) return;
        if (now - lastResizePersistMs < RESIZE_PERSIST_THROTTLE_MS) return;
        persistence.save(WINDOW_SIZE_KEY, width + "x" + height);
        lastResizePersistMs = now;
        lastPersistedW = width;
        lastPersistedH = height;
    }

    @Override
    public void dispose() {
        // Final flush - guarantees the size after the user's last drag (which may have been
        // dropped by the throttle) is written before exit.
        if (persistence != null
                && com.badlogic.gdx.Gdx.graphics != null
                && com.badlogic.gdx.Gdx.graphics.getWidth() > 0) {
            persistence.save(WINDOW_SIZE_KEY,
                    com.badlogic.gdx.Gdx.graphics.getWidth() + "x"
                  + com.badlogic.gdx.Gdx.graphics.getHeight());
        }
        super.dispose();
        if (sounds != null) sounds.dispose();
        if (music  != null) music.dispose();
        if (ui != null) ui.dispose();

        // HARD EXIT to dodge a native teardown crash. On GL stacks that go
        // through Microsoft's OpenGLOn12 / D3D12 mapping layer (used when no
        // native OpenGL driver is present - e.g. ARM Windows, and even under
        // ANGLE here), glfwDestroyWindow reliably access-violates during window
        // teardown. That's a native EXCEPTION_ACCESS_VIOLATION the JVM can't
        // catch with try/catch - it aborts the process with a crash dump.
        // This dispose() is the ApplicationListener teardown, which libGDX runs
        // BEFORE glfwDestroyWindow, and all our own cleanup (window-size save,
        // audio/UI dispose) has just completed - so halt the JVM right here and
        // never reach the buggy native destroy. The OS reclaims the GL context,
        // window, and memory on process exit. Exit code 0 = clean shutdown.
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(0);
    }
}
