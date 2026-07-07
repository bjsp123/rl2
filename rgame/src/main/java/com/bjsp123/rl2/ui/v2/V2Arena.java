package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.input.CameraController;
import com.bjsp123.rl2.logic.CombatArena;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.save.ArenaHallOfFame.ArenaHallOfFameEntry;
import com.bjsp123.rl2.ui.skin.Settings;
import com.bjsp123.rl2.world.anim.Animator;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;
import com.bjsp123.rl2.world.render.LevelRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * V2 arena screen - combat between two AI teams, drawn with V2 chrome.
 * World-setup logic is delegated to {@link CombatArena}; the HUD + result
 * banner are primitive ShapeRenderer + SpriteBatch elements drawn directly
 * by this screen.
 *
 * <p>HUD across the top of the screen: Pause / Fast Forward / Speed / Log /
 * Abort, all as standard V2 buttons. When the fight ends, a centred V2
 * popup with "Fight again" + "Back to setup" buttons appears.
 *
 * <p>Look mode is dropped here (the V1 path supported clicking tiles
 * while paused to inspect mobs); the arena is a spectator screen so the
 * loss isn't significant. Can be added back as a follow-up.
 */
public final class V2Arena extends ScreenAdapter {

    private static final int   ARENA_ANIM_SPEED  = 4;
    private static final int   NORMAL_ANIM_SPEED = 1;
    private static final float ARENA_ZOOM        = 0.45f;
    private static final int   MAX_STANDARD_TURNS = 100;
    private static final int   FF_TICK_BUDGET     = 100_000;

    private final Rl2Game game;
    private final UiCtx   ctx;
    private final V2ArenaSetup.TeamSpec teamASpec;
    private final V2ArenaSetup.TeamSpec teamBSpec;

    private World world;
    private Level level;
    private LevelRenderer levelRenderer;
    private OrthographicCamera camera;
    private CameraController cameraController;
    private final Animator animator = new Animator();

    private List<Mob> teamA;
    private List<Mob> teamB;
    private boolean paused, fightOver, fastSpeed = true, recordedToHallOfFame;
    private int standardTurnsElapsed;

    // HUD button rects + pressed state.
    private final Rect pauseBtn = new Rect();
    private final Rect ffBtn    = new Rect();
    private final Rect speedBtn = new Rect();
    private final Rect logBtn   = new Rect();
    private final Rect abortBtn = new Rect();
    private boolean pausePressed, ffPressed, speedPressed, logPressed, abortPressed;

    // Game-log overlay - shows EventLog entries from the in-progress fight.
    private V2Log log;

    // Result banner state.
    private final Rect bannerWindow      = new Rect();
    private final Rect bannerFightAgain  = new Rect();
    private final Rect bannerBack        = new Rect();
    private boolean bannerFightAgainPressed, bannerBackPressed;

    public V2Arena(Rl2Game game,
                   V2ArenaSetup.TeamSpec a,
                   V2ArenaSetup.TeamSpec b) {
        this.game = game;
        this.ctx  = game.ui;
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
        int teamAColX = 2, teamBColX = w - 3;
        int maxN = Math.max(teamASpec.count, teamBSpec.count);
        int yStart = Math.max(1, (h - maxN) / 2);
        for (int i = 0; i < teamASpec.count; i++) {
            Mob m = spawnTeamMob(teamASpec, new Point(teamAColX, yStart + i));
            if (m != null) { teamA.add(m); spotsA.add(m.position); }
        }
        for (int i = 0; i < teamBSpec.count; i++) {
            Mob m = spawnTeamMob(teamBSpec, new Point(teamBColX, yStart + i));
            if (m != null) { teamB.add(m); spotsB.add(m.position); }
        }
        CombatArena.placeMobs(level, teamA, spotsA);
        CombatArena.placeMobs(level, teamB, spotsB);
        CombatArena.seedTeamHostility(teamA, teamB);
        for (Mob m : level.mobs) m.stateOfMind = Mob.StateOfMind.AWAKE;

        world = new World(new Level[]{level});
        world.linkLevels();
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
        // Block camera drag/pinch while the result banner is up.
        cameraController.setInputBlocker(() -> fightOver);

        log = new V2Log(ctx);
        // log.input() consumes everything when open and returns false
        // otherwise, so events fall through to the HUD + camera when the
        // log is closed. Same pattern PlayScreen uses for its log popup.
        Gdx.input.setInputProcessor(new InputMultiplexer(
                log.input(), cameraController, hudInput()));

        animator.consume(level);
        Settings.setAnimationTransientOverride(ARENA_ANIM_SPEED);
    }

