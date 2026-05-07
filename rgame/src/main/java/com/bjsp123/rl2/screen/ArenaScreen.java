package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.input.CameraController;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.logic.CombatArena;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.save.ArenaHallOfFameEntry;
import com.bjsp123.rl2.ui.overlay.LookRenderer;
import com.bjsp123.rl2.ui.skin.AnimationSpeed;
import com.bjsp123.rl2.ui.skin.StoneUi;
import com.bjsp123.rl2.ui.skin.UiPixelScale;
import com.bjsp123.rl2.ui.skin.UiScale;
import com.bjsp123.rl2.world.anim.Animator;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;
import com.bjsp123.rl2.world.render.LevelRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stripped-down PlayScreen for arena mode. No save/load, no inventory, no look
 * mode — just a renderer driving a {@link CombatArena}-built level until either
 * {@link CombatArena#hostilePairExists} returns false or the standard-turn cap
 * fires (recorded as a draw). Animation speed is forced to 4× via
 * {@link AnimationSpeed#setTransientOverride} for the lifetime of the screen.
 *
 * <p>HUD: pause / fast-forward / abort buttons in the top-left. Camera supports
 * pan + zoom via the same {@link CameraController} the dungeon uses.
 */
public class ArenaScreen implements Screen {

    /** Default animation-speed override applied while the arena is active.
     *  The Speed button in the HUD flips between this and {@link #NORMAL_ANIM_SPEED}. */
    private static final int ARENA_ANIM_SPEED  = 4;
    /** Normal speed — used when the Speed button is toggled off. */
    private static final int NORMAL_ANIM_SPEED = 1;
    /** Camera zoom — slightly more zoomed-out than dungeon play so the whole
     *  arena fits comfortably in view. */
    private static final float ARENA_ZOOM = 0.45f;
    /** Standard-turn cap. Past this the arena calls the fight a draw and
     *  records it — keeps stalemates (e.g. two pacifist blobs that never
     *  detect each other) from looping forever. */
    private static final int MAX_STANDARD_TURNS = 100;
    /** Per-frame ceiling on tick advances during fast-forward, so a runaway
     *  matchup doesn't deadlock the render loop. Plenty of headroom for a
     *  100-turn fight (each standard turn is ~100 ticks → ~10000 ticks total). */
    private static final int FF_TICK_BUDGET = 100_000;

    private final Rl2Game game;
    private final ArenaSetupScreen.TeamSpec teamASpec;
    private final ArenaSetupScreen.TeamSpec teamBSpec;

    private World world;
    private Level level;
    private LevelRenderer levelRenderer;
    private OrthographicCamera camera;
    private CameraController cameraController;
    private LookMode lookMode;
    private LookRenderer lookRenderer;
    private final Animator animator = new Animator();

    // HUD / banner.
    private Stage uiStage;
    private StoneUi stoneUi;
    private Skin uiSkin;
    private Table banner;
    private TextButton pauseButton;
    private TextButton speedButton;
    private boolean fastSpeed = true;
    private boolean paused;
    private boolean fightOver;
    private boolean recordedToHallOfFame;
    private List<Mob> teamA;
    private List<Mob> teamB;

    /** Number of full standard turns that have elapsed in the arena. Tracked
     *  by watching {@code level.standardTurnTickAcc} roll over (it's reset to
     *  0 inside {@link com.bjsp123.rl2.logic.TurnSystem#tick} every time it
     *  reaches {@code STANDARD_TURN_TICKS}). */
    private int standardTurnsElapsed;

    public ArenaScreen(Rl2Game game,
                       ArenaSetupScreen.TeamSpec a,
                       ArenaSetupScreen.TeamSpec b) {
        this.game = game;
        this.teamASpec = a;
        this.teamBSpec = b;
    }

    @Override
    public void show() {
        Random rng = new Random();
        int w = (Math.max(teamASpec.count, teamBSpec.count) > 5) ? 24 : 16;
        int h = w;
        level = CombatArena.buildArenaLevel(w, h, rng);

        teamA = new ArrayList<>();
        teamB = new ArrayList<>();
        List<Point> spotsA = new ArrayList<>();
        List<Point> spotsB = new ArrayList<>();
        int teamAColX = 2;
        int teamBColX = w - 3;
        int maxN = Math.max(teamASpec.count, teamBSpec.count);
        int yStart = (h - maxN) / 2;
        if (yStart < 1) yStart = 1;
        for (int i = 0; i < teamASpec.count; i++) {
            Mob m = spawnTeamMob(teamASpec, new Point(teamAColX, yStart + i));
            if (m != null) {
                teamA.add(m);
                spotsA.add(m.position);
            }
        }
        for (int i = 0; i < teamBSpec.count; i++) {
            Mob m = spawnTeamMob(teamBSpec, new Point(teamBColX, yStart + i));
            if (m != null) {
                teamB.add(m);
                spotsB.add(m.position);
            }
        }
        CombatArena.placeMobs(level, teamA, spotsA);
        CombatArena.placeMobs(level, teamB, spotsB);
        CombatArena.seedTeamHostility(teamA, teamB);

        for (Mob m : level.mobs) m.stateOfMind = Mob.StateOfMind.AWAKE;

        world = new World(new Level[]{level});

        LevelSystem.computeLighting(level);
        LevelSystem.updateVisibility(level);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) level.visible[x][y] = true;
        }

        levelRenderer = new DefaultLevelRenderer();
        levelRenderer.create();
        levelRenderer.setWorld(world);
        levelRenderer.setAnimator(animator);

        camera = new OrthographicCamera();
        camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.zoom = ARENA_ZOOM;
        camera.position.set(
                w * LevelRenderer.TILE_SIZE * 0.5f,
                h * LevelRenderer.TILE_SIZE * 0.5f, 0);
        camera.update();

        cameraController = new CameraController(camera);
        cameraController.setAnimator(animator);

        stoneUi = new StoneUi();
        stoneUi.create();
        uiSkin = stoneUi.newSkin();
        ScreenViewport vp = new ScreenViewport();
        vp.setUnitsPerPixel(1f / (UiScale.scale() * UiPixelScale.scale()));
        uiStage = new Stage(vp);

        // Look popup — only active while paused. The renderer reads cursor
        // state from LookMode each frame; LookMode itself is in the input
        // multiplexer below and consumes clicks only while {@code active}.
        lookMode = new LookMode(camera);
        lookMode.setLevel(level);
        lookRenderer = new LookRenderer(uiSkin);
        lookRenderer.setLevel(level);
        lookRenderer.setLookMode(lookMode);
        uiStage.addActor(lookRenderer);
        buildHud();

        // CameraController FIRST so wheel/pinch zoom still works while the HUD
        // is on top. uiStage second so the HUD buttons consume their own clicks
        // before they fall through to camera-pan. LookMode last so it only
        // sees clicks the HUD didn't claim, and only while paused (it's
        // {@code active} only between activateBlank() / deactivate()).
        Gdx.input.setInputProcessor(new InputMultiplexer(cameraController, uiStage, lookMode));

        animator.consume(level);
        AnimationSpeed.setTransientOverride(ARENA_ANIM_SPEED);
    }

    /** Build a single team mob from the spec at the given spot. Player-class
     *  fighters get their normal starting kit (sword, potions, wand, bombs)
     *  but their behaviour is mapped to the AI archetype that matches their
     *  playstyle. */
    private Mob spawnTeamMob(ArenaSetupScreen.TeamSpec spec, Point pos) {
        Mob m;
        if (spec.type.charClass != null) {
            m = MobFactory.player(pos, spec.type.charClass);
            switch (spec.type.charClass) {
                case WARRIOR -> m.behavior = Mob.Behavior.MOB;
                case ROGUE   -> m.behavior = Mob.Behavior.MOB; // bombs via AI item-use
                case MAGE    -> {
                    m.behavior = Mob.Behavior.RANGED_MOB_STANDOFF;
                    seedMageRangedStats(m);
                }
            }
        } else {
            m = MobFactory.spawn(spec.type.mobType, pos);
        }
        if (m == null) return null;
        MobProgression.setSpawnLevel(m, spec.level);
        return m;
    }

    /** Copy the equipped wand-of-magic-missile's damage range into the mob's
     *  intrinsic ranged stats so the {@link Mob.Behavior#RANGED_MOB_STANDOFF}
     *  AI's {@code tryRangedShot} actually fires. The wand isn't consumed —
     *  arena mobs effectively get unlimited charges, which is fine for a
     *  spectator fight. */
    private static void seedMageRangedStats(Mob mage) {
        com.bjsp123.rl2.model.Item wand = null;
        for (com.bjsp123.rl2.model.Item it : mage.inventory.bag) {
            if (it != null && it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
                wand = it; break;
            }
        }
        if (wand == null) return;
        // Damage range is now per-item (damage + damagePerLevel columns on
        // items.csv) — route through ItemSystem so the arena mob's ranged
        // stats track whatever items.csv says about the wand.
        mage.intrinsic.rangedDamage = com.bjsp123.rl2.logic.ItemSystem.effectiveDamageRange(wand);
        mage.intrinsic.rangedDistance   = 6;
        mage.intrinsic.rangedRateOfFire = 1;
        mage.intrinsic.rangedCost       = mage.intrinsic.attackCost;
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        int dtMs = Math.min(100, (int) (Gdx.graphics.getDeltaTime() * 1000f));

        // Mirrors PlayScreen.render's sequencing: drain the freeze gate first,
        // then advance combat only when no animation is in flight, then run
        // the per-frame real-time clocks. Without the freeze gate the arena
        // ticks combat over still-playing animations and damage floats / lunges
        // never get a chance to register.
        //
        // Unlike PlayScreen — where the player drives one tick per input — the
        // arena has no player and most ticks are pure cooldown drain (mobs at
        // moveCost=100 sit idle for 99 ticks between actions). One tick per
        // render frame means ~1.5s of empty real-time wall clock between any
        // two visible actions. So when no animation is in flight, fast-forward
        // through idle ticks up to one standard turn per render frame; we stop
        // the moment any visible action queues an animation.
        animator.queue.tick(AnimationSpeed.framesPerRender());
        if (!fightOver && !paused) {
            int budget = com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
            while (budget-- > 0
                    && animator.queue.freezeFrames == 0
                    && !fightShouldEnd()) {
                advanceOneCombatTick();
                // Drain events into freezeFrames mid-loop so the next iteration
                // sees the animation kick in and breaks. Without this, a lunge
                // queued mid-budget wouldn't gate further ticks until the loop
                // exited.
                animator.consume(level);
            }
        }
        // The visual layer ticks every frame regardless of pause / fightOver
        // state — otherwise in-flight death ghosts freeze partway through
        // their fade animation and the corpses linger on screen indefinitely.
        animator.consume(level);
        animator.tick(level, dtMs);
        com.bjsp123.rl2.logic.FireSystem.tickRealTime(level, dtMs);
        com.bjsp123.rl2.logic.LevelSystem.tickLightMotesRealTime(level, dtMs);

        camera.update();
        levelRenderer.render(level, camera);
        lookMode.render();
        lookRenderer.update();

        // Fight-over check — runs after the tick so the last kill animation
        // gets to play out; the banner overlays it once.
        if (!fightOver && fightShouldEnd()) {
            fightOver = true;
            if (!recordedToHallOfFame) {
                recordToHallOfFame();
                recordedToHallOfFame = true;
            }
            buildBanner();
        }

        uiStage.act(delta);
        uiStage.draw();
    }

    /** Run one tick of combat through the shared headless engine. Real-time
     *  clocks (fire / light motes / death anim) are NOT advanced here — they
     *  fire once per render frame regardless of how many ticks we ran, so an
     *  inner skip-loop doesn't accidentally fast-forward fire spread. Tracks
     *  the rollover of {@code level.standardTurnTickAcc} so the
     *  {@link #MAX_STANDARD_TURNS} cap has a meaningful counter. */
    private void advanceOneCombatTick() {
        int beforeAcc = level.standardTurnTickAcc;
        CombatArena.tickHeadless(level, world, 0);
        if (level.standardTurnTickAcc < beforeAcc) standardTurnsElapsed++;
        // The level renderer caches the mob/item cell index across frames and
        // rebuilds it only when {@code markDirty()} fires — so without this,
        // killed mobs keep getting drawn from the stale cache and live mobs
        // appear stuck at their starting tiles.
        levelRenderer.markDirty();
    }

    /** Skip rendering and run the simulation to completion (or the turn cap)
     *  in one frame. Records the result and shows the banner the same way
     *  the live path does. */
    private void runFastForward() {
        int budget = FF_TICK_BUDGET;
        while (budget-- > 0
                && !fightOver
                && CombatArena.hostilePairExists(level)
                && standardTurnsElapsed < MAX_STANDARD_TURNS) {
            advanceOneCombatTick();
        }
        // Drain animator state silently — visual mob anim queues, etc., shouldn't
        // carry forward into the banner frame.
        animator.consume(level);
        fightOver = true;
        if (!recordedToHallOfFame) {
            recordToHallOfFame();
            recordedToHallOfFame = true;
        }
        buildBanner();
    }

    /** True iff the fight should end this frame: either no hostile pair
     *  remains, or the standard-turn cap was hit. */
    private boolean fightShouldEnd() {
        if (!CombatArena.hostilePairExists(level)) return true;
        return standardTurnsElapsed >= MAX_STANDARD_TURNS;
    }

    private int aliveCount(List<Mob> team) {
        int n = 0;
        for (Mob m : team) if (m.hp > 0) n++;
        return n;
    }

    private int decideWinner() {
        int aAlive = aliveCount(teamA), bAlive = aliveCount(teamB);
        if (standardTurnsElapsed >= MAX_STANDARD_TURNS
                && CombatArena.hostilePairExists(level)) {
            return 0; // turn-cap draw
        }
        if (aAlive > 0 && bAlive == 0) return 1;
        if (bAlive > 0 && aAlive == 0) return 2;
        return 0;
    }

    private void recordToHallOfFame() {
        if (game == null || game.arenaHallOfFame == null) return;
        game.arenaHallOfFame.add(new ArenaHallOfFameEntry(
                describeTeamSpec(teamASpec),
                describeTeamSpec(teamBSpec),
                describeSurvivors(teamA),
                describeSurvivors(teamB),
                decideWinner(),
                System.currentTimeMillis()));
        com.bjsp123.rl2.save.ArenaHallOfFameStore.save(game.persistence, game.arenaHallOfFame);
    }

    private static String describeTeamSpec(ArenaSetupScreen.TeamSpec spec) {
        return spec.type.label + "×" + spec.count + " L" + spec.level;
    }

    private static String describeSurvivors(List<Mob> team) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mob m : team) {
            if (m.hp <= 0) continue;
            String name = m.name != null ? m.name
                    : (m.mobType != null ? m.mobType.toLowerCase() : "?");
            counts.merge(name, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("×").append(e.getValue());
        }
        return sb.toString();
    }

    /** Top-left strip of pause / fast-forward / abort. Built once, shown for
     *  the lifetime of the screen — even after the fight ends, abort needs
     *  to keep working as a quick way back to setup before the player clicks
     *  through the banner. */
    private void buildHud() {
        Table hud = new Table();
        hud.setFillParent(true);
        hud.top().left().pad(8);
        pauseButton = makeButton("Pause", () -> {
            if (fightOver) return;
            paused = !paused;
            pauseButton.setText(paused ? "Resume" : "Pause");
            // Look popup is paused-only — entering pause arms the click-to-look
            // handler, leaving pause dismisses any open panel.
            if (paused) lookMode.activateBlank();
            else        lookMode.deactivate();
        });
        hud.add(pauseButton).width(80).height(30).pad(2);
        hud.add(makeButton("Fast forward", () -> {
            if (fightOver) return;
            runFastForward();
        })).width(120).height(30).pad(2);
        // Speed toggle — flips between 4× (default for arena) and 1× (normal
        // dungeon speed) so a user can slow the action down to inspect what's
        // happening blow-by-blow without leaving the screen.
        speedButton = makeButton(speedLabel(), () -> {
            fastSpeed = !fastSpeed;
            AnimationSpeed.setTransientOverride(
                    fastSpeed ? ARENA_ANIM_SPEED : NORMAL_ANIM_SPEED);
            speedButton.setText(speedLabel());
        });
        hud.add(speedButton).width(120).height(30).pad(2);
        hud.add(makeButton("Abort", () ->
                game.setScreen(new ArenaSetupScreen(game))))
                .width(80).height(30).pad(2);
        uiStage.addActor(hud);
    }

    /** Label flips between "Speed: 4×" (fast) and "Speed: 1×" (normal) so the
     *  current setting is visible without a separate "is-checked" indicator —
     *  the button itself stays a momentary press. */
    private String speedLabel() { return "Speed: " + (fastSpeed ? "4×" : "1×"); }

    private void buildBanner() {
        if (banner != null) return;
        banner = new Table();
        banner.setFillParent(true);
        banner.center();

        int winner = decideWinner();
        String headline =
                winner == 1 ? "Team A wins"
              : winner == 2 ? "Team B wins"
              : standardTurnsElapsed >= MAX_STANDARD_TURNS ? "Draw — turn limit reached"
              : "Mutual wipe";
        Table panel = new Table();
        panel.setBackground(uiSkin.getDrawable("panel"));
        panel.pad(16);
        panel.add(makeLabel(headline, "title", 1.5f)).pad(6).row();
        panel.add(makeLabel("Team A: " + describeSurvivors(teamA), "default", 1f)).pad(2).row();
        panel.add(makeLabel("Team B: " + describeSurvivors(teamB), "default", 1f)).pad(2).row();

        Table buttons = new Table();
        buttons.add(makeButton("Fight again", () ->
                game.setScreen(new ArenaScreen(game, teamASpec, teamBSpec))))
                .width(140).height(36).pad(6);
        buttons.add(makeButton("Back to setup", () ->
                game.setScreen(new ArenaSetupScreen(game))))
                .width(140).height(36).pad(6);
        panel.add(buttons).padTop(10);

        Container<Table> framed = new Container<>(panel);
        banner.add(framed);
        uiStage.addActor(banner);
    }

    private com.badlogic.gdx.scenes.scene2d.ui.Label makeLabel(String text, String style, float scale) {
        com.badlogic.gdx.scenes.scene2d.ui.Label l =
                new com.badlogic.gdx.scenes.scene2d.ui.Label(text, uiSkin, style);
        l.setFontScale(scale);
        return l;
    }

    private TextButton makeButton(String text, Runnable onClick) {
        TextButton b = new TextButton(text, uiSkin);
        b.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent e, float x, float y) {
                onClick.run();
            }
        });
        return b;
    }

    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            float oldZoom = camera.zoom;
            // Preserve the user's manual pan/zoom across resize — only the
            // first resize (initial setup) should snap the camera to centre.
            float oldX = camera.position.x, oldY = camera.position.y;
            camera.setToOrtho(false, width, height);
            camera.zoom = oldZoom;
            camera.position.set(oldX, oldY, 0);
            camera.update();
        }
        if (uiStage != null) uiStage.getViewport().update(width, height, true);
    }

    @Override public void pause() {}
    @Override public void resume() {}

    @Override
    public void hide() {
        AnimationSpeed.setTransientOverride(0);
    }

    @Override
    public void dispose() {
        AnimationSpeed.setTransientOverride(0);
        if (levelRenderer != null) levelRenderer.dispose();
        if (lookMode != null) lookMode.dispose();
        if (uiStage != null) uiStage.dispose();
        if (stoneUi != null) stoneUi.dispose();
    }
}
