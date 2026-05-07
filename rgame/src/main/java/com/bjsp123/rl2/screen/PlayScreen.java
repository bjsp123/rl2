package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.ui.hud.ActionBar;
import com.bjsp123.rl2.input.CameraController;
import com.bjsp123.rl2.input.GameInput;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.Messages;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TargetHistory;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;
import com.bjsp123.rl2.ui.popup.CharacterStatsRenderer;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;
import com.bjsp123.rl2.ui.hud.HudRenderer;
import com.bjsp123.rl2.ui.popup.InventoryRenderer;
import com.bjsp123.rl2.world.render.LevelRenderer;
import com.bjsp123.rl2.ui.overlay.LookRenderer;
import com.bjsp123.rl2.ui.overlay.TargetingOverlay;

import java.util.ArrayList;
import java.util.List;
import com.bjsp123.rl2.ui.skin.StoneUi;
import com.bjsp123.rl2.ui.skin.UiPixelScale;
import com.bjsp123.rl2.ui.skin.UiScale;
import com.bjsp123.rl2.world.anim.Animator;

public class PlayScreen implements Screen {

    private static final float DEFAULT_ZOOM  = 0.35f;

    private final Rl2Game game;
    private final CharacterClass charClass;
    private final World preloadedWorld;
    public final int saveSlot;

    /** Player-supplied world seed for new runs. {@code null} ⇒ pick a random
     *  seed inside the {@link com.bjsp123.rl2.util.SeedCode} window. Ignored
     *  when {@link #preloadedWorld} is non-null (load preserves the saved seed). */
    private final Long requestedSeed;

    private World            world;
    private OrthographicCamera camera;
    private CameraController cameraController;
    private LevelRenderer    levelRenderer;
    private HudRenderer      hudRenderer;
    private InventoryRenderer inventoryRenderer;
    private com.bjsp123.rl2.ui.popup.CraftingRenderer craftingRenderer;
    private LookRenderer      lookRenderer;
    private ActionBar         actionBar;
    private TargetingOverlay  targetingOverlay;
    private CharacterStatsRenderer characterStatsRenderer;
    private com.bjsp123.rl2.ui.popup.EncyclopediaRenderer encyclopediaRenderer;
    private GameInput         gameInput;
    private LookMode          lookMode;
    private Stage             uiStage;
    private StoneUi           stoneUi;
    private Skin              uiSkin;
    /** In-world animator: drains {@code Level.events} after each {@code TurnSystem.tick}
     *  and maintains the per-mob {@link com.bjsp123.rl2.world.anim.MobAnimState} that the
     *  renderer reads. The single source of truth for the tick-gate freeze counter
     *  lives on {@link com.bjsp123.rl2.world.anim.AnimQueue}. */
    private final com.bjsp123.rl2.world.anim.Animator animator = new com.bjsp123.rl2.world.anim.Animator();

    private boolean initialized;
    private HallOfFameEntry lastSnapshot;

    /** Shared cursor-memory for Look and targeting. Records the most recent mob and the
     *  most recent non-mob tile the player interacted with; {@link TargetHistory#pickInitial}
     *  turns that into a "best guess" starting cell for every new cursor-picking flow. */
    private final TargetHistory targetHistory = new TargetHistory();

