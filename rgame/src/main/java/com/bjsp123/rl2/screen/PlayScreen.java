package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
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
import com.bjsp123.rl2.ui.v2.V2CharacterStats;
import com.bjsp123.rl2.ui.v2.V2Look;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;
import com.bjsp123.rl2.ui.v2.V2Inventory;
import com.bjsp123.rl2.world.render.LevelRenderer;
import com.bjsp123.rl2.ui.overlay.TargetingOverlay;

import java.util.ArrayList;
import java.util.List;
import com.bjsp123.rl2.world.anim.Animator;

public class PlayScreen implements Screen {

    private static final float DEFAULT_ZOOM  = 0.35f;

    private final Rl2Game game;
    private final CharacterClass charClass;
    private final World preloadedWorld;
    public final int saveSlot;

    /** Player-supplied world seed for new runs. {@code null} => pick a random
     *  seed inside the {@link com.bjsp123.rl2.util.SeedCode} window. Ignored
     *  when {@link #preloadedWorld} is non-null (load preserves the saved seed). */
    private final Long requestedSeed;
    /** Pre-game-options god-mode flag - applied to the spawned player
     *  Mob in {@link #initialize}. Ignored on a loaded run (the flag is
     *  on the saved Mob itself in that case). */
    private final boolean godModeRequested;
    /** Pre-game-options starting character / dungeon level. {@code 1} is
     *  the standard new-run start; values up to {@link
     *  com.bjsp123.rl2.logic.GameBalance#DUNGEON_DEPTH} drop the player
     *  into the corresponding generated level and bump their character
     *  level to match. Ignored on a loaded run. */
    private final int startingLevel;
    /** Pre-game-options "all items" flag - seeds the spawned player's
     *  inventory with one of every non-unique item in the registry. */
    private final boolean allItemsRequested;
    /** Pre-game-options "+10 perk points" flag - adds 10 perk points to
     *  whatever the character normally starts with. */
    private final boolean tenPerkPointsRequested;

    private World world;
    /** Exposed so burger-menu items from other packages can construct level-info
     *  and map screens for the currently active run. */
    public World getWorld() { return world; }
    private OrthographicCamera camera;
    private CameraController cameraController;
    private LevelRenderer    levelRenderer;
    private com.bjsp123.rl2.ui.v2.V2Hud v2Hud;
    private V2Inventory v2Inventory;
    private com.bjsp123.rl2.ui.v2.V2Crafting v2Crafting;
    private V2Look      v2Look;
    private ActionBar         actionBar;
    private TargetingOverlay  targetingOverlay;
    private V2CharacterStats v2CharacterStats;
    private com.bjsp123.rl2.ui.v2.V2Encyclopedia v2Encyclopedia;
    private com.bjsp123.rl2.ui.v2.V2BuffInfo v2BuffInfo;
    private com.bjsp123.rl2.ui.v2.V2Log v2Log;
    /** Transient unlock-toast banner. Lives across the screen's lifetime;
     *  rendered above the HUD on every frame; advances its countdown via
     *  the same {@code dt} the animator consumes. */
    private com.bjsp123.rl2.ui.v2.AchievementToast achievementToast;
    /** Cached popup actors registered with the shared V2 stage on
     *  {@link #show()} and de-registered on {@link #hide()}. The Stage
     *  walks them in z-order each frame; PlayScreen no longer calls each
     *  popup's render directly - the v2Stage.act/draw pair on
     *  {@link #render(float)} does that work. */
    private com.badlogic.gdx.scenes.scene2d.Actor[] popupActors;
    private GameInput         gameInput;
    private LookMode          lookMode;
    /** In-world animator: drains {@code Level.events} after each {@code TurnSystem.tick}
     *  and maintains the per-mob {@link com.bjsp123.rl2.world.anim.MobAnimState} that the
     *  renderer reads. The single source of truth for the tick-gate freeze counter
     *  lives on {@link com.bjsp123.rl2.world.anim.AnimQueue}. */
    private final com.bjsp123.rl2.world.anim.Animator animator = new com.bjsp123.rl2.world.anim.Animator();
    private final FrameProfiler frameProfiler = new FrameProfiler();

    private boolean initialized;
    private HallOfFameEntry lastSnapshot;

