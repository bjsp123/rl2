package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.FireSystem;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.LevelFactory;
import com.bjsp123.rl2.logic.LevelFactoryPopulate;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.util.PlayerGearProvider;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.world.anim.Animator;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;

import java.util.Random;

/** Title-screen dungeon preview: a disposable, visual-only world simulation. */
final class AttractMode {
    private static final float LEVEL_SECONDS = 62f;
    private static final float FADE_SECONDS = 5f;
    private static final float EXTRA_MOB_SECONDS = 8f;
    private static final int TARGET_SHORT_SIDE_TILES = 30;
    private static final int MIN_LEVEL_TILES = 26;
    private static final int MAX_LEVEL_TILES = 78;
    private static final int CELL = 16;
    private static final float BORDER_TILES = 2.5f;

    private final UiCtx ctx;
    private final Random rng = new Random();
    private final OrthographicCamera camera = new OrthographicCamera();
    private final DefaultLevelRenderer renderer = new DefaultLevelRenderer();
    private final Animator animator = new Animator();
    private final UniqueTracker unique = new UniqueTracker();
    /** One gear catalogue for the lifetime of attract mode. Non-deterministic
     *  seed so each launch's demo visuals vary. */
    private final PlayerGearProvider gear =
            new PlayerGearProvider(System.nanoTime());

    private World world;
    private Level level;
    private Mob attractPlayer;
    private int requestedLevelW;
    private int requestedLevelH;
    private float levelAge;
    private float mobAge;

    AttractMode(UiCtx ctx) {
        this.ctx = ctx;
        renderer.create();
        rebuild();
    }

    void resize() {
        int[] size = levelSizeForWindow();
        if (level != null && (size[0] != requestedLevelW || size[1] != requestedLevelH)) {
            rebuild();
        } else {
            fitCamera();
        }
    }

    void dispose() {
        renderer.dispose();
    }

    void render(float delta) {
        if (level == null) rebuild();
        levelAge += Math.min(0.1f, Math.max(0f, delta));
        mobAge += Math.min(0.1f, Math.max(0f, delta));
        if (levelAge >= LEVEL_SECONDS) rebuild();

        if (mobAge >= EXTRA_MOB_SECONDS) {
            mobAge = 0f;
            LevelFactoryPopulate.spawnAttractMob(level, rng, unique);
            renderer.markDirty();
        }

        ensurePlayerAlive();
        tick(delta);
        revealAll(level);
        fitCamera();
        renderer.render(level, camera);
        drawFadeOverlay();
        ctx.applyProjection();
    }

    private void rebuild() {
        levelAge = 0f;
        mobAge = 0f;
        int depth = 1 + rng.nextInt(Math.max(1, GameBalance.DUNGEON_DEPTH));
        int[] size = levelSizeForWindow();
        requestedLevelW = size[0];
        requestedLevelH = size[1];
        Level.VisualTheme[] themes = Level.VisualTheme.values();
        Level.VisualTheme theme = themes[rng.nextInt(themes.length)];
        level = LevelFactory.createDungeonLevel(requestedLevelW, requestedLevelH, depth,
                false, false, theme, unique, rng.nextLong());
        level.initTransients();
        world = new World();
        world.levels = new Level[]{ level };
        world.currentLevelIndex = 0;
        world.linkLevels();
        renderer.setWorld(world);
        renderer.setAnimator(animator);
        spawnPlayer();
        revealAll(level);
        LevelSystem.computeLighting(level);
        revealAll(level);
        renderer.markDirty();
        fitCamera();
    }

    private void tick(float delta) {
        int frames = Math.max(1, (int) Math.ceil(delta * 60f));
        for (int i = 0; i < frames; i++) {
            animator.queue.tick(com.bjsp123.rl2.ui.skin.Settings.framesPerRender());
            if (animator.queue.freezeFrames == 0 && TurnSystem.tick(level)) {
                world.tick++;
                level.currentTurn = TurnSystem.standardTurnForTick(world.tick);
                renderer.markDirty();
            }
            animator.consume(level);
            animator.tick(level, 16);
            if (animator.impactFiredThisTick) renderer.markDirty();
        }
        FireSystem.tickRealTime(level, Math.min(100, (int) (delta * 1000f)));
        LevelSystem.tickLightMotesRealTime(level, Math.min(100, (int) (delta * 1000f)));
    }

