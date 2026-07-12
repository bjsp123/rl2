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
import com.bjsp123.rl2.ui.v2.UIVars;

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
    /** Pre-game-options difficulty for a fresh run. Applied to the global
     *  GameBalance multipliers in {@link #initialize()} and recorded on the
     *  World. Ignored on a loaded run (the saved World carries its own). */
    private final com.bjsp123.rl2.logic.GameBalance.Difficulty requestedDifficulty;

    private World world;
    /** Exposed so burger-menu items from other packages can construct level-info
     *  and map screens for the currently active run. */
    public World getWorld() { return world; }
    private OrthographicCamera camera;
    private CameraController cameraController;
    private LevelRenderer    levelRenderer;
    private com.bjsp123.rl2.ui.v2.V2Hud v2Hud;
    private V2Inventory v2Inventory;
    private com.bjsp123.rl2.ui.v2.ConfirmPopup confirmPopup;
    private V2Look      v2Look;
    private ActionBar         actionBar;
    private TargetingOverlay  targetingOverlay;
    private V2CharacterStats v2CharacterStats;
    private com.bjsp123.rl2.ui.v2.V2Encyclopedia v2Encyclopedia;
    private com.bjsp123.rl2.ui.v2.V2BuffInfo v2BuffInfo;
    private com.bjsp123.rl2.ui.v2.V2Log v2Log;
    private com.bjsp123.rl2.ui.v2.V2TipPopup v2TipPopup;
    private com.bjsp123.rl2.ui.v2.V2GameStartTipPopup v2GameStartTipPopup;

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

    /** Input adapter placed at the head of the multiplexer for the
     *  game-start tip phase. While {@link #introTipPhase} is set the popup
     *  is modal: every touch goes to its arrow / dismiss handler and never
     *  reaches downstream processors (notably {@link #introSkipInput} which
     *  would otherwise end the intro early). */
    private com.badlogic.gdx.InputAdapter gameStartTipInput() {
        return new com.badlogic.gdx.InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!introTipPhase || v2GameStartTipPopup == null) return false;
                float vx = game.ui.unprojectX(sx, sy);
                float vy = game.ui.unprojectY(sx, sy);
                v2GameStartTipPopup.handleClick(vx, vy);
                return true;
            }
            @Override public boolean keyDown(int k) { return introTipPhase; }
            @Override public boolean scrolled(float ax, float ay) { return introTipPhase; }
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
            if (mob.isPlayer) continue;
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
     *  overlay's alpha is {@code deathFadeFrame / DEATH_FADE_FRAMES}. Advanced
     *  by the dt-scaled frame delta so the fade lasts the same wall-clock time
     *  at any refresh rate. */
    private float deathFadeFrame;

    /** Victory transition (RL-19): latched when the player steps onto the escape
     *  stairs after defeating the Great Wraith. Mirrors the death transition but
     *  the player is alive, so it gold-fades to the VICTORY screen. */
    private boolean victoryTransitionPending;
    /** Counts up once the victory transition is latched; the V2GameOver (victory)
     *  swap fires at {@link #DEATH_FADE_FRAMES}. */
    private float victoryFadeFrame;

    /** Jade Peach revive cinematic (>=0 while playing): the level fades to black,
     *  the peach appears over a sunburst and bursts, then the level fades back in.
     *  The game is frozen for the duration. -1 = inactive. */
    private float reviveCinematicFrame = -1;
    private static final int REVIVE_FADE_OUT  = 14;  // black ramps in
    private static final int REVIVE_GROW_END  = 44;  // peach finished growing
    private static final int REVIVE_EXPLODE   = 50;  // peach bursts
    private static final int REVIVE_FADE_IN   = 60;  // black starts ramping out
    private static final int REVIVE_TOTAL     = 84;  // cinematic ends

    /** Camera zoom the death outro pulls back to as the screen fades (RL-56).
     *  Larger = more zoomed out; the pull-back mirrors the intro zoom-in. */
    private static final float DEATH_ZOOM_OUT = 1.4f;

    // --- Intro cinematic (RL-56, Stage 1: camera zoom-in + arrival message) --
    /** Camera zoom the new-game intro starts at (far out) before easing down to
     *  {@link #DEFAULT_ZOOM}. Larger = more zoomed out. */
    private static final float INTRO_START_ZOOM = 2.5f;
    /** Real-time length of the intro zoom-in, in seconds. */
    private static final float INTRO_DURATION_SEC = 1.4f;
    /** True from a fresh new-game spawn until the intro zoom completes or the
     *  player taps to skip. While set, the world is paused and gameplay input is
     *  swallowed by {@link #introSkipInput()}. Never set for resumed runs. */
    private boolean introActive;
    /** Real-time accumulator (seconds) driving the intro zoom ease. */
    private float introTime;
    /** Total length of the world-graph -> level transition, in seconds: a quick
     *  dip to black (graph half fades out, level half fades in). Capture-free so
     *  it works on every GL backend. */
    private static final float GRAPH_FADE_SEC = 0.25f;
    /** Real-time accumulator (seconds) for the graph-side fade-to-black, while
     *  still in {@link #introGraphPhase}. */
    private float graphFadeTime;
    /** True while the level-side fade-in from black is running (after the graph
     *  has dipped to black and control passed to the level phase). */
    private boolean introFadeActive;
    /** True while the game-start tip window is up - sits between the graph
     *  fly-in and the level zoom-in. World, HUD and input are inert; the
     *  popup is modal and any click on it (other than its prev/next arrows)
     *  dismisses, after which {@link #introFadeActive} arms the level
     *  zoom-in. Never set for resumed runs or when {@code tip.csv} is
     *  empty / tips are disabled. */
    private boolean introTipPhase;
    /** Real-time accumulator (seconds) driving the level-side fade-in. */
    private float introFadeTime;

    // --- Intro stage 4: world-graph prefix -----------------------------------
    /** Shared world-graph renderer for the intro fly-in (same look as the map
     *  screen). Drawn full-screen while {@link #introGraphPhase} is set. */
    private final com.bjsp123.rl2.ui.v2.WorldGraphView introGraph =
            new com.bjsp123.rl2.ui.v2.WorldGraphView();
    /** First sub-phase of the new-game intro: show the world graph and fly into
     *  the current-level node. Followed by the level zoom-in ({@link #introActive}
     *  with this false). Never set for resumed runs. */
    private boolean introGraphPhase;
    /** Real-time accumulator (seconds) driving the graph fly-in. */
    private float graphIntroTime;
    /** Length of the world-graph fly-in, in seconds. */
    private static final float GRAPH_INTRO_SEC = 1.8f;
    /** Graph zoom at the start of the fly-in (whole graph visible) and end
     *  (current node fills the view). Larger = more zoomed in. */
    private static final float GRAPH_ZOOM_START = 0.6f;
    private static final float GRAPH_ZOOM_END   = 2.6f;

    // --- Outro cinematic (RL-58): touching the exit-portal beacon zooms out
    //     from the level to the world map, dissolves the world to white from
    //     the deepest level up (beacons explode, levels flash + ring), lightens
    //     the swirl to full white, then shows the victory screen. ---
    private boolean outroActive;
    private float   outroTime;
    /** World-graph zoom-out + white-dissolve duration. */
    private static final float OUTRO_GRAPH_SEC  = 2.2f;
    /** Final ramp to a fully white screen after the dissolve. */
    private static final float OUTRO_WHITE_SEC  = 1.0f;
    private static final float OUTRO_ZOOM_START = GRAPH_ZOOM_END;   // focused on the exit node
    private static final float OUTRO_ZOOM_END   = GRAPH_ZOOM_START;  // whole world visible

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
        this(game, slot, cls, seed, godMode, startingLevel, allItems, tenPerkPoints,
                revealWholeWorld, startOnLanding, allScrolls,
                com.bjsp123.rl2.logic.GameBalance.Difficulty.NORMAL);
    }

    public PlayScreen(Rl2Game game, int slot, CharacterClass cls,
                      Long seed, boolean godMode, int startingLevel,
                      boolean allItems, boolean tenPerkPoints,
                      boolean revealWholeWorld, boolean startOnLanding,
                      boolean allScrolls,
                      com.bjsp123.rl2.logic.GameBalance.Difficulty difficulty) {
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
        this.requestedDifficulty = difficulty != null
                ? difficulty : com.bjsp123.rl2.logic.GameBalance.Difficulty.NORMAL;
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
        this.requestedDifficulty = com.bjsp123.rl2.logic.GameBalance.Difficulty.NORMAL;
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
        // they continue to the V2 popup / HUD input processors and the action
        // handlers below.
        // The V2 HUD input sits late in the multiplexer so HUD buttons stay
        // reachable even while Look or Targeting is active - each popup / HUD
        // processor returns false for clicks that don't land on a widget,
        // letting them fall through to the world cursors. This is what lets a
        // re-tap of the same action-slot button confirm an open targeting
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
                gameStartTipInput(),
                introSkipInput(),
                confirmPopup.input(),
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
            popupActors = new com.badlogic.gdx.scenes.scene2d.Actor[8];
            popupActors[0] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Inventory);
            popupActors[1] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2CharacterStats);
            popupActors[2] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Look);
            popupActors[3] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Encyclopedia);
            popupActors[4] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(
                    v2Inventory.detailPopup());
            popupActors[5] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2BuffInfo);
            popupActors[6] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(v2Log);
            popupActors[7] = new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(confirmPopup);
        }
        game.ui.v2Stage.add(popupActors[0]);
        game.ui.v2Stage.add(popupActors[1]);
        game.ui.v2Stage.add(popupActors[2]);
        game.ui.v2Stage.add(popupActors[3]);
        game.ui.v2Stage.addToSubPopup(popupActors[4]);
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
            // Re-apply the saved run's difficulty so its multipliers are live.
            com.bjsp123.rl2.logic.GameBalance.applyDifficulty(world.difficulty);
            // The gem-equip slots were retired: move any gems a pre-change save
            // left equipped back into the bag so they aren't stranded.
            migrateEquippedGemsToBag(world);
        } else {
            EventLog.clear();
            // World is procedurally generated - see WorldTopology.build. Depth and the
            // side-branch / crosslink probabilities come from GameBalance; every level's
            // stairs(Up|Down)(Alt)Target fields fully encode the resulting graph.
            // Allocate the World first so build() and every createDungeonLevel call
            // share the same UniqueTracker - that's how unique themed rooms only
            // appear once per game.
            world = new World();
            // Apply difficulty BEFORE world-build so every mob created during
            // generation gets the difficulty-scaled HP at full health.
            world.difficulty = requestedDifficulty;
            com.bjsp123.rl2.logic.GameBalance.applyDifficulty(requestedDifficulty);
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
            // Difficulty revive charms (Jade Peaches) - the only source of them.
            grantReviveCharms(player, com.bjsp123.rl2.logic.GameBalance.tuning().startingReviveCharms());
            if (allItemsRequested) grantOneOfEachItem(player);
            if (allScrollsRequested) grantOneOfEachScroll(player);
            if (revealWholeWorldRequested) revealWholeWorld(player, levels);
            startLevel.mobs.add(player);
            // Initial placement must trigger the same arrival side effects a
            // stair-descent would (seal-on-entry stairs, final-boss spawn), so
            // the "start at level" debug option can drop the player straight onto
            // a special / boss floor and have it behave correctly.
            com.bjsp123.rl2.logic.MobLifecycle.applyLevelEntryEffects(startLevel, player);
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

        // V2 inventory popup - primitive-drawn modal, wrapped as a V2PopupActor
        // on the shared game.ui.v2Stage (drawn by the stage's act/draw pair).
        // Reads the shared V2 rendering context off the Game so the same
        // fonts / palette as the HUD apply.
        v2Inventory = new V2Inventory(game.ui);
        v2Inventory.setPlayer(player);
        v2Inventory.setActionBar(actionBar);
        v2Inventory.setSounds(game.sounds);

        // Shared confirmation modal (e.g. recycle-at-forge). Configured + opened
        // on demand via controller.setConfirmRequest below.
        confirmPopup = new com.bjsp123.rl2.ui.v2.ConfirmPopup(game.ui);

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
        // Game-start tip - modal window shown once at the top of every new
        // run between the world-graph fly-in and the level zoom-in.
        v2GameStartTipPopup = new com.bjsp123.rl2.ui.v2.V2GameStartTipPopup(game.ui);
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

        // V2 popups route input through their own InputProcessors slotted into
        // the multiplexer above, and are rendered by wrapping each as a
        // V2PopupActor on the shared game.ui.v2Stage (registered just below and
        // drawn by the stage's act/draw pair in render()).

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
        v2Inventory.setOnDrop((user, item) -> controller.dropItem(user, item));
        v2Inventory.setOnUse((user, item) -> controller.useItemFromInventory(user, item));
        controller.setItemPicker((eligible, onPick, onCancel) ->
                v2Inventory.openPicker(eligible, onPick, onCancel));
        controller.setConfirmRequest((title, message, onConfirm) -> {
            confirmPopup.configure(title, message, "Recycle", "Cancel", onConfirm);
            confirmPopup.open();
        });
        // Beacon-triggered map: teleport enabled (walked into / waited beside a beacon).
        controller.setOpenMapScreen(() -> game.pushScreen(new com.bjsp123.rl2.ui.v2.V2Map(
                game, game.ui, game::popScreen, world, /*teleportEnabled=*/true)));
        controller.setOpenForgeScreen(() -> game.pushScreen(new com.bjsp123.rl2.ui.v2.V2Forge(
                game, game.ui, game::popScreen,
                // Recycle: leave the forge and open the inventory picker to choose
                // an item to break down into gems.
                () -> { game.popScreen(); controller.openRecyclePicker(); })));
        v2Hud.setOnActionUse(controller::triggerActionSlot);
        v2Hud.setOnAutoExplore(controller::toggleAutoExplore);

        gameInput = new GameInput(world, camera, cameraController);
        gameInput.setStairHandlers(controller::tryStairsUp, controller::tryStairsDown);
        gameInput.setInteractHandler(controller::tryInteract);
        gameInput.setBeaconActivate(controller::openBeaconMap);
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
        gameInput.setAutoExploreActive(controller::isAutoExploring);
        gameInput.setOnCancelAuto(controller::cancelAutoActions);

        camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        // Fresh runs (no preloaded world) open on the intro cinematic: start far
        // out and ease in to play zoom. Resumed runs jump straight to play.
        introActive = (preloadedWorld == null);
        introGraphPhase = introActive;   // graph fly-in precedes the level zoom-in
        introTime = 0f;
        graphIntroTime = 0f;
        graphFadeTime = 0f;
        introFadeActive = false;
        introFadeTime = 0f;
        introTipPhase = false;
        camera.zoom = introActive ? INTRO_START_ZOOM : DEFAULT_ZOOM;
        recenterCameraOnPlayer();
    }

    /** Listener registered on {@link com.bjsp123.rl2.save.AchievementSystem}
     *  so each first-time unlock both flashes the toast banner and pushes
     *  a HIGH-priority log line. Fired synchronously from inside the
     *  unlock path. */
    private void onAchievementUnlocked(com.bjsp123.rl2.save.Achievements.Achievement a) {
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

    /** Save migration: the gem equip-slot system was retired. Move any gems a
     *  pre-change save left in {@code inventory.gems[]} back into the bag and
     *  clear the slots, for every mob in the world. */
    private static void migrateEquippedGemsToBag(World world) {
        if (world == null || world.levels == null) return;
        for (Level l : world.levels) {
            if (l == null || l.mobs == null) continue;
            for (Mob m : l.mobs) {
                if (m == null || m.inventory == null || m.inventory.gems == null) continue;
                com.bjsp123.rl2.model.Item[] gems = m.inventory.gems;
                for (int i = 0; i < gems.length; i++) {
                    if (gems[i] != null) {
                        com.bjsp123.rl2.logic.InventorySystem.addToBag(m.inventory, gems[i]);
                        gems[i] = null;
                    }
                }
            }
        }
    }

    /** Grant {@code n} Jade Peach revive charms into the bag (difficulty levels).
     *  They stack, so this lands as a single bag entry with count {@code n}. */
    private static void grantReviveCharms(Mob player, int n) {
        if (player == null || player.inventory == null || n <= 0) return;
        for (int i = 0; i < n; i++) {
            try {
                com.bjsp123.rl2.model.Item charm =
                        com.bjsp123.rl2.logic.ItemFactory.build("JADE_PEACH");
                com.bjsp123.rl2.logic.InventorySystem.addToBag(player.inventory, charm);
            } catch (RuntimeException ignored) { /* registry missing JADE_PEACH */ }
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
        // Intro stage 4: world-graph fly-in plays before anything else, on its
        // own minimal render path (graph only, no world / HUD / profiler).
        if (introActive && introGraphPhase) {
            renderIntroGraph(delta);
            return;
        }
        if (outroActive) {
            renderOutro(delta);
            return;
        }
        if (introActive && introTipPhase) {
            renderIntroTip(delta);
            return;
        }
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
        //
        // Cap the wall-clock delta at 100 ms so a frame hitch doesn't dump a
        // backlog of particles all at once. Computed here (above the gate) so the
        // freeze drain and the Animator's anim advance share the same dt-scaled
        // frame delta - both must pace on real time so a 120 Hz display doesn't
        // clear the gate (and run animations) twice as fast as a 60 Hz one.
        int dtMs = Math.min(100, (int) (Gdx.graphics.getDeltaTime() * 1000f));
        span = frameProfiler.start();
        animator.queue.tick(com.bjsp123.rl2.world.anim.Animator.frameDelta(dtMs));
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
                && reviveCinematicFrame < 0
                && !introActive
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
        // effect-frame advancement are owned by the Animator below. dtMs is
        // computed above (shared with the freeze-gate drain).
        // Engine->renderer event drain plus the in-world animation tick. The Animator
        // owns per-mob anim state, the freeze gate, ghost flicker/fade, and the
        // periodic teleport / burning / sleep-Z particle cadences.
        animator.impactFiredThisTick = false;
        span = frameProfiler.start();
        animator.consume(level);
        frameProfiler.add("animConsume", span);
        // Jade Peach revive: kick off the full-screen cinematic when the engine
        // signals a revive (player stays alive; the game freezes for the show).
        if (animator.playerRevivedSignal) {
            animator.playerRevivedSignal = false;
            if (reviveCinematicFrame < 0) reviveCinematicFrame = 0;
        }
        if (reviveCinematicFrame >= 0) {
            reviveCinematicFrame += com.bjsp123.rl2.world.anim.Animator.frameDelta(dtMs);
            if (reviveCinematicFrame >= REVIVE_TOTAL) reviveCinematicFrame = -1;
        }
        span = frameProfiler.start();
        animator.tick(level, dtMs);
        frameProfiler.add("animTick", span);
        if (animator.impactFiredThisTick) {
            span = frameProfiler.start();
            levelRenderer.markDirty();
            if (controller != null) controller.afterMove(level);
            frameProfiler.add("impactAfterMove", span);
        }
        // First-encounter tips: queue a tip for any new species adjacent to the
        // player. Adjacency only changes when something moved, so the scan (O(mobs)
        // walk + tip-key string building) runs on ticks and impact resolutions,
        // not every render frame. Same dedupe applies via TipSystem.
        if (ticked || animator.impactFiredThisTick) scanForMobTips(level);
        span = frameProfiler.start();
        com.bjsp123.rl2.logic.FireSystem.tickRealTime(level, dtMs);
        // Lamp/beacon/hearth motes are pure ambience - skipped in fast graphics.
        if (!com.bjsp123.rl2.ui.skin.Settings.fastGraphics()) {
            com.bjsp123.rl2.logic.LevelSystem.tickLightMotesRealTime(level, dtMs);
        }
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
        // Victory (RL-19): killing the Great Wraith opens stairs down to the
        // exit-portal floor; touching (stepping adjacent to) its beacon
        // (lockedExit) starts the end-sequence. Latched before the death check
        // and mutually exclusive with it (the player is alive here).
        if (playerAfter != null && !victoryTransitionPending && !deathTransitionPending
                && level.kind == com.bjsp123.rl2.model.Level.LevelKind.EXIT_PORTAL
                && level.lockedExit != null
                && playerAfter.position != null
                && Math.max(Math.abs(playerAfter.position.tileX() - level.lockedExit.tileX()),
                            Math.abs(playerAfter.position.tileY() - level.lockedExit.tileY())) <= 1) {
            latchVictory(level, playerAfter);
        }

        // First frame the player has been removed from level.mobs: capture
        // the death snapshot + clear the save (irreversible bookkeeping) and
        // LATCH the V2GameOver transition. The screen swap itself waits for
        // the killing attack's lunge / flinch + the death flicker / fade to
        // finish - see deathTransitionPending javadoc.
        if (player != null && playerAfter == null && lastSnapshot != null
                && !deathTransitionPending && !victoryTransitionPending) {
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
            // Death outro: a column of light + rising sparkles erupts from the
            // fallen player's tile, beneath the slow-mo death anim and zoom-out.
            if (animator != null) {
                com.bjsp123.rl2.world.render.Effect.columnOfLight(
                        animator.stage, player.position, animator.rng());
            }
            // Player-death sting for the outro cinematic (distinct from the
            // in-combat death event sound, sfx.player.combat.die).
            if (game.sounds != null) game.sounds.play("sfx.player.death");
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
            deathFadeFrame += com.bjsp123.rl2.world.anim.Animator.frameDelta(dtMs);
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
        // Victory (RL-19): no in-world death anim to wait on - the player is
        // alive on the escape stairs. Ramp the gold fade immediately, then swap
        // to the VICTORY screen.
        if (victoryTransitionPending) {
            victoryFadeFrame += com.bjsp123.rl2.world.anim.Animator.frameDelta(dtMs);
            if (victoryFadeFrame >= DEATH_FADE_FRAMES) {
                com.bjsp123.rl2.ui.skin.Settings.setAnimationTransientOverride(0f);
                dispose();
                game.setRootScreen(new com.bjsp123.rl2.ui.v2.V2GameOver(game, lastSnapshot));
                frameProfiler.add("deathHandling", span);
                frameProfiler.finish(ticked, overlayOpen);
                return;
            }
        }
        frameProfiler.add("deathCheck", span);

        span = frameProfiler.start();
        cameraController.followPlayer(playerAfter);
        camera.update();
        // Intro overrides the follow camera: ease zoom from far-out to play
        // zoom, locked on the player, until done or skipped.
        if (introActive) updateIntro(delta);
        // Death outro: pull the camera back as the screen fades to black.
        if (deathTransitionPending) updateDeathCamera();
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
        // Render the CURRENT level, not the one captured at the top of the frame:
        // controller.tick() above may have taken stairs and swapped levels, in which
        // case the local `level` is the level we just left. Drawing it for a frame
        // (with the new level's camera/visibility) shows the prior room - or, for a
        // trees room, the grass front-veg without the trunk/canopy - until the next
        // tick. The renderer rebuilds its per-level caches when the instance changes.
        levelRenderer.render(world.currentLevel(), camera);
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
        v2Hud.setHazard(level.hazardLevel);
        v2Hud.setAutoExploreActive(controller.isAutoExploring());
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
        // Victory cinematic - the same ramp but a warm gold wash instead of
        // black, so the win reads as triumph rather than death.
        if (victoryTransitionPending && victoryFadeFrame > 0) {
            float alpha = Math.min(1f, victoryFadeFrame / (float) DEATH_FADE_FRAMES);
            drawVictoryFadeOverlay(alpha);
        }
        // Jade Peach revive cinematic - fade to black, the peach blooms over a
        // sunburst and bursts, then the level fades back in.
        if (reviveCinematicFrame >= 0) {
            drawReviveCinematic((int) reviveCinematicFrame);
        }

        // Intro arrival message - a bottom banner naming the goal, shown over
        // the world while the camera eases in. Drawn last so it sits on top.
        if (introActive) drawIntroMessage();

        // Graph -> level transition, level half: the level fades in from black
        // (the graph dipped to black first). Drawn over everything.
        if (introFadeActive) {
            introFadeTime += delta;
            float a = 1f - Math.min(1f, introFadeTime / (GRAPH_FADE_SEC * 0.5f));
            if (a <= 0f) {
                introFadeActive = false;
            } else {
                drawBlackOverlay(a);
            }
        }

        if (com.bjsp123.rl2.ui.skin.Settings.showPerfOverlay()) renderPerfOverlay();
        frameProfiler.finish(ticked, overlayOpen);
    }

    /** Advance the intro zoom toward {@link #DEFAULT_ZOOM}, keeping the camera
     *  centred on the player. Ends the intro when the ease completes. */
    private void updateIntro(float delta) {
        introTime += delta;
        float t = INTRO_DURATION_SEC <= 0f ? 1f
                : Math.min(1f, introTime / INTRO_DURATION_SEC);
        float eased = t * t * (3f - 2f * t); // smoothstep
        camera.zoom = INTRO_START_ZOOM + (DEFAULT_ZOOM - INTRO_START_ZOOM) * eased;
        recenterCameraOnPlayer();
        if (t >= 1f) endIntro();
    }

    /** Intro stage 4 render path: draw the world graph full-screen and fly the
     *  view from "whole graph visible" into the current-level node, then hand
     *  off (crossfade into the level zoom-in). Runs instead of the normal frame
     *  while {@link #introGraphPhase} is set; the world stays paused. */
    private void renderIntroGraph(float delta) {
        graphIntroTime += delta;
        float t = GRAPH_INTRO_SEC <= 0f ? 1f
                : Math.min(1f, graphIntroTime / GRAPH_INTRO_SEC);
        float eased = t * t * (3f - 2f * t); // smoothstep

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        com.bjsp123.rl2.ui.v2.UiCtx ctx = game.ui;
        com.bjsp123.rl2.ui.v2.Rect full =
                new com.bjsp123.rl2.ui.v2.Rect(0f, 0f, ctx.worldW(), ctx.worldH());
        introGraph.world        = world;
        introGraph.layoutArea   = full;
        introGraph.viewport     = full;
        introGraph.zoom         = GRAPH_ZOOM_START + (GRAPH_ZOOM_END - GRAPH_ZOOM_START) * eased;
        introGraph.selected     = -1;
        introGraph.currentIndex = world.currentLevelIndex;
        introGraph.swirlT      += delta;
        introGraph.beaconPulseT += delta;
        float[] p = introGraph.panToCenter(world.currentLevelIndex);
        introGraph.panX = p[0];
        introGraph.panY = p[1];

        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setProjectionMatrix(ctx.camera.combined);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        introGraph.draw(ctx);
        s.end();

        ctx.applyProjection();
        ctx.batch.begin();
        introGraph.drawUnvisitedGlyphs(ctx);
        ctx.batch.end();

        if (t >= 1f) {
            if (willShowGameStartTip()) {
                // A tip follows: hand straight over with the swirl still showing,
                // so the backdrop keeps moving into the tip phase instead of
                // flashing to black between the graph and the tip.
                endGraphPhase();
            } else {
                // No tip - dip the graph to black over the first half of the
                // transition, then hand off to the level (fades in from black).
                graphFadeTime += delta;
                float k = Math.min(1f, graphFadeTime / (GRAPH_FADE_SEC * 0.5f));
                drawBlackOverlay(k);
                if (k >= 1f) endGraphPhase();
            }
        }
    }

    /** Full-screen black overlay at {@code alpha} (0=clear, 1=opaque). Used for
     *  the intro graph->level dip-to-black transition. */
    private void drawBlackOverlay(float alpha) {
        if (alpha <= 0f) return;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = game.ui.shapes;
        s.setProjectionMatrix(game.ui.camera.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, Math.min(1f, alpha));
        s.rect(0f, 0f, game.ui.worldW(), game.ui.worldH());
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Hand off from the (now black) graph fly-in to the level zoom-in: arm the
     *  level camera at its far zoom and either show a game-start tip (modal,
     *  level zoom-in deferred until the player dismisses it) or start the
     *  level-side fade-in from black immediately. */
    private void endGraphPhase() {
        introGraphPhase = false;
        introTime = 0f;
        camera.zoom = INTRO_START_ZOOM;
        recenterCameraOnPlayer();
        if (com.bjsp123.rl2.ui.skin.Settings.tipsEnabled()
                && v2GameStartTipPopup != null
                && !com.bjsp123.rl2.GameStartTipsRegistry.isEmpty()) {
            boolean shown = v2GameStartTipPopup.show(
                    new java.util.Random(), this::endGameStartTipPhase);
            if (shown) {
                introTipPhase = true;
                return;
            }
        }
        beginLevelZoomIn();
    }

    /** Tip-window dismiss callback - arm the level zoom-in fade-from-black
     *  the same way {@link #endGraphPhase} does in the no-tip path. */
    private void endGameStartTipPhase() {
        introTipPhase = false;
        beginLevelZoomIn();
    }

    /** Arm the level (second) zoom-in fade-from-black and play its audio sting.
     *  Shared by the no-tip ({@link #endGraphPhase}) and post-tip
     *  ({@link #endGameStartTipPhase}) paths so the second zoom-in sounds exactly
     *  once per run, whichever path armed it. */
    private void beginLevelZoomIn() {
        introFadeActive = true;
        introFadeTime = 0f;
        if (game.sounds != null) game.sounds.play("sfx.game.zoomin");
    }

    /** Game-start tip phase render path: keep the swirling void backdrop alive
     *  behind the modal tip window (the same swirl the graph fly-in used), so the
     *  background stays in motion right up until the tip is dismissed. World stays
     *  paused; the tip popup's input adapter swallows every tap. */
    private void renderIntroTip(float delta) {
        introGraph.swirlT += delta;
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        com.bjsp123.rl2.ui.v2.UiCtx ctx = game.ui;
        float w = ctx.worldW(), h = ctx.worldH();
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.setProjectionMatrix(ctx.camera.combined);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0.04f, 0.04f, 0.07f, 1f);         // dark void base
        s.rect(0f, 0f, w, h);
        s.end();
        ctx.batch.setProjectionMatrix(ctx.camera.combined);
        ctx.batch.begin();
        com.bjsp123.rl2.ui.v2.SwirlBackground.render(ctx.batch, 0f, 0f, w, h, introGraph.swirlT);
        ctx.batch.end();
        if (v2GameStartTipPopup != null) v2GameStartTipPopup.renderSelf();
    }

    /** Whether a game-start tip will actually be shown after the graph fly-in -
     *  used to skip the dip-to-black so the swirl flows seamlessly into the tip. */
    private boolean willShowGameStartTip() {
        return com.bjsp123.rl2.ui.skin.Settings.tipsEnabled()
                && v2GameStartTipPopup != null
                && !com.bjsp123.rl2.GameStartTipsRegistry.isEmpty();
    }

    /** Ease the camera back from play zoom toward {@link #DEATH_ZOOM_OUT} as the
     *  death fade ramps, so the view pulls away from the fallen player. The
     *  camera stays where it last followed the player (who is now removed). */
    private void updateDeathCamera() {
        float t = DEATH_FADE_FRAMES <= 0 ? 1f
                : Math.min(1f, deathFadeFrame / (float) DEATH_FADE_FRAMES);
        float eased = t * t * (3f - 2f * t); // smoothstep
        camera.zoom = DEFAULT_ZOOM + (DEATH_ZOOM_OUT - DEFAULT_ZOOM) * eased;
        camera.update();
    }

    /** Latch the victory transition (RL-19): the player has stepped onto the
     *  stairs that appeared when the Great Wraith died. Mirrors the death
     *  bookkeeping - snapshot the run, mark it a win, score by beacons lit,
     *  persist to the hall of fame, clear the save - then ramp the gold fade and
     *  swap to the VICTORY screen. The player is alive, so {@code lastSnapshot}
     *  already holds the live snapshot refreshed each frame. */
    private void latchVictory(Level level, Mob player) {
        if (lastSnapshot == null) return;
        // Beacons lit this run + whether the whole world was lit (perfect victory).
        int beaconsLit = player.beaconsLit;
        boolean anyUnlit = false;
        for (Level l : world.levels) {
            if (l == null || l.tiles == null) continue;
            for (int ty = 0; ty < l.height && !anyUnlit; ty++) {
                for (int tx = 0; tx < l.width; tx++) {
                    if (l.tiles[tx][ty] == com.bjsp123.rl2.model.Tile.BEACON_INACTIVE) {
                        anyUnlit = true;
                        break;
                    }
                }
            }
            if (anyUnlit) break;
        }
        boolean allBeaconsLit = !anyUnlit;
        lastSnapshot.victory = true;
        lastSnapshot.beaconsLit = beaconsLit;
        lastSnapshot.allBeaconsLit = allBeaconsLit;
        // Final score uses the run-stats formula (RL-58); the snapshot is already
        // refreshed each frame, so recompute here to capture the boss bonus.
        lastSnapshot.killedGreatWraith = player.killedGreatWraith;
        lastSnapshot.score = com.bjsp123.rl2.logic.GameBalance.runScore(
                player.runStats, beaconsLit, player.killedGreatWraith, allBeaconsLit);
        game.hallOfFame.add(lastSnapshot);
        com.bjsp123.rl2.save.HallOfFameStore.save(game.persistence, game.hallOfFame);
        if (game.achievementSystem != null) {
            game.achievementSystem.observeRunEnded(lastSnapshot);
        }
        game.saveSystem.clear(saveSlot);
        game.currentPlay = null;
        // Start the end-sequence outro (RL-58): zoom out to the world map, dissolve
        // it to white, then the victory screen. Replaces the old gold-fade swap.
        outroActive = true;
        outroTime   = 0f;
        // A column of light erupts from the exit portal - triumph rather than
        // the death outro's dim collapse.
        if (animator != null) {
            com.bjsp123.rl2.world.render.Effect.columnOfLight(
                    animator.stage, player.position, animator.rng());
        }
    }

    /** Outro cinematic render path (RL-58). Runs instead of the normal frame
     *  once {@link #outroActive} is set: world graph zooms out from the exit
     *  node while a white dissolve sweeps up from the deepest level (beacons
     *  burst, levels flash + ring), then the whole screen ramps to white and
     *  hands off to {@link com.bjsp123.rl2.ui.v2.V2Victory}. Tap to skip. */
    private void renderOutro(float delta) {
        if (com.badlogic.gdx.Gdx.input.justTouched() && outroTime > 0.25f) {
            outroTime = OUTRO_GRAPH_SEC + OUTRO_WHITE_SEC;   // jump to the end
        }
        outroTime += delta;
        float gT = Math.min(1f, outroTime / OUTRO_GRAPH_SEC);
        float eased = gT * gT * (3f - 2f * gT);             // smoothstep

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        com.bjsp123.rl2.ui.v2.UiCtx ctx = game.ui;
        com.bjsp123.rl2.ui.v2.Rect full =
                new com.bjsp123.rl2.ui.v2.Rect(0f, 0f, ctx.worldW(), ctx.worldH());
        introGraph.world        = world;
        introGraph.layoutArea   = full;
        introGraph.viewport     = full;
        introGraph.zoom         = OUTRO_ZOOM_START + (OUTRO_ZOOM_END - OUTRO_ZOOM_START) * eased;
        introGraph.selected     = -1;
        introGraph.currentIndex = world.currentLevelIndex;
        introGraph.swirlT      += delta;
        introGraph.beaconPulseT += delta;
        float[] p = introGraph.panToCenter(world.currentLevelIndex);
        introGraph.panX = p[0];
        introGraph.panY = p[1];

        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setProjectionMatrix(ctx.camera.combined);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        introGraph.draw(ctx);
        s.end();

        ctx.applyProjection();
        ctx.batch.begin();
        introGraph.drawUnvisitedGlyphs(ctx);
        ctx.batch.end();

        drawOutroDissolve(gT);

        // Final ramp to a fully white screen, then hand off to the victory screen.
        if (outroTime >= OUTRO_GRAPH_SEC) {
            float wT = Math.min(1f, (outroTime - OUTRO_GRAPH_SEC) / OUTRO_WHITE_SEC);
            drawColorOverlay(1f, 1f, 1f, wT);
            if (outroTime >= OUTRO_GRAPH_SEC + OUTRO_WHITE_SEC) {
                outroActive = false;
                com.bjsp123.rl2.ui.skin.Settings.setAnimationTransientOverride(0f);
                dispose();
                game.setRootScreen(new com.bjsp123.rl2.ui.v2.V2Victory(game, lastSnapshot));
            }
        }
    }

    /** Bottom-up white dissolve over the world graph: each level node (and its
     *  beacons) whitens, bursts, and rings out as a sweep front passes it,
     *  ordered deepest-first. {@code gT} is the 0..1 dissolve progress. */
    private void drawOutroDissolve(float gT) {
        if (introGraph.boxRects.isEmpty()) return;
        int minD = Integer.MAX_VALUE, maxD = Integer.MIN_VALUE;
        for (int idx : introGraph.boxIndex) {
            int d = (idx >= 0 && idx < world.levels.length && world.levels[idx] != null)
                    ? world.levels[idx].depth : 0;
            minD = Math.min(minD, d);
            maxD = Math.max(maxD, d);
        }
        float span = Math.max(1, maxD - minD);
        com.bjsp123.rl2.ui.v2.UiCtx ctx = game.ui;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.setProjectionMatrix(ctx.camera.combined);

        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        // White node fills + beacon bursts.
        for (int i = 0; i < introGraph.boxRects.size(); i++) {
            com.bjsp123.rl2.ui.v2.Rect r = introGraph.boxRects.get(i);
            float trigger = 1f - depthNorm(introGraph.boxIndex.get(i), minD, span);
            float amt = clamp01((gT - trigger) / 0.12f);
            if (amt > 0f) {
                s.setColor(1f, 1f, 1f, amt);
                s.rect(r.x, r.y, r.w, r.h);
            }
        }
        for (int i = 0; i < introGraph.beaconRects.size(); i++) {
            com.bjsp123.rl2.ui.v2.Rect br = introGraph.beaconRects.get(i);
            float trigger = 1f - depthNorm(introGraph.beaconRefs.get(i)[0], minD, span);
            float age = gT - trigger;
            if (age >= 0f && age <= 0.4f) {
                float k = age / 0.4f;
                s.setColor(1f, 0.95f, 0.55f, (1f - k) * 0.85f);
                s.circle(br.cx(), br.cy(), br.w * (0.6f + 3.5f * k), 24);
            }
        }
        s.end();

        // Expanding ring outlines (the "rings of sparks") at each node.
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < introGraph.boxRects.size(); i++) {
            com.bjsp123.rl2.ui.v2.Rect r = introGraph.boxRects.get(i);
            float age = gT - (1f - depthNorm(introGraph.boxIndex.get(i), minD, span));
            if (age >= 0f && age <= 0.45f) {
                float k = age / 0.45f;
                s.setColor(1f, 1f, 1f, 1f - k);
                s.circle(r.cx(), r.cy(), Math.max(r.w, r.h) * (0.4f + 1.8f * k), 20);
            }
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Normalised depth (0 shallowest .. 1 deepest) of the level at world index
     *  {@code idx}, for the deepest-first dissolve ordering. */
    private float depthNorm(int idx, int minD, float span) {
        int d = (idx >= 0 && idx < world.levels.length && world.levels[idx] != null)
                ? world.levels[idx].depth : 0;
        return (d - minD) / span;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Full-screen colour overlay at {@code alpha} (0 = clear). */
    private void drawColorOverlay(float r, float g, float b, float alpha) {
        if (alpha <= 0f) return;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = game.ui.shapes;
        s.setProjectionMatrix(game.ui.camera.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(r, g, b, Math.min(1f, alpha));
        s.rect(0f, 0f, game.ui.worldW(), game.ui.worldH());
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Snap to play zoom and hand control to the player, blooming a dust cloud
     *  around them so the hand-off reads as "you materialise here". Idempotent. */
    private void endIntro() {
        if (!introActive) return;
        introActive = false;
        introGraphPhase = false;   // a skip during the graph fly-in lands here too
        if (introTipPhase) {
            introTipPhase = false;
            if (v2GameStartTipPopup != null) v2GameStartTipPopup.hide();
        }
        camera.zoom = DEFAULT_ZOOM;
        recenterCameraOnPlayer();
        // Arrival: bloom a dust cloud around the player as control begins, and
        // play the "player appears in the world" sting alongside it.
        Mob p = TurnSystem.findPlayer(world.currentLevel());
        if (p != null && animator != null) {
            com.bjsp123.rl2.world.render.Effect.arrivalCloud(
                    animator.stage, p.position, animator.rng());
        }
        if (game.sounds != null) game.sounds.play("sfx.player.arrive");
    }

    /** First processor in the input chain. While the intro is running, any tap
     *  or key skips it (and is swallowed so it can't also fire a game action);
     *  once the intro ends it is transparent and events fall through. */
    private com.badlogic.gdx.InputProcessor introSkipInput() {
        return new com.badlogic.gdx.InputAdapter() {
            @Override public boolean touchDown(int x, int y, int pointer, int button) {
                if (introActive) { endIntro(); return true; }
                return false;
            }
            @Override public boolean keyDown(int keycode) {
                if (introActive) { endIntro(); return true; }
                return false;
            }
            @Override public boolean scrolled(float ax, float ay) { return introActive; }
        };
    }

    /** Bottom banner with the intro goal text, drawn over the world. */
    private void drawIntroMessage() {
        String msg = com.bjsp123.rl2.logic.TextCatalog.get("ui.intro.message");
        if (msg == null || msg.isEmpty()) return;
        float w = game.ui.worldW();
        float h = game.ui.worldH();
        float bandH = Math.max(64f, h * 0.14f);
        // Dark translucent band.
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = game.ui.shapes;
        s.setProjectionMatrix(game.ui.camera.combined);
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(
                com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.6f);
        s.rect(0f, 0f, w, bandH);
        s.end();
        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        // Centred goal text.
        game.ui.applyProjection();
        com.badlogic.gdx.graphics.g2d.SpriteBatch b = game.ui.batch;
        com.badlogic.gdx.graphics.g2d.BitmapFont f = game.ui.fontRegular;
        float yTop = bandH * 0.5f + f.getCapHeight() * 0.5f;
        b.begin();
        com.bjsp123.rl2.ui.v2.TextDraw.centreFit(game.ui, f,
                com.badlogic.gdx.graphics.Color.WHITE, msg, w * 0.5f, yTop, w * 0.9f);
        b.end();
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

    /** Victory analogue of {@link #drawDeathFadeOverlay} - a warm gold wash that
     *  ramps to full as the run resolves into the VICTORY screen. */
    private void drawVictoryFadeOverlay(float alpha) {
        if (alpha <= 0f) return;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = game.ui.shapes;
        s.setProjectionMatrix(game.ui.camera.combined);
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(
                com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(1f, 0.85f, 0.45f, alpha);
        s.rect(0f, 0f, game.ui.worldW(), game.ui.worldH());
        s.end();
        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
    }

    /** Jade Peach revive cinematic: the level fades to black, the peach blooms
     *  over a rotating golden sunburst and bursts in a flash, then the level
     *  fades back in. The game is frozen for the whole sequence. */
    private void drawReviveCinematic(int f) {
        final int B = com.badlogic.gdx.graphics.GL20.GL_BLEND;
        final int SA = com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA;
        final int OMSA = com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA;
        final int ONE = com.badlogic.gdx.graphics.GL20.GL_ONE;
        float vw = game.ui.worldW(), vh = game.ui.worldH();
        float cx = vw * 0.5f, cy = vh * 0.5f;

        // Black backdrop: ramp in, hold, ramp out.
        float black;
        if (f < REVIVE_FADE_OUT)     black = f / (float) REVIVE_FADE_OUT;
        else if (f < REVIVE_FADE_IN) black = 1f;
        else                         black = Math.max(0f, 1f - (f - REVIVE_FADE_IN)
                                              / (float) (REVIVE_TOTAL - REVIVE_FADE_IN));

        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = game.ui.shapes;
        s.setProjectionMatrix(game.ui.camera.combined);
        com.badlogic.gdx.Gdx.gl.glEnable(B);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(SA, OMSA);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, black);
        s.rect(0f, 0f, vw, vh);

        boolean showPeach = f >= REVIVE_FADE_OUT - 3 && f < REVIVE_FADE_IN + 2;
        if (showPeach) {
            // Additive golden sunburst rotating behind the peach, fading after the burst.
            com.badlogic.gdx.Gdx.gl.glBlendFunc(SA, ONE);
            float postBurst = f >= REVIVE_EXPLODE
                    ? Math.max(0f, 1f - (f - REVIVE_EXPLODE) / (float) (REVIVE_FADE_IN - REVIVE_EXPLODE))
                    : 1f;
            float maxLen = Math.max(vw, vh);
            int rays = 16;
            float rot = f * 0.05f, half = 0.085f;
            s.setColor(1f, 0.82f, 0.35f, 0.45f * black * postBurst);
            for (int i = 0; i < rays; i++) {
                float a = rot + i * (float) (Math.PI * 2 / rays);
                s.triangle(cx, cy,
                        cx + (float) Math.cos(a - half) * maxLen, cy + (float) Math.sin(a - half) * maxLen,
                        cx + (float) Math.cos(a + half) * maxLen, cy + (float) Math.sin(a + half) * maxLen);
            }
            // White flash full-screen at the burst, fading fast.
            if (f >= REVIVE_EXPLODE) {
                float t = (f - REVIVE_EXPLODE) / (float) (REVIVE_FADE_IN - REVIVE_EXPLODE);
                float flash = Math.max(0f, 1f - t * 1.6f) * black;
                if (flash > 0f) {
                    s.setColor(1f, 0.97f, 0.8f, flash);
                    s.rect(0f, 0f, vw, vh);
                }
            }
            com.badlogic.gdx.Gdx.gl.glBlendFunc(SA, OMSA);
        }
        s.end();

        // The peach itself - grows in, then pops + fades on the burst.
        if (showPeach) {
            com.badlogic.gdx.graphics.g2d.TextureRegion peach =
                    com.bjsp123.rl2.world.render.ItemSprites.regionFor("JADE_PEACH");
            if (peach != null) {
                float grow = f < REVIVE_FADE_OUT ? 0f
                        : f < REVIVE_GROW_END ? (f - REVIVE_FADE_OUT) / (float) (REVIVE_GROW_END - REVIVE_FADE_OUT)
                        : 1f;
                float ease = grow * grow * (3f - 2f * grow);
                float base = Math.min(vw, vh) * 0.22f;
                float scale, pa;
                if (f < REVIVE_EXPLODE) {
                    scale = 0.4f + 0.6f * ease;
                    pa = black;
                } else {
                    float t = (f - REVIVE_EXPLODE) / (float) (REVIVE_FADE_IN - REVIVE_EXPLODE);
                    scale = 1f + 1.8f * t;
                    pa = Math.max(0f, 1f - t) * black;
                }
                float sz = base * scale;
                game.ui.batch.setProjectionMatrix(game.ui.camera.combined);
                game.ui.batch.begin();
                game.ui.batch.setColor(1f, 1f, 1f, pa);
                game.ui.batch.draw(peach, cx - sz * 0.5f, cy - sz * 0.5f, sz, sz);
                game.ui.batch.setColor(1f, 1f, 1f, 1f);
                game.ui.batch.end();
            }
        }
        com.badlogic.gdx.Gdx.gl.glDisable(B);
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
        if (confirmPopup != null && confirmPopup.isOpen()) return true;
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
        f.draw(b, com.bjsp123.rl2.util.Fmt.of(
                "FPS:%.0f  CLR:%.1fms  REN:%.1fms  LGC:%.1fms",
                frameProfiler.snapFps(), frameProfiler.snapClearMs(),
                frameProfiler.snapRenderMs(), frameProfiler.snapLogicMs()), x, y);
        f.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        b.end();
    }

    private static String fmtPass(long ns, int flushes) {
        return com.bjsp123.rl2.util.Fmt.of("%.1fms/%d", ns / 1_000_000.0, flushes);
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