    /** Shared cursor-memory for Look and targeting. Records the most recent mob and the
     *  most recent non-mob tile the player interacted with; {@link TargetHistory#pickInitial}
     *  turns that into a "best guess" starting cell for every new cursor-picking flow. */
    private final TargetHistory targetHistory = new TargetHistory();

    /** Action / firing / auto-move dispatcher. Holds every "user pressed a button ->
     *  mutate the level" code path; this screen keeps lifecycle + render orchestration
     *  and delegates everything else here. Built in {@link #initialize()}. */
    private PlayController controller;

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls) {
        this(game, slot, cls, null, false, 1, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls, Long seed) {
        this(game, slot, cls, seed, false, 1, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode) {
        this(game, slot, cls, seed, godMode, 1, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode, int startingLevel,
                      boolean allItems, boolean tenPerkPoints) {
        this.game = game;
        this.saveSlot = slot;
        this.charClass = cls;
        this.preloadedWorld = null;
        this.requestedSeed = seed;
        this.godModeRequested = godMode;
        this.startingLevel = startingLevel;
        this.allItemsRequested = allItems;
        this.tenPerkPointsRequested = tenPerkPoints;
    }

    public PlayScreen(Rl2Game game, int slot, World loadedWorld) {
        this.game = game;
        this.saveSlot = slot;
        this.charClass = null;
        this.preloadedWorld = loadedWorld;
        this.requestedSeed = null;
        // Loaded runs read godMode off the saved player Mob; this flag
        // is only consulted on a fresh new-game spawn.
        this.godModeRequested = false;
        this.startingLevel = 1;
        this.allItemsRequested = false;
        this.tenPerkPointsRequested = false;
    }

    @Override
    public void show() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
        // cameraController goes FIRST so mouse-wheel zoom and pinch-zoom are always
        // available regardless of UI focus state - opening the inventory or the
        // encyclopaedia must not lock the camera at the current zoom level. Wheel
        // events return true (consumed); touchDown / touchDragged return false so
        // they continue to uiStage and the action handlers below.
        // uiStage goes second so HUD buttons stay reachable even while Look or
        // Targeting is active - the stage returns false for clicks that don't land
        // on a widget, letting them fall through to the world cursors. This is what
        // lets a re-tap of the same action-slot button confirm an open targeting
        // session, or a tap on a different action swap the target picker to that
        // new action.
        // Block map drag/pinch/scroll while any modal is open - per the UI
        // rules, the topmost window owns input and the world behind it
        // shouldn't slide under the user's finger.
        cameraController.setInputBlocker(this::isAnyPopupOpen);
        // Input chain: cameraController -> V2 modal popups (each a no-op when
        // closed; consume when open) -> V2 HUD (corner buttons) -> world
        // handlers. V2 popups come BEFORE the HUD so an open popup eats taps
        // before a HUD button under it can fire.
        Gdx.input.setInputProcessor(new InputMultiplexer(
                cameraController,
                v2BuffInfo.input(),
                v2Inventory.input(),
                v2CharacterStats.input(),
                v2Crafting.input(),
                v2Encyclopedia.input(),
                v2Log.input(),
                v2Look.input(),
                v2Hud.input(),
                targetingOverlay, lookMode, gameInput));
        // Register the in-game popups with the shared V2 stage so their
        // render order is owned by the scenegraph, not by an implicit
        // call sequence in render(). Insertion order = back-to-front;
        // the inventory's item-detail sub-popup goes on the higher
        // {@code subPopupLayer} so its scrim covers the inventory text
        // cleanly when up.
        if (popupActors == null) {
            popupActors = new com.badlogic.gdx.scenes.scene2d.Actor[8];
            popupActors[0] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Inventory);
            popupActors[1] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2CharacterStats);
            popupActors[2] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Crafting);
            popupActors[3] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Look);
            popupActors[4] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Encyclopedia);
            popupActors[5] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(
                    v2Inventory.detailPopup());
            popupActors[6] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2BuffInfo);
            popupActors[7] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Log);
        }
        game.ui.v2Stage.add(popupActors[0]);
        game.ui.v2Stage.add(popupActors[1]);
        game.ui.v2Stage.add(popupActors[2]);
        game.ui.v2Stage.add(popupActors[3]);
        game.ui.v2Stage.add(popupActors[4]);
        game.ui.v2Stage.addToSubPopup(popupActors[5]);
        game.ui.v2Stage.addToSubPopup(popupActors[6]);
        game.ui.v2Stage.addToSubPopup(popupActors[7]);
        game.currentPlay = this;
        if (game.music != null) game.music.play(com.bjsp123.rl2.audio.MusicPlayer.Track.GAMEPLAY);
    }

    @Override
    public void hide() {
        persist();
        // Pull our popup actors back out of the shared stage so a
        // disposed PlayScreen doesn't leave dangling actors.
        if (popupActors != null) {
            for (com.badlogic.gdx.scenes.scene2d.Actor a : popupActors) {
                game.ui.v2Stage.remove(a);
            }
        }
    }

    private void initialize() {
        camera           = new OrthographicCamera();
        cameraController = new CameraController(camera);
        cameraController.setAnimator(animator);

        boolean newRun = (preloadedWorld == null);
        if (preloadedWorld != null) {
            world = preloadedWorld;
            for (Level l : world.levels) if (l != null) l.initTransients();
            world.linkLevels();
        } else {
            EventLog.clear();
            // World is procedurally generated - see WorldTopology.build. Depth and the
            // side-branch / crosslink probabilities come from GameBalance; every level's
            // stairs(Up|Down)(Alt)Target fields fully encode the resulting graph.
            // Allocate the World first so build() and every createDungeonLevel call
            // share the same UniqueTracker - that's how unique themed rooms only
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
            world.linkLevels();
            int startIdx = Math.max(0,
                    Math.min(levels.length - 1, startingLevel - 1));
            // Walk every level up to the start index as visited so the
            // map screen / topology reads them as already-explored.
            for (int i = 0; i <= startIdx; i++) {
                if (levels[i] != null) levels[i].visited = true;
            }
            world.currentLevelIndex = startIdx;
            Level startLevel = levels[startIdx];
            Point spawn = startLevel.spawnPoint != null ? startLevel.spawnPoint : new Point(2, 2);
            Mob player  = MobFactory.player(spawn, charClass);
            player.godMode = godModeRequested;
            if (startingLevel > 1) {
                com.bjsp123.rl2.logic.MobProgression.setSpawnLevel(player, startingLevel);
            }
            if (tenPerkPointsRequested) player.perkPoints += 10;
            if (allItemsRequested) grantOneOfEachItem(player);
            startLevel.mobs.add(player);
            String playerName = player.name != null ? player.name
                    : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
            EventLog.add(Messages.beginGame(playerName));
            EventLog.add(Messages.enterLevel(playerName, startLevel.depth, startLevel.flags));
        }

        Mob player = TurnSystem.findPlayer(world.currentLevel());
        LevelSystem.computeLighting(world.currentLevel());
        LevelSystem.updateVisibility(world.currentLevel());

        levelRenderer = new DefaultLevelRenderer();
        levelRenderer.create();
        levelRenderer.setWorld(world);
        levelRenderer.setAnimator(animator);

        // Achievement observation - every drained GameEvent gets a chance
        // to fire a "first X" achievement. The listener flashes the toast
        // banner + queues a HIGH-priority log line on each unlock.
        achievementToast = new com.bjsp123.rl2.ui.v2.AchievementToast(game.ui);
        if (game.achievementSystem != null) {
            animator.setEventObserver(game.achievementSystem::observeEvent);
            game.achievementSystem.setListener(this::onAchievementUnlocked);
        }
        if (game.sounds != null) animator.setSounds(game.sounds);

        // V2 HUD - primitive ShapeRenderer + SpriteBatch chrome, drawn directly
        // by this Screen. Game.ui is the shared V2 rendering context (one per
        // Game).
        v2Hud = new com.bjsp123.rl2.ui.v2.V2Hud(game.ui);
        v2Hud.setOnReturnToTitle(() -> {
            persist();
            game.setRootScreen(new com.bjsp123.rl2.ui.v2.V2Title(game, game.ui));
        });
        v2Hud.setOnOpenSettings(() ->
                game.pushScreen(new com.bjsp123.rl2.ui.v2.V2Settings(game, game.ui)));
        v2Hud.setOnOpenLevelInfo(() ->
                game.pushScreen(new com.bjsp123.rl2.ui.v2.V2LevelInfo(
                        game, game.ui,
                        game::popScreen,
                        world.currentLevel())));

        // HUD action bar - bound to the player Mob so quickslot assignments
        // persist across save/load: ActionBar mirrors the live slot Item
        // refs into {@link Mob#actionSlotTypes} on every set/clear, and
        // {@link ActionBar#bindToPlayer} restores live refs from the
        // saved type strings here.
        actionBar = new ActionBar();
        actionBar.bindToPlayer(player);

        // V2 inventory popup - primitive-drawn modal, drawn directly by this
        // Screen (not in uiStage). Reads the shared V2 rendering context off
        // the Game so the same fonts / palette as the HUD apply.
        v2Inventory = new V2Inventory(game.ui);
        v2Inventory.setPlayer(player);
        v2Inventory.setActionBar(actionBar);
        v2Inventory.setSounds(game.sounds);
        v2Crafting = new com.bjsp123.rl2.ui.v2.V2Crafting(game.ui);
        v2Crafting.setPlayer(player);
        if (game.achievementSystem != null) {
            v2Crafting.setOnCrafted(game.achievementSystem::observeCrafted);
        }
        // The Combine button on the inventory's item-detail popup hands the chosen item
        // to the crafting screen, which pre-loads it into the first empty cell.
        v2Inventory.setOnCombine((user, item) -> v2Crafting.openWith(item));

        lookMode = new LookMode(camera);
        lookMode.setPlayer(player);
        lookMode.setLevel(world.currentLevel());
        lookMode.setHistory(targetHistory);

        // V2 look popup - its open state follows lookMode.isActive(), so no
        // toggle is needed here. The HUD's Look button toggles lookMode and
        // V2Look reads from it on every frame.
        v2Look = new V2Look(game.ui);
        v2Look.setLevel(world.currentLevel());
        v2Look.setLookMode(lookMode);
        // V2 HUD doesn't track inv/look directly - it routes through callbacks
        // (onOpenInventory + onLook) which the binding below wires up. setPlayerSupplier
        // and setActionBar mirror the V1 HUD's API so the HUD always reads the live
        // player + bound action items.
        v2Hud.setPlayerSupplier(() -> TurnSystem.findPlayer(world.currentLevel()));
        v2Hud.setActionBar(actionBar);
        v2Hud.setOnOpenInventory(() -> {
            if (v2Inventory != null) v2Inventory.toggle();
        });
        v2Hud.setOnLook(() -> {
            if (lookMode != null) lookMode.toggle();
        });
        v2Hud.setOnOpenMap(() ->
                game.pushScreen(new com.bjsp123.rl2.ui.v2.V2Map(
                        game, game.ui, game::popScreen, world)));

        v2CharacterStats = new V2CharacterStats(game.ui);
        v2CharacterStats.setPlayer(player);
        v2Hud.setOnPortraitTap(() -> {
            Mob cur = TurnSystem.findPlayer(world.currentLevel());
            if (cur != null) v2CharacterStats.setPlayer(cur);
            v2CharacterStats.toggle();
        });

        v2Encyclopedia = new com.bjsp123.rl2.ui.v2.V2Encyclopedia(game.ui);
        v2Hud.setOnOpenEncyclopedia(() -> v2Encyclopedia.toggle());
        v2BuffInfo = new com.bjsp123.rl2.ui.v2.V2BuffInfo(game.ui);
        v2Hud.setOnBuffTap(buff -> { if (buff != null) v2BuffInfo.open(buff); });
        v2Log = new com.bjsp123.rl2.ui.v2.V2Log(game.ui);
        v2Log.setSounds(game.sounds);
        v2Hud.setOnOpenLog(() -> v2Log.toggle());
        v2CharacterStats.setBuffInfo(v2BuffInfo);
        v2Look.setBuffInfo(v2BuffInfo);
        // Inventory's item-detail info button jumps to the encyclopaedia
        // pre-selected to that item - closes inventory in the process so
        // the V2 single-popup-at-a-time rule is preserved.
        v2Inventory.setEncyclopedia(v2Encyclopedia);
        // Look popup's per-section info buttons route through the same
        // shared encyclopaedia.
        v2Look.setEncyclopedia(v2Encyclopedia);
        // Character-stats popup's per-perk info buttons jump to the
        // encyclopaedia's perk page.
        v2CharacterStats.setEncyclopedia(v2Encyclopedia);
        // The V1 path used to wire the Encyclopaedia into the Look popup's
        // "?" info buttons; the V2 look popup doesn't have those yet, so
        // there's nothing to wire here.

        // V2 popups are rendered DIRECTLY by this Screen (see render() below)
        // and route input through their own InputProcessors slotted into the
        // multiplexer. None of them are scene2d Actors, so uiStage stays
        // empty for now (kept around in case a follow-up V1 popup needs it).

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
                levelRenderer, this::recenterCameraOnPlayer, frameProfiler, game.sounds);
        if (newRun) controller.seedDefaultActionBar(player, charClass);
        v2Inventory.setOnThrow((thrower, item) -> controller.beginThrow(thrower, item));
        v2Inventory.setOnUse((user, item) -> controller.useItemFromInventory(user, item));
        v2Hud.setOnActionUse(controller::triggerActionSlot);

        gameInput = new GameInput(world, camera, cameraController);
        gameInput.setStairHandlers(controller::tryStairsUp, controller::tryStairsDown);
        gameInput.setInteractHandler(controller::tryInteract);
        gameInput.setInventoryToggle(() -> {
            if (v2Inventory != null) v2Inventory.toggle();
        });
        gameInput.setLookToggle(() -> {
            if (lookMode != null) lookMode.toggle();
        });
        gameInput.setCharacterToggle(() -> {
            if (v2CharacterStats != null) {
                Mob cur = TurnSystem.findPlayer(world.currentLevel());
                if (cur != null) v2CharacterStats.setPlayer(cur);
                v2CharacterStats.toggle();
            }
        });
        gameInput.setActionSlotHandler(controller::triggerActionSlot);

        camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.zoom = DEFAULT_ZOOM;
        recenterCameraOnPlayer();
    }

    /** Listener registered on {@link com.bjsp123.rl2.save.AchievementSystem}
     *  so each first-time unlock both flashes the toast banner and pushes
     *  a HIGH-priority log line. Fired synchronously from inside the
     *  unlock path. */
    private void onAchievementUnlocked(com.bjsp123.rl2.save.Achievement a) {
        if (a == null) return;
        if (achievementToast != null) {
            achievementToast.show(com.bjsp123.rl2.logic.TextCatalog.format(
                    "ui.achievement.toast",
                    com.bjsp123.rl2.logic.TextCatalog.vars("achievement", a.displayName())));
        }
        EventLog.add(com.bjsp123.rl2.logic.Messages.achievementUnlocked(a.displayName()));
    }

    /** Forward the current depth to {@link com.bjsp123.rl2.save.AchievementSystem#observeDepth}.
     *  Cheap no-op once every depth threshold is on the books. */
    private void checkDepthAchievements() {
        if (game.achievementSystem == null || world == null) return;
        com.bjsp123.rl2.model.Level cur = world.currentLevel();
        if (cur == null) return;
        game.achievementSystem.observeDepth(cur.depth);
    }

    /** Stuff one of every type in {@link com.bjsp123.rl2.logic.ItemRegistry}
     *  into {@code player}'s bag, equipping anything with a slot. Used by
     *  the "All items" debug option on character creation. */
    private static void grantOneOfEachItem(Mob player) {
        if (player == null || player.inventory == null) return;
        for (String type : com.bjsp123.rl2.logic.Registries.itemTypes()) {
            try {
                com.bjsp123.rl2.model.Item it =
                        com.bjsp123.rl2.logic.ItemFactory.build(type);
                player.inventory.bag.add(it);
                if (it.isEquippable()) {
                    com.bjsp123.rl2.logic.InventorySystem.equip(player.inventory, it);
                    player.statsDirty = true;
                }
            } catch (RuntimeException ignored) {
                // Defensive: skip any registry entry the factory refuses.
            }
        }
    }

    /** Save the in-progress world to disk. Called on hide/pause. */
    public void persist() {
        if (world != null && TurnSystem.findPlayer(world.currentLevel()) != null) {
            game.saveSystem.save(saveSlot, world);
        }
    }

    @Override
    public void render(float delta) {
        Level level  = world.currentLevel();
        frameProfiler.begin(delta, level, animator.queue.freezeFrames);

        long span = frameProfiler.start();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        frameProfiler.add("clear", span);

        span = frameProfiler.start();
        v2Look.setLevel(level);
        lookMode.setLevel(level);
        targetingOverlay.setLevel(level);

        // Sync the completed standard turn into the level so stateless logic
        // (MobSystem.killMob and friends) can time-stamp history entries without
        // passing the World around. The authoritative clock is world.tick.
        level.currentTurn = TurnSystem.standardTurnForTick(world.tick);

        Mob player = TurnSystem.findPlayer(level);
        if (player != null) {
            lookMode.setPlayer(player);
            // V2Look reads tile / mob / item info via cursor + level; player
            // ref isn't needed for it.
            lastSnapshot = controller.snapshotOf(player);
        }
        // Refresh look-popup level reference each frame (level can change on
        // stairs traversal).
        v2Look.setLevel(level);
        frameProfiler.add("frameSetup", span);

        boolean overlayOpen = isAnyPopupOpen();
        // Drain one frame off the animation gate BEFORE the tick check. This lets a
        // step that finishes "this frame" hand off to the next step in the same render
        // frame instead of leaving a one-frame stationary gap that reads as jerky.
        // The drain count matches the Animator's frames-per-render multiplier so the
        // freeze gate clears at the user-selected animation speed.
        span = frameProfiler.start();
        animator.queue.tick(com.bjsp123.rl2.ui.skin.Settings.framesPerRender());
        frameProfiler.add("queueTick", span);
        // Single gate: any visible game-action animation in progress (step interpolation,
        // attack lunge / flinch, projectile in flight, death flicker / fade) bumps
        // animator.queue.freezeFrames; we wait for it to drain before letting another tick
        // advance. Off-screen actions add 0, so they never hold up the world.
        boolean ticked = !overlayOpen
                && (com.bjsp123.rl2.ui.skin.Settings.instantActions() || animator.queue.freezeFrames == 0)
                && controller.tick(level);
        if (ticked) {
            level.currentTurn = TurnSystem.standardTurnForTick(world.tick);
            // Per-standard-turn handlers (vegetation spread, fire spread, smoke emission,
            // ...) are dispatched from TurnSystem.tick on every 100th call, so they keep a
            // stable game-time cadence regardless of player speed. Ember emission lives
            // on the real-time clock below.
            // Something moved / died / got picked up / lit up - fog + item/mob indexes are
            // potentially stale. Between ticks none of those change, so the renderer skips
            // the rebuilds and pixmap upload.
            levelRenderer.markDirty();
        }

        // Real-time cadence - runs every render frame regardless of the game-tick or
        // per-turn clocks, so visuals (effect frames, mob lunge animation, fire embers)
        // continue while the game is paused on input. Projectile-impact resolution and
        // effect-frame advancement are owned by the Animator below.
        // Cap the wall-clock delta at 100 ms so a frame hitch doesn't dump a backlog of
        // particles all at once.
        int dtMs = Math.min(100, (int) (Gdx.graphics.getDeltaTime() * 1000f));
        // Engine->renderer event drain plus the in-world animation tick. The Animator
        // owns per-mob anim state, the freeze gate, ghost flicker/fade, and the
        // periodic teleport / burning / sleep-Z particle cadences.
        animator.impactFiredThisTick = false;
        span = frameProfiler.start();
        animator.consume(level);
        frameProfiler.add("animConsume", span);
        span = frameProfiler.start();
        animator.tick(level, dtMs);
        frameProfiler.add("animTick", span);
        if (animator.impactFiredThisTick) {
            span = frameProfiler.start();
            levelRenderer.markDirty();
            if (controller != null) controller.afterMove(level);
            frameProfiler.add("impactAfterMove", span);
        }
        span = frameProfiler.start();
        com.bjsp123.rl2.logic.FireSystem.tickRealTime(level, dtMs);
        com.bjsp123.rl2.logic.LevelSystem.tickLightMotesRealTime(level, dtMs);
        frameProfiler.add("realtimeFx", span);

        span = frameProfiler.start();
        Mob playerAfter = TurnSystem.findPlayer(level);
        // Cross-run achievement poll - depth-threshold checks fire from
        // here so any path that changes the player's level (stairs,
        // chasm fall, debug "starting level") feeds the same poll.
        checkDepthAchievements();
        if (player != null && playerAfter == null && lastSnapshot != null) {
            java.util.List<com.bjsp123.rl2.model.LogEvent> logEntries = com.bjsp123.rl2.logic.EventLog.all();
            for (int i = logEntries.size() - 1; i >= 0; i--) {
                com.bjsp123.rl2.model.LogEvent le = logEntries.get(i);
                if (le.involvesPlayer) { lastSnapshot.deathMessage = le.text; break; }
            }
            game.hallOfFame.add(lastSnapshot);
            com.bjsp123.rl2.save.HallOfFameStore.save(game.persistence, game.hallOfFame);
            if (game.achievementSystem != null) {
                game.achievementSystem.observeRunEnded(lastSnapshot);
            }
            game.saveSystem.clear(saveSlot);
            game.currentPlay = null;
            dispose();
            // Game over is terminal - clear the back-stack so the user can't
            // pop back into a disposed PlayScreen.
            game.setRootScreen(new com.bjsp123.rl2.ui.v2.V2GameOver(game, lastSnapshot));
            frameProfiler.add("deathHandling", span);
            frameProfiler.finish(ticked, overlayOpen);
            return;
        }
        frameProfiler.add("deathCheck", span);

        span = frameProfiler.start();
        cameraController.followPlayer(playerAfter);
        camera.update();
        frameProfiler.add("camera", span);

        // Audit action slots EVERY frame, not just inside afterMove. Any path that consumes
        // an item (throw, eat, ...) is supposed to call afterMove which already runs the
        // audit; this redundant call is a safety net for edge cases (e.g. an item that left
        // the inventory through a path I haven't anticipated) so a bound action button is
        // never showing a stale or empty reference for longer than one frame.
        span = frameProfiler.start();
        controller.auditActionSlots(playerAfter);
        frameProfiler.add("auditSlots", span);

        // Tell the renderer which mob (if any) the look cursor is over, so it can overlay
        // attitude markers on every visible mob and the looked-at mob's state of mind.
        // Cast is intentional - only DefaultLevelRenderer carries this annotation hook;
        // other LevelRenderer impls (test stubs) silently skip the overlay.
        span = frameProfiler.start();
        if (levelRenderer instanceof DefaultLevelRenderer dlr) {
            dlr.setLookedAtMob(lookMode.isActive() ? lookMode.mobAtCursor() : null);
        }
        levelRenderer.render(level, camera);
        frameProfiler.add("levelRender", span);
        if (levelRenderer instanceof DefaultLevelRenderer dlr) {
            com.bjsp123.rl2.world.render.DefaultLevelRenderer.RenderStats rs = dlr.lastRenderStats();
            frameProfiler.addMetric("lr.fl", String.valueOf(rs.totalFlushes));
            frameProfiler.addMetric("lr.p1", fmtPass(rs.nsPass1, rs.flushPass1));
            frameProfiler.addMetric("lr.s",  fmtPass(rs.nsSurface, rs.flushSurface));
            frameProfiler.addMetric("lr.p3", fmtPass(rs.nsPass3, rs.flushPass3));
            frameProfiler.addMetric("lr.p4", fmtPass(rs.nsPass4, rs.flushPass4));
            frameProfiler.addMetric("lr.fog",fmtPass(rs.nsFog, rs.flushFog));
        }
        span = frameProfiler.start();
        targetingOverlay.render();
        frameProfiler.add("targetingRender", span);
        span = frameProfiler.start();
        lookMode.render();
        frameProfiler.add("lookRender", span);

        // V2 HUD chrome - drawn between the world and the popups so popups
        // (inventory, look, etc.) overlay the HUD correctly. Switches
        // projection to V2's camera internally.
        span = frameProfiler.start();
        v2Hud.update(level.depth, animator.visualClockTick(level, world.tick));
        frameProfiler.add("hudUpdate", span);
        span = frameProfiler.start();
        v2Hud.render();
        frameProfiler.add("hudRender", span);

        // Achievement toast - sits above the HUD strip and below modal
        // popups. Renders nothing when no unlock is active.
        span = frameProfiler.start();
        if (achievementToast != null) achievementToast.render(dtMs);
        frameProfiler.add("toast", span);

        // Popup layer - the V2 stage walks the registered popup actors
        // in z-order. Each {@link com.bjsp123.rl2.ui.v2.stage.V2PopupActor}
        // mirrors its popup's isOpen() onto its visibility, so closed
        // popups are skipped. The inventory's item-detail sub-popup
        // sits on a higher z-layer so its scrim cleanly hides the
        // inventory's text behind it.
        float dtSec = Gdx.graphics.getDeltaTime();
        span = frameProfiler.start();
        game.ui.v2Stage.act(dtSec);
        frameProfiler.add("stageAct", span);
        span = frameProfiler.start();
        game.ui.v2Stage.draw();
        frameProfiler.add("stageDraw", span);
        if (com.bjsp123.rl2.ui.skin.Settings.showPerfOverlay()) renderPerfOverlay();
        frameProfiler.finish(ticked, overlayOpen);
    }

    /** True iff any modal popup or input-claiming overlay is currently open.
     *  Used both as the game-tick gate (don't tick the world while a window
     *  is up) and as the camera input blocker (don't pan the map under a
     *  finger that's interacting with a popup). */
    private boolean isAnyPopupOpen() {
        if (v2Inventory != null && v2Inventory.isOpen()) return true;
        if (lookMode != null && lookMode.isActive()) return true;
        if (v2Crafting != null && v2Crafting.isOpen()) return true;
        if (v2Hud != null && v2Hud.isMenuOpen()) return true;
        if (targetingOverlay != null && targetingOverlay.isActive()) return true;
        if (v2CharacterStats != null && v2CharacterStats.isOpen()) return true;
        if (v2Encyclopedia != null && v2Encyclopedia.isOpen()) return true;
        if (v2BuffInfo != null && v2BuffInfo.isOpen()) return true;
        if (v2Log != null && v2Log.isOpen()) return true;
        return false;
    }

    private void renderPerfOverlay() {
        game.ui.applyProjection();
        com.badlogic.gdx.graphics.g2d.SpriteBatch b = game.ui.batch;
        com.badlogic.gdx.graphics.g2d.BitmapFont  f = game.ui.fontRegular;
        float x = 6f;
        float y = game.ui.worldH() - 6f;
        b.begin();
        f.setColor(1f, 1f, 0.2f, 1f);
        f.draw(b, String.format(java.util.Locale.ROOT,
                "FPS:%.0f  CLR:%.1fms  REN:%.1fms  LGC:%.1fms",
                frameProfiler.snapFps(), frameProfiler.snapClearMs(),
                frameProfiler.snapRenderMs(), frameProfiler.snapLogicMs()), x, y);
        f.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        b.end();
    }

    private static String fmtPass(long ns, int flushes) {
        return String.format(java.util.Locale.ROOT, "%.1fms/%d", ns / 1_000_000.0, flushes);
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
        recenterCameraOnPlayer();
        // Forward to the V2 UI viewport too - without this the HUD,
        // popups, and burger menu keep their original size while the
        // window grows / shrinks, so chrome ends up stretched (or
        // crammed off-screen) instead of just repositioned.
        if (game != null && game.ui != null) {
            game.ui.resize(width, height);
        }
    }

    public void cancelThrow() {
        if (targetingOverlay != null) targetingOverlay.cancel();
    }

    @Override public void pause()  { persist(); }
    @Override public void resume() {}


    @Override
    public void dispose() {
        if (levelRenderer    != null) levelRenderer.dispose();
        // V2 inventory has no GPU resources of its own - dispose via UiCtx.
        if (targetingOverlay != null) targetingOverlay.dispose();
        if (lookMode         != null) lookMode.dispose();
        // Drop the achievement listener so a stale callback can't fire
        // into a torn-down PlayScreen.
        if (game.achievementSystem != null) {
            game.achievementSystem.clearListener();
        }
        if (achievementToast != null) achievementToast.clear();
        levelRenderer = null;
        v2Hud = null;
        v2Inventory = null;
        v2Look = null;
        targetingOverlay = null;
        lookMode = null;
        achievementToast = null;
        initialized = false;
    }
}