    private Mob spawnTeamMob(V2ArenaSetup.TeamSpec spec, Point pos) {
        Mob m;
        if (spec.type.charClass != null) {
            m = MobFactory.player(pos, spec.type.charClass);
            switch (spec.type.charClass) {
                case WARRIOR -> m.behavior = Mob.Behavior.MOB;
                case ROGUE   -> m.behavior = Mob.Behavior.MOB;
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

    private static void seedMageRangedStats(Mob mage) {
        com.bjsp123.rl2.model.Item wand = null;
        for (com.bjsp123.rl2.model.Item it : mage.inventory.bag) {
            if (it != null && it.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
                wand = it; break;
            }
        }
        if (wand == null) return;
        // Seed a ranged attack from the wand's damage base; MobStats will
        // expand it into a [N/2, N] range and scale it by character level.
        mage.baseRangedDamage           = wand.damage;
        mage.intrinsic.rangedDistance   = 6;
        mage.intrinsic.rangedRateOfFire = 1;
        mage.intrinsic.rangedCost       = mage.intrinsic.attackCost;
        mage.statsDirty                 = true;
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        int dtMs = Math.min(100, (int) (Gdx.graphics.getDeltaTime() * 1000f));
        animator.queue.tick(Settings.framesPerRender());
        if (!fightOver && !paused) {
            int budget = com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
            while (budget-- > 0
                    && animator.queue.freezeFrames == 0
                    && !fightShouldEnd()) {
                advanceOneCombatTick();
                animator.consume(level);
            }
        }
        animator.consume(level);
        animator.tick(level, dtMs);
        com.bjsp123.rl2.logic.FireSystem.tickRealTime(level, dtMs);
        if (!com.bjsp123.rl2.ui.skin.Settings.fastGraphics()) {
            com.bjsp123.rl2.logic.LevelSystem.tickLightMotesRealTime(level, dtMs);
        }

        camera.update();
        levelRenderer.render(level, camera);

        if (!fightOver && fightShouldEnd()) {
            fightOver = true;
            if (!recordedToHallOfFame) {
                recordToHallOfFame();
                recordedToHallOfFame = true;
            }
        }

        // V2 chrome - HUD across the top, plus banner if the fight is over.
        ctx.applyProjection();
        renderHud();
        if (fightOver) renderBanner();
        if (log != null) log.renderSelf();
    }

    private void advanceOneCombatTick() {
        int beforeAcc = level.standardTurnTickAcc;
        CombatArena.tickHeadless(level, world, 0);
        if (level.standardTurnTickAcc < beforeAcc) standardTurnsElapsed++;
        levelRenderer.markDirty();
    }

    private void runFastForward() {
        int budget = FF_TICK_BUDGET;
        while (budget-- > 0
                && !fightOver
                && CombatArena.hostilePairExists(level)
                && standardTurnsElapsed < MAX_STANDARD_TURNS) {
            advanceOneCombatTick();
        }
        animator.consume(level);
        fightOver = true;
        if (!recordedToHallOfFame) {
            recordToHallOfFame();
            recordedToHallOfFame = true;
        }
    }

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
                && CombatArena.hostilePairExists(level)) return 0;
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

    private static String describeTeamSpec(V2ArenaSetup.TeamSpec spec) {
        return TextCatalog.format("ui.arena.teamSpec",
                TextCatalog.vars("label", spec.type.label,
                        "count", spec.count, "level", spec.level));
    }

    private static String describeSurvivors(List<Mob> team) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mob m : team) {
            if (m.hp <= 0) continue;
            String name = m.name != null ? m.name
                    : (m.mobType != null ? m.mobType.toLowerCase() : "?");
            counts.merge(name, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("x").append(e.getValue());
        }
        return sb.toString();
    }

    // -- V2 chrome rendering ------------------------------------------------

    private void renderHud() {
        // Layout the four HUD buttons in a row across the top of the viewport.
        float pad = 8f;
        float btnH = 32f;
        float gap = 4f;
        float[] widths = { 78f, 110f, 110f, 60f, 70f };
        float vh = ctx.worldH();
        float x = pad;
        float y = vh - pad - btnH;
        pauseBtn.set(x, y, widths[0], btnH); x += widths[0] + gap;
        ffBtn   .set(x, y, widths[1], btnH); x += widths[1] + gap;
        speedBtn.set(x, y, widths[2], btnH); x += widths[2] + gap;
        logBtn  .set(x, y, widths[3], btnH); x += widths[3] + gap;
        abortBtn.set(x, y, widths[4], btnH);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        drawBtn(s, pauseBtn, pausePressed);
        drawBtn(s, ffBtn,    ffPressed);
        drawBtn(s, speedBtn, speedPressed);
        drawBtn(s, logBtn,   logPressed);
        drawBtn(s, abortBtn, abortPressed);
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        ctx.batch.begin();
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get(paused ? "ui.arena.resume" : "ui.arena.pause"),
                pauseBtn.cx(), pauseBtn.cy() + 6f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get("ui.arena.fastForward"),
                ffBtn.cx(), ffBtn.cy() + 6f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.arena.speed",
                        TextCatalog.vars("speed", fastSpeed ? "4x" : "1x")),
                speedBtn.cx(), speedBtn.cy() + 6f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get("ui.arena.log"),
                logBtn.cx(), logBtn.cy() + 6f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_WARN,
                TextCatalog.get("ui.arena.abort"),
                abortBtn.cx(), abortBtn.cy() + 6f);
        ctx.batch.end();
    }

    private void renderBanner() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(320f, vw - 32f);
        float winH = 220f;
        bannerWindow.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        float btnW = (winW - 14f * 3) * 0.5f;
        float btnH = 40f;
        float btnY = bannerWindow.y + 16f;
        bannerFightAgain.set(bannerWindow.x + 14f,                 btnY, btnW, btnH);
        bannerBack      .set(bannerWindow.x + 14f + btnW + 14f,    btnY, btnW, btnH);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
        s.rect(0, 0, vw, vh);
        Window.drawShape(ctx, bannerWindow.x, bannerWindow.y,
                bannerWindow.w, bannerWindow.h);
        drawBtn(s, bannerFightAgain, bannerFightAgainPressed);
        drawBtn(s, bannerBack,       bannerBackPressed);
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        int winner = decideWinner();
        String headline =
                winner == 1 ? TextCatalog.get("ui.arena.teamAWins")
              : winner == 2 ? TextCatalog.get("ui.arena.teamBWins")
              : standardTurnsElapsed >= MAX_STANDARD_TURNS ? TextCatalog.get("ui.arena.drawTurnLimit")
              : TextCatalog.get("ui.arena.mutualWipe");

        ctx.batch.begin();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, headline,
                bannerWindow.cx(), bannerWindow.top() - ctx.headerLineH());
        TextDraw.centreFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.arena.teamSummary",
                        TextCatalog.vars("team", TextCatalog.get("ui.arena.teamA"),
                                "survivors", describeSurvivors(teamA))),
                bannerWindow.cx(), bannerWindow.top() - 64f,
                bannerWindow.w - 28f);
        TextDraw.centreFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.arena.teamSummary",
                        TextCatalog.vars("team", TextCatalog.get("ui.arena.teamB"),
                                "survivors", describeSurvivors(teamB))),
                bannerWindow.cx(), bannerWindow.top() - 86f,
                bannerWindow.w - 28f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get("ui.arena.fightAgain"),
                bannerFightAgain.cx(), bannerFightAgain.cy() + 6f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get("ui.arena.backToSetup"),
                bannerBack.cx(), bannerBack.cy() + 6f);
        ctx.batch.end();
    }

    private static void drawBtn(ShapeRenderer s, Rect r, boolean pressed) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
        s.setColor(pressed ? UIVars.BTN_PRESSED_BG : UIVars.HUD_BG);
        s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
    }

    // -- Input --------------------------------------------------------------

    private InputAdapter hudInput() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                if (fightOver) {
                    if (bannerFightAgain.contains(vx, vy)) { bannerFightAgainPressed = true; return true; }
                    if (bannerBack.contains(vx, vy))       { bannerBackPressed = true;       return true; }
                    if (!bannerWindow.contains(vx, vy))    return true;   // modal - eat the touch
                    return true;
                }

                if (pauseBtn.contains(vx, vy)) { pausePressed = true; return true; }
                if (ffBtn   .contains(vx, vy)) { ffPressed    = true; return true; }
                if (speedBtn.contains(vx, vy)) { speedPressed = true; return true; }
                if (logBtn  .contains(vx, vy)) { logPressed   = true; return true; }
                if (abortBtn.contains(vx, vy)) { abortPressed = true; return true; }
                return false;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                if (bannerFightAgainPressed) {
                    bannerFightAgainPressed = false;
                    if (bannerFightAgain.contains(vx, vy)) {
                        // Replace this V2Arena instance with a fresh fight
                        // without growing the back-stack.
                        game.setScreen(new V2Arena(game, teamASpec, teamBSpec));
                    }
                    return true;
                }
                if (bannerBackPressed) {
                    bannerBackPressed = false;
                    if (bannerBack.contains(vx, vy)) {
                        game.popScreen();
                    }
                    return true;
                }
                if (pausePressed) {
                    pausePressed = false;
                    if (pauseBtn.contains(vx, vy) && !fightOver) paused = !paused;
                    return true;
                }
                if (ffPressed) {
                    ffPressed = false;
                    if (ffBtn.contains(vx, vy) && !fightOver) runFastForward();
                    return true;
                }
                if (speedPressed) {
                    speedPressed = false;
                    if (speedBtn.contains(vx, vy)) {
                        fastSpeed = !fastSpeed;
                        Settings.setAnimationTransientOverride(
                                fastSpeed ? ARENA_ANIM_SPEED : NORMAL_ANIM_SPEED);
                    }
                    return true;
                }
                if (logPressed) {
                    logPressed = false;
                    if (logBtn.contains(vx, vy) && log != null) log.open();
                    return true;
                }
                if (abortPressed) {
                    abortPressed = false;
                    if (abortBtn.contains(vx, vy)) {
                        game.popScreen();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    game.popScreen();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            float oldZoom = camera.zoom;
            float oldX = camera.position.x, oldY = camera.position.y;
            camera.setToOrtho(false, width, height);
            camera.zoom = oldZoom;
            camera.position.set(oldX, oldY, 0);
            camera.update();
        }
        ctx.resize(width, height);
    }

    @Override
    public void hide() { Settings.setAnimationTransientOverride(0); }

    @Override
    public void dispose() {
        Settings.setAnimationTransientOverride(0);
        if (levelRenderer != null) levelRenderer.dispose();
    }
}
