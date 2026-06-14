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
    /** Pre-game-options "All scrolls" flag - seeds the bag with one of
     *  every GEM-category crafted scroll item. */
    private final boolean allScrollsRequested;
    /** Pre-game-options "+10 perk points" flag - adds 10 perk points to
     *  whatever the character normally starts with. */
    private final boolean tenPerkPointsRequested;
    /** Pre-game-options "reveal whole world" flag - marks every tile on
     *  every level explored, flips every beacon to active, and adds 10
     *  extra teleport orbs to the starting inventory so the player can
     *  immediately use the network. */
    private final boolean revealWholeWorldRequested;
    /** Pre-game-options "start on Landing" flag - drops the player on the
     *  endgame Landing floor instead of depth 1 / startingLevel. Overrides
     *  startingLevel when true. Temporary while we iterate on the endgame
     *  floors; defaults true in V2CharacterSelect. */
    private final boolean startOnLandingRequested;

    private World world;
    /** Exposed so burger-menu items from other packages can construct level-info
     *  and map screens for the currently active run. */
    public World getWorld() { return world; }
    private OrthographicCamera camera;
    private CameraController cameraController;
    private LevelRenderer    levelRenderer;
    private com.bjsp123.rl2.ui.v2.V2Hud v2Hud;
    private V2Inventory v2Inventory;
    private V2Look      v2Look;
    private ActionBar         actionBar;
    private TargetingOverlay  targetingOverlay;
    private V2CharacterStats v2CharacterStats;
    private com.bjsp123.rl2.ui.v2.V2Encyclopedia v2Encyclopedia;
    private com.bjsp123.rl2.ui.v2.V2BuffInfo v2BuffInfo;
    private com.bjsp123.rl2.ui.v2.V2Log v2Log;
    private com.bjsp123.rl2.ui.v2.V2TipPopup v2TipPopup;

    /** Input adapter slotted FIRST in the multiplexer (right after the
     *  camera controller). Consumes a tap when the tip popup is showing,
     *  dismissing it before the tap reaches the world / HUD layers. Lets
     *  through normally otherwise. */
    private com.badlogic.gdx.InputAdapter tipPopupDismissInput() {
        return new com.badlogic.gdx.InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (v2TipPopup == null) return false;
                float vx = game.ui.unprojectX(sx, sy);
                float vy = game.ui.unprojectY(sx, sy);
                return v2TipPopup.handleClick(vx, vy);
            }
        };
    }

    /** Walk {@link com.bjsp123.rl2.model.Level#mobs} once per frame and
     *  trigger the first-encounter tip for every mob whose tile is in the
     *  player's FOV. The tip-system's dedupe set ensures each species fires
     *  at most once per character; faster than caching state per-mob here. */
    private void scanForMobTips(com.bjsp123.rl2.model.Level level) {
        if (level == null || level.mobs == null) return;
        com.bjsp123.rl2.model.Mob player = com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        if (player == null || player.position == null) return;
        int px = player.position.tileX(), py = player.position.tileY();
        // Only fire mob tips when the mob is adjacent to the player. Earlier
        // we triggered on simple visibility, but a distant new species
        // spotted across the room would fire the tip without the player
        // having anything to act on yet. Adjacency means "it's actually in
        // your face" - that's when the tip is relevant and well-timed.
        for (com.bjsp123.rl2.model.Mob mob : level.mobs) {
            if (mob == null || mob.position == null || mob.mobType == null) continue;
            if (mob.behavior == com.bjsp123.rl2.model.Mob.Behavior.PLAYER) continue;
            int mx = mob.position.tileX(), my = mob.position.tileY();
            int cheb = Math.max(Math.abs(mx - px), Math.abs(my - py));
            if (cheb != 1) continue;
            com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                    "mob:" + mob.mobType,
                    "mob." + mob.mobType + ".tip",
                    "mob." + mob.mobType + ".name",
                    com.bjsp123.rl2.world.render.MobSprites.regionFor(mob));
        }
        // Concept tips for adjacency: walk the 8 neighbours of the player
        // tile (plus the player's own tile for stair-adjacency where the
        // player IS on the stair). Once per concept per run.
        scanForAdjacencyTips(level);
    }

    /** Fire stairs / beacon concept tips when the player stands on or next
     *  to those tiles for the first time this run. */
    private void scanForAdjacencyTips(com.bjsp123.rl2.model.Level level) {
        com.bjsp123.rl2.model.Mob player = com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        if (player == null || player.position == null) return;
        int px = player.position.tileX(), py = player.position.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = px + dx, y = py + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                com.bjsp123.rl2.model.Tile t = level.tiles[x][y];
                if (t == com.bjsp123.rl2.model.Tile.STAIRS_UP
                        || t == com.bjsp123.rl2.model.Tile.STAIRS_DOWN) {
                    com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                            "concept:stairs", "concept.stairs.tip",
                            "concept.stairs.name", null);
                } else if (t == com.bjsp123.rl2.model.Tile.BEACON_INACTIVE
                        || t == com.bjsp123.rl2.model.Tile.BEACON_ACTIVE) {
                    com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                            "concept:beacon", "concept.beacon.tip",
                            "concept.beacon.name", null);
                }
            }
        }
    }
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

    /** Latched once the player is detected gone from {@code level.mobs}. While true,
     *  the world tick is suspended (no more AI actions) but the Animator keeps
     *  draining events and advancing frames so the killing attack's lunge / flinch
     *  and the player's death flicker / fade play to completion before the
     *  V2GameOver screen takes over. The actual screen transition fires only when
     *  {@link com.bjsp123.rl2.world.anim.Animator#playerDeathAnimComplete()}
     *  reports the ghost has finished fading and no freeze frames or pending
     *  impacts remain. */
    private boolean deathTransitionPending;

    /** Slow-mo factor for the killing-blow animation. The Animator's
     *  {@code framesPerRender} is transiently overridden to this value when
     *  the player dies, so the ghost flicker / fade plays in slow motion.
     *  Cleared on transition to the death screen. */
    private static final float DEATH_SLOWMO_FACTOR = 0.30f;

    /** Number of render frames over which the post-death screen-fade ramps
     *  from transparent to fully black. Starts only once the slow-mo death
     *  animation has fully drained. */
    private static final int DEATH_FADE_FRAMES = 60;

    /** Counts up each render frame AFTER the in-world death animation has
     *  completed (ghost faded, freeze frames drained). When this reaches
     *  {@link #DEATH_FADE_FRAMES} the V2GameOver transition fires. The fade
     *  overlay's alpha is {@code deathFadeFrame / DEATH_FADE_FRAMES}. */
    private int deathFadeFrame;

    /** Shared cursor-memory for Look and targeting. Records the most recent mob and the
     *  most recent non-mob tile the player interacted with; {@link TargetHistory#pickInitial}
     *  turns that into a "best guess" starting cell for every new cursor-picking flow. */
    private final TargetHistory targetHistory = new TargetHistory();

    /** Action / firing / auto-move dispatcher. Holds every "user pressed a button ->
     *  mutate the level" code path; this screen keeps lifecycle + render orchestration
     *  and delegates everything else here. Built in {@link #initialize()}. */
    private PlayController controller;

    /** Teleport the player to a beacon. Thin wrapper around the
     *  package-private {@link PlayController#teleportToBeacon} so UI screens
     *  outside the screen package (e.g. V2Map) can trigger a beacon
     *  teleport. */
    public boolean teleportToBeacon(int destLevelIdx, com.bjsp123.rl2.model.Point beacon) {
        if (controller == null) return false;
        return controller.teleportToBeacon(destLevelIdx, beacon);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls) {
        this(game, slot, cls, null, false, 1, false, false, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls, Long seed) {
        this(game, slot, cls, seed, false, 1, false, false, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode) {
        this(game, slot, cls, seed, godMode, 1, false, false, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode, int startingLevel,
                      boolean allItems, boolean tenPerkPoints) {
        this(game, slot, cls, seed, godMode, startingLevel, allItems, tenPerkPoints, false, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode, int startingLevel,
                      boolean allItems, boolean tenPerkPoints,
                      boolean revealWholeWorld) {
        this(game, slot, cls, seed, godMode, startingLevel, allItems, tenPerkPoints, revealWholeWorld, false, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode, int startingLevel,
                      boolean allItems, boolean tenPerkPoints,
                      boolean revealWholeWorld, boolean startOnLanding) {
        this(game, slot, cls, seed, godMode, startingLevel, allItems, tenPerkPoints, revealWholeWorld, startOnLanding, false);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode, int startingLevel,
                      boolean allItems, boolean tenPerkPoints,
                      boolean revealWholeWorld, boolean startOnLanding,
                      boolean allScrolls) {
        this.game = game;
        this.saveSlot = slot;
        this.charClass = cls;
        this.preloadedWorld = null;
        this.requestedSeed = seed;
        this.godModeRequested = godMode;
        this.startingLevel = startingLevel;
        this.allItemsRequested = allItems;
        this.allScrollsRequested = allScrolls;
        this.tenPerkPointsRequested = tenPerkPoints;
        this.revealWholeWorldRequested = revealWholeWorld;
        this.startOnLandingRequested = startOnLanding;
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
        this.allScrollsRequested = false;
        this.tenPerkPointsRequested = false;
        this.revealWholeWorldRequested = false;
        this.startOnLandingRequested = false;
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
                tipPopupDismissInput(),
                v2BuffInfo.input(),
                v2Inventory.input(),
                v2CharacterStats.input(),
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
            popupActors = new com.badlogic.gdx.scenes.scene2d.Actor[7];
            popupActors[0] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Inventory);
            popupActors[1] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2CharacterStats);
            popupActors[2] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Look);
            popupActors[3] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Encyclopedia);
            popupActors[4] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(
                    v2Inventory.detailPopup());
            popupActors[5] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2BuffInfo);
            popupActors[6] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Log);
        }
        game.ui.v2Stage.add(popupActors[0]);
        game.ui.v2Stage.add(popupActors[1]);
        game.ui.v2Stage.add(popupActors[2]);
        game.ui.v2Stage.add(popupActors[3]);
        game.ui.v2Stage.addToSubPopup(popupActors[4]);
        game.ui.v2Stage.addToSubPopup(popupActors[5]);
        game.ui.v2Stage.addToSubPopup(popupActors[6]);
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
            // Pre-game-options override: drop the player on the Landing
            // floor (first endgame floor) rather than the depth-based
            // startingLevel. Scans the level array for the LANDING kind.
            if (startOnLandingRequested) {
                for (int i = 0; i < levels.length; i++) {
                    if (levels[i] != null
                            && levels[i].kind == Level.LevelKind.LANDING) {
                        startIdx = i;
                        break;
                    }
                }
            }
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
            if (allScrollsRequested) grantOneOfEachScroll(player);
            if (revealWholeWorldRequested) revealWholeWorld(player, levels);
            startLevel.mobs.add(player);
            String playerName = player.name != null ? player.name
                    : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
            EventLog.add(Messages.beginGame(playerName));
            EventLog.add(Messages.enterLevel(playerName, startLevel.depth, startLevel.flags));
            // Start-of-run audio sting. Fires once per run (initialize() is
            // guarded by `initialized`). Honors SFX mute/volume via SoundManager.
            if (game.sounds != null) game.sounds.play("sfx.game.start");
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
            com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                    "concept:inventory", "concept.inventory.tip",
                    "concept.inventory.name", null);
        });
        v2Hud.setOnLook(() -> {
            if (lookMode != null) lookMode.toggle();
        });
        v2Hud.setOnOpenMap(() -> {
            game.pushScreen(new com.bjsp123.rl2.ui.v2.V2Map(
                    game, game.ui, game::popScreen, world));
            com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                    "concept:map", "concept.map.tip", "concept.map.name", null);
        });

        v2CharacterStats = new V2CharacterStats(game.ui);
        v2CharacterStats.setPlayer(player);
        v2Hud.setOnPortraitTap(() -> {
            Mob cur = TurnSystem.findPlayer(world.currentLevel());
            if (cur != null) v2CharacterStats.setPlayer(cur);
            v2CharacterStats.toggle();
            com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                    "concept:perks", "concept.perks.tip", "concept.perks.name", null);
        });

        v2Encyclopedia = new com.bjsp123.rl2.ui.v2.V2Encyclopedia(game.ui);
        v2Hud.setOnOpenEncyclopedia(() -> v2Encyclopedia.toggle());
        v2BuffInfo = new com.bjsp123.rl2.ui.v2.V2BuffInfo(game.ui);
        v2Hud.setOnBuffTap(buff -> { if (buff != null) v2BuffInfo.open(buff); });
        v2Log = new com.bjsp123.rl2.ui.v2.V2Log(game.ui);
        v2Log.setSounds(game.sounds);
        v2Hud.setSounds(game.sounds);
        // Animator → HUD callback: when the player takes a damaging hit while
        // already below 20% HP, flash the HUD red and play the low-HP warn
        // sfx. Threshold is computed POST-hit, so a blow that drops the
        // player from 22% → 18% triggers.
        animator.setOnPlayerLowHpHit(() -> v2Hud.triggerLowHpHitFlash());
        v2Hud.setOnOpenLog(() -> v2Log.toggle());
        // Tip popup - non-blocking first-encounter overlay. Renders over the
        // world, ticks its own auto-fade timer, dismisses on click anywhere.
        v2TipPopup = new com.bjsp123.rl2.ui.v2.V2TipPopup(game.ui);
        com.bjsp123.rl2.ui.v2.TipSystem.setPopup(v2TipPopup);
        com.bjsp123.rl2.ui.v2.TipSystem.reset();
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
        targetingOverlay.setUiCtx(game.ui);

        // Build the action / firing / auto-move dispatcher now that every UI piece it
        // talks to (action bar, targeting, level renderer) is wired up. PlayController
        // closes over this::recenterCameraOnPlayer so it can recenter after stair
        // traversal without holding a back-reference to the screen.
        controller = new PlayController(world, animator, actionBar, targetingOverlay,
                levelRenderer, this::recenterCameraOnPlayer, frameProfiler, game.sounds);
        if (newRun) controller.seedDefaultActionBar(player, charClass);
        v2Inventory.setOnThrow((thrower, item) -> controller.beginThrow(thrower, item));
        v2Inventory.setOnUse((user, item) -> controller.useItemFromInventory(user, item));
        controller.setOpenMapScreen(() -> game.pushScreen(new com.bjsp123.rl2.ui.v2.V2Map(
                game, game.ui, game::popScreen, world)));
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

    /** Seed {@code player}'s bag with one of every GEM-category crafted
     *  scroll item (the 24 gem-hearth recipes). Gems go in the bag rather
     *  than auto-equipping since there are only three gem slots. Used by
     *  the "All scrolls" debug option on character creation. */
    private static void grantOneOfEachScroll(Mob player) {
        if (player == null || player.inventory == null) return;
        for (String type : com.bjsp123.rl2.logic.Registries.itemTypesMatching(
                def -> def.inventoryCategory
                        == com.bjsp123.rl2.model.Item.InventoryCategory.GEM)) {
            try {
                com.bjsp123.rl2.model.Item it =
                        com.bjsp123.rl2.logic.ItemFactory.build(type);
                player.inventory.bag.add(it);
            } catch (RuntimeException ignored) {
                // Defensive: skip any registry entry the factory refuses.
            }
        }
    }

    /** "Reveal whole world" debug option: mark every tile of every level
     *  explored + visited, flip every inactive beacon to active so it's
     *  immediately a teleport target on the map, and stock the player
     *  with 10 extra teleport orbs on top of the class default. */
    private static void revealWholeWorld(Mob player, Level[] levels) {
        if (levels == null) return;
        for (Level lvl : levels) {
            if (lvl == null) continue;
            lvl.visited = true;
            if (lvl.explored != null) {
                for (int x = 0; x < lvl.width; x++) {
                    for (int y = 0; y < lvl.height; y++) lvl.explored[x][y] = true;
                }
            }
            if (lvl.tiles != null) {
                for (int x = 0; x < lvl.width; x++) {
                    for (int y = 0; y < lvl.height; y++) {
                        if (lvl.tiles[x][y] == com.bjsp123.rl2.model.Tile.BEACON_INACTIVE) {
                            lvl.tiles[x][y] = com.bjsp123.rl2.model.Tile.BEACON_ACTIVE;
                        }
                    }
                }
            }
        }
        if (player != null && player.inventory != null) {
            for (int i = 0; i < 10; i++) {
                try {
                    com.bjsp123.rl2.model.Item orb =
                            com.bjsp123.rl2.logic.ItemFactory.build("TELEPORT_ORB");
                    com.bjsp123.rl2.logic.InventorySystem.addToBag(player.inventory, orb);
                } catch (RuntimeException ignored) { /* registry missing - skip */ }
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
        // Additionally hold on pending projectile impacts: when freeze hits 0 on a
        // projectile's final frame, animator.tick (run later in this same frame)
        // fires the impact. If we ticked the world now, the target mob would get a
        // free move BEFORE the impact applies and the projectile would land where
        // they used to be. Waiting one more frame lets the impact resolve first.
        boolean ticked = !overlayOpen
                && !deathTransitionPending
                && (com.bjsp123.rl2.ui.skin.Settings.instantActions()
                        || (animator.queue.freezeFrames == 0 && !animator.hasPendingImpacts()))
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
        // First-encounter tips: scan visible mobs each frame and queue a
        // tip for any new species the player can see (or is adjacent to).
        // Same dedupe applies via TipSystem - one tip per species per run.
        scanForMobTips(level);
        span = frameProfiler.start();
        com.bjsp123.rl2.logic.FireSystem.tickRealTime(level, dtMs);
        com.bjsp123.rl2.logic.LevelSystem.tickLightMotesRealTime(level, dtMs);
        frameProfiler.add("realtimeFx", span);

        span = frameProfiler.start();
        // Look for the player on the CURRENT level, not the frame-start `level`: a chasm
        // fall or cross-level teleport during controller.tick() moves the player (and
        // world.currentLevelIndex) to another level. Using the stale `level` here would
        // find no player and fire a false "game over" even though the player is alive on
        // the new level (a silent death with no log / death message).
        Mob playerAfter = TurnSystem.findPlayer(world.currentLevel());
        // Cross-run achievement poll - depth-threshold checks fire from
        // here so any path that changes the player's level (stairs,
        // chasm fall, debug "starting level") feeds the same poll.
        checkDepthAchievements();
        // First frame the player has been removed from level.mobs: capture
        // the death snapshot + clear the save (irreversible bookkeeping) and
        // LATCH the V2GameOver transition. The screen swap itself waits for
        // the killing attack's lunge / flinch + the death flicker / fade to
        // finish - see deathTransitionPending javadoc.
        if (player != null && playerAfter == null && lastSnapshot != null
                && !deathTransitionPending) {
            // Walk the rolling event log oldest -> newest and pull the last
            // five player-involved entries. The very last one is the cause
            // of death; the four before it are the lead-up (incoming hits,
            // missed potions, walked-into-chasm, etc.). The V2GameOver
            // screen renders the whole sequence so the player sees how the
            // run actually ended, not just one cryptic line.
            java.util.List<com.bjsp123.rl2.model.LogEvent> logEntries = com.bjsp123.rl2.logic.EventLog.all();
            java.util.ArrayList<String> recent = new java.util.ArrayList<>();
            for (int i = logEntries.size() - 1; i >= 0 && recent.size() < 5; i--) {
                com.bjsp123.rl2.model.LogEvent le = logEntries.get(i);
                if (le == null || le.text == null) continue;
                if (!le.involvesPlayer) continue;
                recent.add(le.text);
            }
            java.util.Collections.reverse(recent);
            lastSnapshot.deathLog = recent;
            // Keep the legacy single-line field populated as a fallback for
            // any UI / save reader that still reads it; equals the last
            // entry of deathLog (the cause).
            if (!recent.isEmpty()) {
                lastSnapshot.deathMessage = recent.get(recent.size() - 1);
            }
            // Compose the "what killed you" headline from the last fatal
            // DamageCause captured by MobSystem.processAttack; rendered as
            // the top frame on V2GameOver.
            String headlineClass = player.characterClass != null
                    ? player.characterClass.displayName() : "Adventurer";
            lastSnapshot.deathHeadline = com.bjsp123.rl2.logic.Messages.deathHeadline(
                    level, headlineClass,
                    com.bjsp123.rl2.logic.MobSystem.lastPlayerCause(),
                    com.bjsp123.rl2.logic.MobSystem.lastPlayerElement());
            com.bjsp123.rl2.logic.MobSystem.resetLastPlayerHit();
            game.hallOfFame.add(lastSnapshot);
            com.bjsp123.rl2.save.HallOfFameStore.save(game.persistence, game.hallOfFame);
            if (game.achievementSystem != null) {
                game.achievementSystem.observeRunEnded(lastSnapshot);
            }
            game.saveSystem.clear(saveSlot);
            game.currentPlay = null;
            deathTransitionPending = true;
            deathFadeFrame = 0;
            // Killing-blow cinematic: drop the animation speed for the next
            // few seconds so the player's ghost flicker / fade plays in slow
            // motion. Cleared just before the V2GameOver transition fires.
            com.bjsp123.rl2.ui.skin.Settings.setAnimationTransientOverride(DEATH_SLOWMO_FACTOR);
        }
        // Latched: wait for the death animation to drain, then ramp the
        // black fade overlay, then transition. The fade frame counter only
        // increments AFTER the in-world death visuals (slide, ghost
        // flicker, fade) have settled, so the player sees their character
        // die in slow motion before the screen fades to black.
        boolean deathAnimDone = deathTransitionPending
                && animator.queue.freezeFrames == 0
                && !animator.hasPendingImpacts()
                && animator.playerDeathAnimComplete();
        if (deathAnimDone) {
            deathFadeFrame++;
        }
        if (deathTransitionPending && deathFadeFrame >= DEATH_FADE_FRAMES) {
            com.bjsp123.rl2.ui.skin.Settings.setAnimationTransientOverride(0f);
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

        // Tip popup - ticks its own 5-second fade timer in real-time, renders
        // centred above the middle of the screen. Non-blocking: world + HUD
        // stay live underneath.
        if (v2TipPopup != null) {
            v2TipPopup.tick(com.badlogic.gdx.Gdx.graphics.getDeltaTime());
            v2TipPopup.renderSelf();
        }

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

        // Death cinematic - opaque-ramping black overlay over the entire
        // viewport once the in-world death anim has finished. Drawn AFTER
        // the v2Stage so it covers HUD + popups, BEFORE the perf overlay
        // so debug text stays readable through the fade.
        if (deathTransitionPending && deathFadeFrame > 0) {
            float alpha = Math.min(1f, deathFadeFrame / (float) DEATH_FADE_FRAMES);
            drawDeathFadeOverlay(alpha);
        }

        if (com.bjsp123.rl2.ui.skin.Settings.showPerfOverlay()) renderPerfOverlay();
        frameProfiler.finish(ticked, overlayOpen);
    }

    private void drawDeathFadeOverlay(float alpha) {
        if (alpha <= 0f) return;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = game.ui.shapes;
        s.setProjectionMatrix(game.ui.camera.combined);
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(
                com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, alpha);
        s.rect(0f, 0f, game.ui.worldW(), game.ui.worldH());
        s.end();
        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
    }

    /** True iff any modal popup or input-claiming overlay is currently open.
     *  Used both as the game-tick gate (don't tick the world while a window
     *  is up) and as the camera input blocker (don't pan the map under a
     *  finger that's interacting with a popup). */
    private boolean isAnyPopupOpen() {
        if (v2Inventory != null && v2Inventory.isOpen()) return true;
        if (lookMode != null && lookMode.isActive()) return true;
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