    private void spawnPlayer() {
        Point p = level.spawnPoint != null ? level.spawnPoint : randomOpenFloor();
        if (p == null) p = new Point(2, 2);
        Mob.CharacterClass[] classes = Mob.CharacterClass.values();
        attractPlayer = MobFactory.player(p, classes[rng.nextInt(classes.length)]);
        attractPlayer.behavior = Mob.Behavior.MOB;
        attractPlayer.stateOfMind = Mob.StateOfMind.AWAKE;
        // Scale player's intrinsic stats AND equip depth-appropriate gear so
        // a deep-dungeon demo doesn't show the player swinging the L1 starter
        // kit at deep-tier enemies.
        int depth = Math.max(1, level.depth);
        MobProgression.setSpawnLevel(attractPlayer, depth);
        gear.applyKit(attractPlayer, gear.kitForDepth(depth));
        MobProgression.autoLevelUpPerks(attractPlayer, rng);
        level.mobs.add(attractPlayer);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(attractPlayer, p));
        }
        renderer.markDirty();
    }

    private void ensurePlayerAlive() {
        if (attractPlayer != null && attractPlayer.hp > 0 && level.mobs.contains(attractPlayer)) return;
        spawnPlayer();
    }

    private Point randomOpenFloor() {
        for (int tries = 0; tries < 80; tries++) {
            int x = rng.nextInt(level.width);
            int y = rng.nextInt(level.height);
            Point p = new Point(x, y);
            if (level.tiles[x][y] == Tile.FLOOR
                    && com.bjsp123.rl2.logic.MobQueries.mobAt(level, p) == null) {
                return p;
            }
        }
        return null;
    }

    private void fitCamera() {
        float worldPxW = level.width * CELL;
        float worldPxH = level.height * CELL;
        float borderPx = BORDER_TILES * CELL;
        float screenW = Math.max(1f, windowPixelW());
        float screenH = Math.max(1f, windowPixelH());
        camera.setToOrtho(false, screenW, screenH);
        camera.position.set(worldPxW * 0.5f, worldPxH * 0.5f, 0f);
        camera.zoom = Math.max(
                (worldPxW + borderPx * 2f) / screenW,
                (worldPxH + borderPx * 2f) / screenH);
        camera.update();
    }

    private int[] levelSizeForWindow() {
        float w = Math.max(1f, windowPixelW());
        float h = Math.max(1f, windowPixelH());
        float aspect = w / h;
        int levelW;
        int levelH;
        if (aspect >= 1f) {
            levelH = TARGET_SHORT_SIDE_TILES;
            levelW = Math.round(levelH * aspect);
        } else {
            levelW = TARGET_SHORT_SIDE_TILES;
            levelH = Math.round(levelW / aspect);
        }
        return new int[]{
                clamp(levelW, MIN_LEVEL_TILES, MAX_LEVEL_TILES),
                clamp(levelH, MIN_LEVEL_TILES, MAX_LEVEL_TILES)
        };
    }

    private int windowPixelW() {
        int w = ctx.viewport.getScreenWidth();
        return w > 0 ? w : Gdx.graphics.getWidth();
    }

    private int windowPixelH() {
        int h = ctx.viewport.getScreenHeight();
        return h > 0 ? h : Gdx.graphics.getHeight();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void revealAll(Level level) {
        if (level == null) return;
        if (level.visible == null || level.explored == null || level.lit == null) {
            level.initTransients();
        }
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                level.visible[x][y] = true;
                level.explored[x][y] = true;
                level.lit[x][y] = true;
            }
        }
    }

    private void drawFadeOverlay() {
        float fadeIn = Math.min(1f, levelAge / FADE_SECONDS);
        float fadeOut = levelAge > LEVEL_SECONDS - FADE_SECONDS
                ? (levelAge - (LEVEL_SECONDS - FADE_SECONDS)) / FADE_SECONDS
                : 0f;
        float alpha = Math.max(1f - fadeIn, Math.min(1f, fadeOut));
        if (alpha <= 0f) return;
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        ctx.shapes.setColor(0f, 0f, 0f, alpha);
        ctx.shapes.rect(0f, 0f, ctx.worldW(), ctx.worldH());
        ctx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        ctx.shapes.setColor(Color.WHITE);
    }
}