    /** Action / firing / auto-move dispatcher. Holds every "user pressed a button →
     *  mutate the level" code path; this screen keeps lifecycle + render orchestration
     *  and delegates everything else here. Built in {@link #initialize()}. */
    private PlayController controller;

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls) {
        this(game, slot, cls, null);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls, Long seed) {
        this.game = game;
        this.saveSlot = slot;
        this.charClass = cls;
        this.preloadedWorld = null;
        this.requestedSeed = seed;
    }

    public PlayScreen(Rl2Game game, int slot, World loadedWorld) {
        this.game = game;
        this.saveSlot = slot;
        this.charClass = null;
        this.preloadedWorld = loadedWorld;
        this.requestedSeed = null;
    }

    @Override
    public void show() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
        // cameraController goes FIRST so mouse-wheel zoom and pinch-zoom are always
        // available regardless of UI focus state — opening the inventory or the
        // encyclopaedia must not lock the camera at the current zoom level. Wheel
        // events return true (consumed); touchDown / touchDragged return false so
        // they continue to uiStage and the action handlers below.
        // uiStage goes second so HUD buttons stay reachable even while Look or
        // Targeting is active — the stage returns false for clicks that don't land
        // on a widget, letting them fall through to the world cursors. This is what
        // lets a re-tap of the same action-slot button confirm an open targeting
        // session, or a tap on a different action swap the target picker to that
        // new action.
        // Block map drag/pinch/scroll while any modal is open — per the UI
        // rules, the topmost window owns input and the world behind it
        // shouldn't slide under the user's finger.
        cameraController.setInputBlocker(this::isAnyPopupOpen);
        Gdx.input.setInputProcessor(new InputMultiplexer(
                cameraController, uiStage, targetingOverlay, lookMode, gameInput));
        game.currentPlay = this;
    }

    private void initialize() {
        camera           = new OrthographicCamera();
        cameraController = new CameraController(camera);
        cameraController.setAnimator(animator);

        boolean newRun = (preloadedWorld == null);
        if (preloadedWorld != null) {
            world = preloadedWorld;
            for (Level l : world.levels) if (l != null) l.initTransients();
        } else {
            EventLog.clear();
            // World is procedurally generated — see WorldTopology.build. Depth and the
            // side-branch / crosslink probabilities come from GameBalance; every level's
            // stairs(Up|Down)(Alt)Target fields fully encode the resulting graph.
            // Allocate the World first so build() and every createDungeonLevel call
            // share the same UniqueTracker — that's how unique themed rooms only
            // appear once per game.
            world = new World();
            // Honour an explicit user-supplied seed if there is one; otherwise
            // pick a random seed inside the {@link com.bjsp123.rl2.util.SeedCode}
            // window so the printed code is always typeable. Storing on World
            // lets the map screen show it and lets the dump reproduce the run.
            world.seed = (requestedSeed != null)
                    ? requestedSeed
                    : new java.util.Random().nextLong()
                            % com.bjsp123.rl2.util.SeedCode.SPACE;
            Level[] levels = WorldTopology.build(
                    com.bjsp123.rl2.logic.GameBalance.LEVEL_BASE_W,
                    com.bjsp123.rl2.logic.GameBalance.LEVEL_BASE_H,
                    new java.util.Random(world.seed),
                    world.unique);
            world.levels = levels;
            world.currentLevelIndex = 0;
            Point spawn = levels[0].spawnPoint != null ? levels[0].spawnPoint : new Point(2, 2);
            Mob player  = MobFactory.player(spawn, charClass);
            levels[0].mobs.add(player);
            levels[0].visited = true;
            String playerName = player.name != null ? player.name : "Adventurer";
            EventLog.add(Messages.beginGame(playerName));
            EventLog.add(Messages.enterLevel(playerName, 1, levels[0].flags));
        }

        Mob player = TurnSystem.findPlayer(world.currentLevel());
        LevelSystem.computeLighting(world.currentLevel());
        LevelSystem.updateVisibility(world.currentLevel());

        levelRenderer = new DefaultLevelRenderer();
        levelRenderer.create();
        levelRenderer.setWorld(world);
        levelRenderer.setAnimator(animator);

        // Shared in-game UI stack: one Stage backed by ScreenViewport at UiScale × UiPixelScale,
        // one Skin from the stone 9-patches. All three overlays are scene2d Groups added to this
        // stage. UiScale controls overall widget size; UiPixelScale adds an integer-multiple
        // nearest-neighbor upscale on top (each source pixel → N screen pixels).
        stoneUi = new StoneUi();
        stoneUi.create();
        uiSkin = stoneUi.newSkin();
        ScreenViewport vp = new ScreenViewport();
        vp.setUnitsPerPixel(1f / (UiScale.scale() * UiPixelScale.scale()));
        uiStage = new Stage(vp);

        hudRenderer = new HudRenderer(uiSkin, stoneUi);
        hudRenderer.setOnReturnToTitle(() -> {
            persist();
            game.setScreen(new TitleScreen(game));
        });
        PlayScreen self = this;
        hudRenderer.setOnOpenSettings(() ->
                game.setScreen(new SettingsScreen(game, () -> game.setScreen(self))));
        // Encyclopaedia is now an in-game popup (constructed below); the menu callback
        // just toggles it. Level Info reuses the old full-screen with only its
        // current-level fact sheet.
        hudRenderer.setOnOpenLevelInfo(() ->
                game.setScreen(new LevelInfoScreen(
                        () -> game.setScreen(self),
                        world.currentLevel())));

        // HUD action bar — used to live as a field on Mob; now an rgame-side UI object so
        // the rlib model has no idea quickslots exist. New games seed the default
        // bindings from the player's bag based on character class; loaded games start
        // with everything empty until the user re-binds (saves don't persist the bar).
        actionBar = new ActionBar();

        inventoryRenderer = new InventoryRenderer(uiSkin);
        inventoryRenderer.setPlayer(player);
        inventoryRenderer.setActionBar(actionBar);
        craftingRenderer = new com.bjsp123.rl2.ui.popup.CraftingRenderer(uiSkin);
        craftingRenderer.setPlayer(player);
        // The Combine button on the inventory's item-detail popup hands the chosen item
        // to the crafting screen, which pre-loads it into the first empty cell.
        inventoryRenderer.setOnCombine((user, item) -> craftingRenderer.openWith(item));

        lookMode = new LookMode(camera);
        lookMode.setPlayer(player);
        lookMode.setLevel(world.currentLevel());
        lookMode.setHistory(targetHistory);

        lookRenderer = new LookRenderer(uiSkin);
        lookRenderer.setPlayer(player);
        lookRenderer.setLevel(world.currentLevel());
        lookRenderer.setLookMode(lookMode);
        inventoryRenderer.setLookRenderer(lookRenderer);
        hudRenderer.setOverlays(inventoryRenderer, lookRenderer);
        hudRenderer.setLookMode(lookMode);
        hudRenderer.setPlayerSupplier(() -> TurnSystem.findPlayer(world.currentLevel()));
        hudRenderer.setActionBar(actionBar);
        // Action-slot binding now lives entirely on the inventory popup's quickslot
        // buttons — long-press has been removed (no-gesture rule), so the HUD action
        // tiles are tap-only "fire what's bound" surfaces.
        hudRenderer.setOnOpenInfo(() ->
                game.setScreen(new MapScreen(() -> game.setScreen(self), world)));
        hudRenderer.setCombineToggle(() -> {
            if (craftingRenderer != null) craftingRenderer.toggle();
        });

        characterStatsRenderer = new CharacterStatsRenderer(uiSkin);
        characterStatsRenderer.setPlayer(player);
        hudRenderer.setOnPortraitTap(() -> {
            Mob cur = TurnSystem.findPlayer(world.currentLevel());
            if (cur != null) characterStatsRenderer.setPlayer(cur);
            characterStatsRenderer.toggle();
        });

        encyclopediaRenderer = new com.bjsp123.rl2.ui.popup.EncyclopediaRenderer(uiSkin);
        hudRenderer.setOnOpenEncyclopedia(() -> encyclopediaRenderer.toggle());
        // Look popup's "?" info buttons (tile / mob / per-item) route through this
        // shared encyclopaedia, so a click on the look reticle's tile portrait can
        // open the encyclopedia pre-selected to that terrain entry.
        lookRenderer.setEncyclopedia(encyclopediaRenderer);
        // Inventory's item-detail popup also has a "?" info button under its icon.
        inventoryRenderer.setEncyclopedia(encyclopediaRenderer);

        // Z-order: HUD beneath the dialogs so popups draw over the bar.
        uiStage.addActor(hudRenderer);
        uiStage.addActor(inventoryRenderer);
        uiStage.addActor(craftingRenderer);
        uiStage.addActor(lookRenderer);
        uiStage.addActor(characterStatsRenderer);
        uiStage.addActor(encyclopediaRenderer);

        targetingOverlay = new TargetingOverlay();
        targetingOverlay.create();
        targetingOverlay.setPlayer(player);
        targetingOverlay.setLevel(world.currentLevel());
        targetingOverlay.setWorldCamera(camera);
        targetingOverlay.setHistory(targetHistory);

        // Build the action / firing / auto-move dispatcher now that every UI piece it
        // talks to (action bar, targeting, level renderer) is wired up. PlayController
        // closes over this::recenterCameraOnPlayer so it can recenter after stair
        // traversal without holding a back-reference to the screen.
        controller = new PlayController(world, animator, actionBar, targetingOverlay,
                levelRenderer, this::recenterCameraOnPlayer);
        if (newRun) controller.seedDefaultActionBar(player, charClass);
        inventoryRenderer.setOnThrow((thrower, item) -> controller.beginThrow(thrower, item));
        inventoryRenderer.setOnUse((user, item) -> controller.useItemFromInventory(user, item));
        hudRenderer.setOnActionUse(controller::triggerActionSlot);
        hudRenderer.setOnWait(controller::tryInteract);

        gameInput = new GameInput(world, camera, cameraController);
        gameInput.setStairHandlers(controller::tryStairsUp, controller::tryStairsDown);
        gameInput.setInteractHandler(controller::tryInteract);
        gameInput.setInventoryToggle(() -> {
            if (inventoryRenderer != null) inventoryRenderer.toggle();
        });
        gameInput.setLookToggle(() -> {
            if (lookMode != null) lookMode.toggle();
        });
        gameInput.setCharacterToggle(() -> {
            if (characterStatsRenderer != null) {
                Mob cur = TurnSystem.findPlayer(world.currentLevel());
                if (cur != null) characterStatsRenderer.setPlayer(cur);
                characterStatsRenderer.toggle();
            }
        });
        gameInput.setActionSlotHandler(controller::triggerActionSlot);

        camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.zoom = DEFAULT_ZOOM;
        recenterCameraOnPlayer();
    }

    /** Save the in-progress world to disk. Called on hide/pause. */
    public void persist() {
        if (world != null && TurnSystem.findPlayer(world.currentLevel()) != null) {
            game.saveSystem.save(saveSlot, world);
        }
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        Level level  = world.currentLevel();
        lookRenderer.setLevel(level);
        lookMode.setLevel(level);
        targetingOverlay.setLevel(level);

        // Sync the turn counter into the level so stateless logic (MobSystem.killMob and
        // friends) can time-stamp history entries without passing the World around.
        level.currentTurn = world.turn;

        Mob player = TurnSystem.findPlayer(level);
        if (player != null) {
            lookMode.setPlayer(player);
            lookRenderer.setPlayer(player);
            lastSnapshot = controller.snapshotOf(player);
        }

        boolean overlayOpen = isAnyPopupOpen();
        // Drain one frame off the animation gate BEFORE the tick check. This lets a
        // step that finishes "this frame" hand off to the next step in the same render
        // frame instead of leaving a one-frame stationary gap that reads as jerky.
        // The drain count matches the Animator's frames-per-render multiplier so the
        // freeze gate clears at the user-selected animation speed.
        animator.queue.tick(com.bjsp123.rl2.ui.skin.AnimationSpeed.framesPerRender());
        // Single gate: any visible game-action animation in progress (step interpolation,
        // attack lunge / flinch, projectile in flight, death flicker / fade) bumps
        // animator.queue.freezeFrames; we wait for it to drain before letting another tick
        // advance. Off-screen actions add 0, so they never hold up the world.
        boolean ticked = !overlayOpen && animator.queue.freezeFrames == 0 && controller.tick(level);
        if (ticked) {
            world.turn++;
            level.currentTurn = world.turn;
            hudRenderer.recordTick();
            // Per-standard-turn handlers (vegetation spread, fire spread, smoke emission,
            // …) are dispatched from TurnSystem.tick on every 100th call, so they keep a
            // stable game-time cadence regardless of player speed. Ember emission lives
            // on the real-time clock below.
            // Something moved / died / got picked up / lit up — fog + item/mob indexes are
            // potentially stale. Between ticks none of those change, so the renderer skips
            // the rebuilds and pixmap upload.
            levelRenderer.markDirty();
        }

        // Real-time cadence — runs every render frame regardless of the game-tick or
        // per-turn clocks, so visuals (effect frames, mob lunge animation, fire embers)
        // continue while the game is paused on input. Projectile-impact resolution and
        // effect-frame advancement are owned by the Animator below.
        // Cap the wall-clock delta at 100 ms so a frame hitch doesn't dump a backlog of
        // particles all at once.
        int dtMs = Math.min(100, (int) (Gdx.graphics.getDeltaTime() * 1000f));
        // Engine→renderer event drain plus the in-world animation tick. The Animator
        // owns per-mob anim state, the freeze gate, ghost flicker/fade, and the
        // periodic teleport / burning / sleep-Z particle cadences.
        animator.consume(level);
        animator.tick(level, dtMs);
        com.bjsp123.rl2.logic.FireSystem.tickRealTime(level, dtMs);
        com.bjsp123.rl2.logic.LevelSystem.tickLightMotesRealTime(level, dtMs);

        Mob playerAfter = TurnSystem.findPlayer(level);
        if (player != null && playerAfter == null && lastSnapshot != null) {
            game.hallOfFame.add(lastSnapshot);
            com.bjsp123.rl2.save.HallOfFameStore.save(game.persistence, game.hallOfFame);
            game.saveSystem.clear(saveSlot);
            game.currentPlay = null;
            dispose();
            game.setScreen(new GameOverScreen(game, lastSnapshot));
            return;
        }

        cameraController.followPlayer(playerAfter);
        camera.update();

        // Audit action slots EVERY frame, not just inside afterMove. Any path that consumes
        // an item (throw, eat, …) is supposed to call afterMove which already runs the
        // audit; this redundant call is a safety net for edge cases (e.g. an item that left
        // the inventory through a path I haven't anticipated) so a bound action button is
        // never showing a stale or empty reference for longer than one frame.
        controller.auditActionSlots(playerAfter);

        // Tell the renderer which mob (if any) the look cursor is over, so it can overlay
        // attitude markers on every visible mob and the looked-at mob's state of mind.
        // Cast is intentional — only DefaultLevelRenderer carries this annotation hook;
        // other LevelRenderer impls (test stubs) silently skip the overlay.
        if (levelRenderer instanceof DefaultLevelRenderer dlr) {
            dlr.setLookedAtMob(lookMode.isActive() ? lookMode.mobAtCursor() : null);
        }
        levelRenderer.render(level, camera);
        targetingOverlay.render();
        lookMode.render();

        // Refresh HUD state for the frame, then let scene2d act+draw the entire UI.
        hudRenderer.update(playerAfter, level.depth, world.turn, world.tick);
        uiStage.act(delta);
        uiStage.draw();
    }

    /** True iff any modal popup or input-claiming overlay is currently open.
     *  Used both as the game-tick gate (don't tick the world while a window
     *  is up) and as the camera input blocker (don't pan the map under a
     *  finger that's interacting with a popup). */
    private boolean isAnyPopupOpen() {
        if (inventoryRenderer != null && inventoryRenderer.isOpen()) return true;
        if (lookMode != null && lookMode.isActive()) return true;
        if (craftingRenderer != null && craftingRenderer.isOpen()) return true;
        if (hudRenderer != null && hudRenderer.isMenuOpen()) return true;
        if (targetingOverlay != null && targetingOverlay.isActive()) return true;
        if (characterStatsRenderer != null && characterStatsRenderer.isOpen()) return true;
        if (encyclopediaRenderer != null && encyclopediaRenderer.isOpen()) return true;
        return false;
    }

    private void recenterCameraOnPlayer() {
        Mob player = TurnSystem.findPlayer(world.currentLevel());
        if (player == null) return;
        camera.position.set(
            player.position.tileX() * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE * 0.5f,
            player.position.tileY() * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE * 0.5f,
            0);
        camera.update();
    }

    @Override
    public void resize(int width, int height) {
        if (camera == null) return;
        float oldZoom = camera.zoom;
        camera.setToOrtho(false, width, height);
        camera.zoom = oldZoom;
        if (uiStage != null) {
            ScreenViewport vp = (ScreenViewport) uiStage.getViewport();
            vp.setUnitsPerPixel(1f / (UiScale.scale() * UiPixelScale.scale()));
            vp.update(width, height, true);
        }
        recenterCameraOnPlayer();
    }

    public void cancelThrow() {
        if (targetingOverlay != null) targetingOverlay.cancel();
    }

    @Override public void pause()  { persist(); }
    @Override public void resume() {}

    @Override public void hide()   { persist(); }

    @Override
    public void dispose() {
        if (levelRenderer    != null) levelRenderer.dispose();
        if (inventoryRenderer != null) inventoryRenderer.dispose();
        if (targetingOverlay != null) targetingOverlay.dispose();
        if (lookMode         != null) lookMode.dispose();
        if (uiStage          != null) uiStage.dispose();
        if (uiSkin           != null) uiSkin.dispose();
        if (stoneUi          != null) stoneUi.dispose();
        levelRenderer = null;
        hudRenderer = null;
        inventoryRenderer = null;
        lookRenderer = null;
        targetingOverlay = null;
        lookMode = null;
        uiStage = null;
        uiSkin = null;
        stoneUi = null;
        initialized = false;
    }
}
