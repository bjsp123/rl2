package com.bjsp123.rl2.ui.overlay;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.bjsp123.rl2.logic.ItemStats;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.MobQueries;
import com.bjsp123.rl2.logic.MobStats;
import com.bjsp123.rl2.logic.TargetHistory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.ui.v2.UiCtx;
import com.bjsp123.rl2.ui.v2.UIVars;
import com.badlogic.gdx.graphics.g2d.BitmapFont;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.bjsp123.rl2.world.render.LevelRenderer;

/**
 * Keyboard / mouse / touch target picker. Activate with a callback, a {@link TargetHistory}
 * to pick the starting reticle, and a {@code sourceKey} identifying the action-slot item
 * that opened it (so a repeat-tap of the same item can confirm instead of re-opening).
 *
 * <p>Behavior once active:
 * <ul>
 *   <li>Arrow keys / numpad nudge the reticle but do NOT fire.</li>
 *   <li>Enter / Space confirms and fires the callback.</li>
 *   <li>A mouse click or touch anywhere in the world fires immediately at that tile.</li>
 *   <li>Tab cycles to the nearest visible hostile.</li>
 *   <li>Escape cancels without firing.</li>
 *   <li>Re-triggering the same source item externally (via {@link #confirm()}) fires at
 *       the current reticle - the calling screen detects the re-trigger and calls it.</li>
 * </ul>
 *
 * <p>On confirm, the target cell is recorded into {@link TargetHistory} so the next picker
 * (targeting or look) can start there.
 */
public class TargetingOverlay extends InputAdapter {

    private OrthographicCamera worldCamera;
    private ShapeRenderer shapes;
    private Mob player;
    private Level level;
    private TargetHistory history;
    private boolean active;
    private Point target;
    private Consumer<Point> onConfirm;
    private Object sourceKey;

    /** Valid target tiles for the current targeting action. Null when no action is active
     *  or all tiles are valid. Indexed [x][y] matching level dimensions. */
    private boolean[][] validTiles;
    private int validW, validH;

    public void create() {
        shapes = new ShapeRenderer();
    }

    public void setPlayer(Mob p)                       { this.player = p; }
    public void setLevel(Level l)                      { this.level  = l; }
    public void setWorldCamera(OrthographicCamera c)   { this.worldCamera = c; }
    public void setHistory(TargetHistory h)            { this.history = h; }
    public void setUiCtx(UiCtx c)                      { this.uiCtx = c; }
    private UiCtx uiCtx;
    private final Vector3 projectBuf = new Vector3();
    /** Frame counter for the reticle pulse animation. Wraps via modulo. */
    private int pulseFrame;

    /** Supply the set of tiles that are legal targets for the current action.
     *  Call before {@link #activate}. Pass {@code null} to clear (all tiles accepted). */
    public void setValidTiles(boolean[][] tiles, int w, int h) {
        this.validTiles = tiles;
        this.validW     = w;
        this.validH     = h;
    }

    public boolean isActive()   { return active; }
    public Point   target()     { return target; }
    /** Opaque key identifying the action that activated targeting (typically the bound
     *  {@code Item}). Used by the caller to detect same-source re-triggers. */
    public Object  sourceKey()  { return sourceKey; }

    /**
     * Open the picker. The starting reticle comes from {@link TargetHistory#pickInitial};
     * if no history has been provided it falls back to nearest-hostile, then the player's
     * own tile. {@code sourceKey} is stored so the caller can compare against the next
     * activation request and pick between "confirm current" and "switch to new action".
     */
    public void activate(Consumer<Point> onConfirm, Object sourceKey) {
        if (player == null || level == null) return;
        this.onConfirm = onConfirm;
        this.sourceKey = sourceKey;
        this.target    = pickInitialTarget();
        this.active    = true;
    }

    private Point pickInitialTarget() {
        // Charge (Jade Bull) defaults straight to the nearest CHARGEABLE hostile
        // - a foe standing on a valid (highlighted) tile - so the reticle starts
        // on something the player can actually dash into, not on history or a
        // foe that's too near / too far.
        if (sourceKey instanceof Item it && it.useBehavior == Item.UseBehavior.CHARGE) {
            Point c = nearestValidTileHostile();
            if (c != null) return c;
        }
        Mob hostile = MobQueries.nearestHostile(player, level);
        // Honour the remembered reticle only when it still sits on a mob, or
        // there's no visible hostile to aim at - never start on an empty tile
        // while a hostile is in view, even if the last shot hit empty ground.
        if (history != null) {
            Point p = history.pickInitial(level, player);
            if (p != null && (hostile == null || MobQueries.mobAt(level, p) != null)) {
                return p;
            }
        }
        return hostile != null ? hostile.position : player.position;
    }

    /** Nearest live mob standing on a currently-valid target tile (Chebyshev),
     *  or {@code null}. For charge the valid-tiles set is exactly the chargeable
     *  hostiles, so this picks the nearest one the player can dash into. */
    private Point nearestValidTileHostile() {
        if (validTiles == null || player == null || player.position == null
                || level == null || level.mobs == null) return null;
        int px = player.position.tileX(), py = player.position.tileY();
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        for (Mob m : level.mobs) {
            if (m == null || m == player || m.position == null || m.hp <= 0) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= validW || my >= validH) continue;
            if (!validTiles[mx][my]) continue;
            int d = Math.max(Math.abs(mx - px), Math.abs(my - py));
            if (d < bestD) { best = m; bestD = d; }
        }
        return best != null ? best.position : null;
    }

    public void cancel() {
        this.active    = false;
        this.onConfirm = null;
        this.sourceKey = null;
        this.validTiles = null;
    }

    /**
     * Fire the callback at the current reticle and close. Exposed so the screen can commit
     * the current target when the user re-taps the same action-slot button.
     */
    public void confirm() {
        if (!active || target == null) return;
        Point t = target;
        Consumer<Point> cb = onConfirm;
        if (history != null) history.record(level, t);
        active = false;
        onConfirm = null;
        sourceKey = null;
        if (cb != null) cb.accept(t);
    }

    public void render() {
        if (!active || worldCamera == null) return;
        shapes.setProjectionMatrix(worldCamera.combined);
        final float T = LevelRenderer.TILE_SIZE;

        if (validTiles != null) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

            // Tint pass - very slight yellow fill over every valid tile.
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 1f, 0.1f, 0.06f);
            for (int x = 0; x < validW; x++) {
                for (int y = 0; y < validH; y++) {
                    if (validTiles[x][y]) shapes.rect(x * T, y * T, T, T);
                }
            }
            shapes.end();

            // Outer-border pass - 1-px yellow segment on each edge that faces a
            // non-valid (or out-of-bounds) neighbour, producing a border around the
            // SET rather than around every individual tile.
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(1f, 1f, 0f, 0.75f);
            for (int x = 0; x < validW; x++) {
                for (int y = 0; y < validH; y++) {
                    if (!validTiles[x][y]) continue;
                    float px = x * T, py = y * T;
                    if (y == 0          || !validTiles[x][y - 1]) shapes.line(px,     py,     px + T, py);
                    if (y == validH - 1 || !validTiles[x][y + 1]) shapes.line(px,     py + T, px + T, py + T);
                    if (x == 0          || !validTiles[x - 1][y]) shapes.line(px,     py,     px,     py + T);
                    if (x == validW - 1 || !validTiles[x + 1][y]) shapes.line(px + T, py,     px + T, py + T);
                }
            }
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }

        // Trajectory preview - straight line from caster to where the actual
        // projectile would land. Drawn below the reticle so the reticle stays
        // on top. Surfaces LOS feedback: a clear yellow line means the shot
        // reaches the target; a red line clipped short with an X marker means
        // a wall or mob intercepts before the target tile.
        renderTrajectory();

        // AoE blast disc preview - the set of tiles a bomb or AoE wand will
        // actually affect, centred on the real impact tile (NOT the user-
        // aimed tile, which may be past a wall). Same packTilesAround call
        // the action uses at fire-time, with a deterministic seed so the
        // disc doesn't shimmer between frames.
        renderBlastDisc();

        // Reticle for the currently-aimed tile - four corner brackets with
        // a subtle pulse instead of a solid square. Reads as a target
        // marker without obscuring the tile contents.
        if (target != null) {
            pulseFrame = (pulseFrame + 1) % 40;
            float pulse = 0.65f + 0.35f * (float) Math.sin(pulseFrame * Math.PI / 20.0);
            float x = target.tileX() * T;
            float y = target.tileY() * T;
            float armLen = T * 0.30f;
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 1f, 0f, pulse);
            float thick = 1.5f;
            // Bottom-left
            shapes.rect(x,                 y,                 armLen, thick);
            shapes.rect(x,                 y,                 thick,  armLen);
            // Bottom-right
            shapes.rect(x + T - armLen,    y,                 armLen, thick);
            shapes.rect(x + T - thick,     y,                 thick,  armLen);
            // Top-left
            shapes.rect(x,                 y + T - thick,     armLen, thick);
            shapes.rect(x,                 y + T - armLen,    thick,  armLen);
            // Top-right
            shapes.rect(x + T - armLen,    y + T - thick,     armLen, thick);
            shapes.rect(x + T - thick,     y + T - armLen,    thick,  armLen);
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
        // Hit-chance / damage chip - rendered in screen-pixel space via the
        // V2 UiCtx so the text stays legible at any world-camera zoom.
        // Only fires when the target tile holds a hostile mob.
        renderTargetingChip();
        renderCycleHighlight();
        renderChargeLabels();
    }

    /** For a CHARGE source (Jade Bull), label every visible hostile that is NOT
     *  chargeable with a small yellow "too near" (needs a 2+ tile runway) or
     *  "too far" (beyond charge range). Chargeable enemies are already lit by
     *  the valid-tiles highlight, so they get no label. */
    private void renderChargeLabels() {
        if (player == null || player.position == null || level == null
                || level.visible == null || uiCtx == null || worldCamera == null) return;
        if (!(sourceKey instanceof Item it)
                || it.useBehavior != Item.UseBehavior.CHARGE) return;
        int radius = Math.max(1, ItemStats.effectiveRange(it, ItemStats.effectiveLevel(it, player)));
        int px = player.position.tileX(), py = player.position.tileY();
        List<ChipPlacement> labels = new ArrayList<>();
        for (Mob m : level.mobs) {
            if (m == null || m == player || m.position == null || m.hp <= 0) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(player, m)
                    != com.bjsp123.rl2.logic.MobSystem.Attitude.ATTACK) continue;
            int d = Math.max(Math.abs(mx - px), Math.abs(my - py));
            String label = d < 2 ? "too near" : (d > radius ? "too far" : null);
            if (label != null) labels.add(new ChipPlacement(m.position, label));
        }
        if (labels.isEmpty()) return;

        uiCtx.applyProjection();
        BitmapFont font = uiCtx.fontRegular;
        float prevScale = font.getScaleX();
        font.getData().setScale(prevScale * UIVars.CHIP_SCALE);
        uiCtx.batch.begin();
        font.setColor(UIVars.ACCENT);   // small yellow letters
        for (ChipPlacement cp : labels) {
            projectBuf.set(cp.tile.tileX() * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE * 0.5f,
                    cp.tile.tileY() * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE, 0f);
            worldCamera.project(projectBuf);
            int sx = Math.round(projectBuf.x);
            int sy = Gdx.graphics.getHeight() - Math.round(projectBuf.y);
            float vx = uiCtx.unprojectX(sx, sy);
            float vy = uiCtx.unprojectY(sx, sy);
            uiCtx.layout.setText(font, cp.text);
            font.draw(uiCtx.batch, cp.text, vx - uiCtx.layout.width * 0.5f,
                    vy + UIVars.CHIP_LIFT + uiCtx.layout.height);
        }
        font.setColor(Color.WHITE);
        uiCtx.batch.end();
        font.getData().setScale(prevScale);
    }

    /** True when the active source action travels along a line that can be
     *  intercepted by a wall or a body in the way - i.e. drawing a trajectory
     *  preview is meaningful. WAND-TELEPORT, JUMP and CHARGE all bypass
     *  intermediate tiles in different ways, so they get no preview. */
    private boolean shouldShowTrajectory() {
        if (!(sourceKey instanceof Item it)) return false;
        if (it.useBehavior == null) return false;
        return switch (it.useBehavior) {
            case WAND -> it.wandEffect != Item.ItemEffect.TELEPORT;
            case GRAPPLE, NONE -> true;
            default -> false;
        };
    }

    /** Where the projectile actually lands - same call fireWand / throwItem
     *  make at fire-time. Returns the target tile unchanged for non-projectile
     *  sources (jumps, teleports) which arrive at the aimed tile directly. */
    private Point resolveImpact() {
        if (player == null || player.position == null || target == null) return target;
        if (!shouldShowTrajectory()) return target;
        Point impact = com.bjsp123.rl2.logic.MobSystem.firstMobBlocking(
                level, player.position, target, player);
        return impact != null ? impact : target;
    }

    /** AoE radius of the current source action measured as a tile-count
     *  (matches packTilesAround's "size" semantics). Zero when the action
     *  has no AoE or no current source. */
    private int effectiveAoeSize() {
        if (!(sourceKey instanceof Item it) || player == null) return 0;
        return com.bjsp123.rl2.logic.ItemStats.effectiveSize(it,
                com.bjsp123.rl2.logic.ItemStats.effectiveLevel(it, player));
    }

    /** Draw the world-space trajectory from caster to the actual impact tile.
     *  Impact is computed via {@link com.bjsp123.rl2.logic.MobSystem#firstMobBlocking}
     *  - the exact routine fireWand / throwItem use - so the preview is
     *  pixel-faithful to where the projectile will really land. */
    private void renderTrajectory() {
        if (!shouldShowTrajectory()) return;
        if (player == null || player.position == null || target == null || level == null) return;
        Point from = player.position;
        if (from.tileX() == target.tileX() && from.tileY() == target.tileY()) return;
        Point impact = com.bjsp123.rl2.logic.MobSystem.firstMobBlocking(level, from, target, player);
        if (impact == null) return;
        boolean clipped = !(impact.tileX() == target.tileX()
                         && impact.tileY() == target.tileY());

        final float T = LevelRenderer.TILE_SIZE;
        float x0 = from.tileX()   * T + T * 0.5f;
        float y0 = from.tileY()   * T + T * 0.5f;
        float x1 = impact.tileX() * T + T * 0.5f;
        float y1 = impact.tileY() * T + T * 0.5f;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (clipped) shapes.setColor(1f, 0.35f, 0.2f, 0.55f);
        else         shapes.setColor(1f, 1f,    0.3f, 0.45f);
        shapes.rectLine(x0, y0, x1, y1, 1.5f);
        // Small + marker at the clipped impact tile so the eye lands on
        // "stops here" rather than just "shorter line".
        if (clipped) {
            float s = T * 0.18f;
            shapes.rect(x1 - s,      y1 - 0.75f, 2 * s, 1.5f);
            shapes.rect(x1 - 0.75f,  y1 - s,     1.5f,  2 * s);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Render the union of tiles an AoE source (bomb / AoE wand, size >= 2)
     *  will affect. Centred on the resolved impact tile so the preview is
     *  honest when LOS clips the throw against a wall. Translucent orange
     *  fill + 1-px outer border on edges that face non-disc tiles. */
    private void renderBlastDisc() {
        int aoeSize = effectiveAoeSize();
        if (aoeSize < 2) return;
        if (player == null || target == null || level == null) return;
        Point centre = resolveImpact();
        if (centre == null) return;
        // Deterministic per-impact seed - same packTilesAround the action
        // uses at fire-time, but stable from frame to frame and not pulling
        // from the global RNG that gameplay rolls share.
        long seed = ((long) centre.tileX() * 73856093L) ^ ((long) centre.tileY() * 19349663L);
        java.util.List<Point> disc = com.bjsp123.rl2.logic.ItemSystem.packTilesAround(
                level, centre, aoeSize, new java.util.Random(seed));
        if (disc.isEmpty()) return;

        // O(1) membership for the border pass via packed (x,y) keys.
        java.util.Set<Long> inDisc = new java.util.HashSet<>(disc.size() * 2);
        for (Point p : disc) inDisc.add(packXY(p.tileX(), p.tileY()));

        final float T = LevelRenderer.TILE_SIZE;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Translucent orange fill on each disc tile.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0.45f, 0.1f, 0.18f);
        for (Point p : disc) shapes.rect(p.tileX() * T, p.tileY() * T, T, T);
        shapes.end();

        // Outer border: one segment per edge that faces a non-disc neighbour.
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 0.55f, 0.1f, 0.85f);
        for (Point p : disc) {
            int x = p.tileX(), y = p.tileY();
            float px = x * T, py = y * T;
            if (!inDisc.contains(packXY(x,     y - 1))) shapes.line(px,     py,     px + T, py);
            if (!inDisc.contains(packXY(x,     y + 1))) shapes.line(px,     py + T, px + T, py + T);
            if (!inDisc.contains(packXY(x - 1, y    ))) shapes.line(px,     py,     px,     py + T);
            if (!inDisc.contains(packXY(x + 1, y    ))) shapes.line(px + T, py,     px + T, py + T);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private static long packXY(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /** Render the small "85% · ~6 dmg" chip(s) near the target tile, in V2
     *  virtual coordinates so the font scales with the UI not the world
     *  camera. Modes:
     *  <ul>
     *    <li>AOE source (bomb or AoE wand, effectSize >= 2) - per-mob damage
     *        chip above every mob in the blast disc. No hit %.</li>
     *    <li>Single-target bomb (effectSize <= 1) - one damage chip at the
     *        target. No hit % (throws always hit).</li>
     *    <li>Anything else - one hit% + damage chip at the target.</li>
     *  </ul>
     *  No-op when no V2 ctx is wired (headless / arena) or no mobs match. */
    private void renderTargetingChip() {
        if (target == null || player == null || level == null || uiCtx == null) return;
        if (worldCamera == null) return;
        Item sourceItem = (sourceKey instanceof Item) ? (Item) sourceKey : null;

        int aoeSize = effectiveAoeSize();
        // Wands always land at the target tile. AOE bombs (effectSize >= 2)
        // also always land. Single-target throws (throwing knives, etc.) now
        // roll-to-hit using the same accuracy-vs-evasion math as melee, so
        // they get a hit% chip just like a melee swing.
        boolean isWand = sourceItem != null
                && sourceItem.useBehavior == Item.UseBehavior.WAND;
        boolean alwaysHits = isWand || aoeSize >= 2;
        // AoE chips centre on the resolved impact (matches the disc and the
        // actual blast); single-target chips stay on the aimed tile.
        Point chipCentre = aoeSize >= 2 ? resolveImpact() : target;

        List<ChipPlacement> chips = new ArrayList<>();
        if (aoeSize >= 2) {
            // AoE: chip per affected mob in the disc.
            long discSeed = ((long) chipCentre.tileX() * 73856093L)
                          ^ ((long) chipCentre.tileY() * 19349663L);
            for (Point p : ItemSystem.packTilesAround(level, chipCentre, aoeSize,
                    new java.util.Random(discSeed))) {
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || m == player) continue;
                if (com.bjsp123.rl2.logic.MobSystem.isAlly(player, m)) continue;
                String chip = composeDamageOnly(player, m, sourceItem);
                if (chip == null) continue;
                chips.add(new ChipPlacement(p, chip));
            }
        } else {
            Mob victim = MobQueries.mobAt(level, target);
            if (victim == null || victim == player) return;
            if (com.bjsp123.rl2.logic.MobSystem.isAlly(player, victim)) return;
            String chip = alwaysHits
                    ? composeDamageOnly(player, victim, sourceItem)
                    : composeHitAndDamage(player, victim);
            if (chip == null) return;
            chips.add(new ChipPlacement(target, chip));
        }
        if (chips.isEmpty()) return;

        // Two passes - one shape pass for every chip's pill backdrop, one
        // batch pass for every chip's text. Font temporarily down-scaled so
        // the chip reads as an annotation, not a billboard.
        uiCtx.applyProjection();
        BitmapFont font = uiCtx.fontRegular;
        float prevScale = font.getScaleX();
        font.getData().setScale(prevScale * UIVars.CHIP_SCALE);

        // Compute positions and per-chip rects from the world tile centres.
        float[][] geom = new float[chips.size()][];
        for (int i = 0; i < chips.size(); i++) {
            ChipPlacement cp = chips.get(i);
            projectBuf.set(cp.tile.tileX() * LevelRenderer.TILE_SIZE
                            + LevelRenderer.TILE_SIZE * 0.5f,
                    cp.tile.tileY() * LevelRenderer.TILE_SIZE
                            + LevelRenderer.TILE_SIZE,
                    0f);
            worldCamera.project(projectBuf);
            int sx = Math.round(projectBuf.x);
            int sy = Gdx.graphics.getHeight() - Math.round(projectBuf.y);
            float vx = uiCtx.unprojectX(sx, sy);
            float vy = uiCtx.unprojectY(sx, sy);
            uiCtx.layout.setText(font, cp.text);
            float w = uiCtx.layout.width;
            float h = uiCtx.layout.height;
            // baseline sits at vy + lift; the visible glyph rises ABOVE that
            // baseline by `h` (cap-height in libGDX), so the background rect
            // ranges [baseline-1, baseline+h+1] vertically.
            float baselineY = vy + UIVars.CHIP_LIFT;
            float bgX = vx - w * 0.5f - UIVars.CHIP_PAD;
            float bgY = baselineY - 2f;
            float bgW = w + 2 * UIVars.CHIP_PAD;
            float bgH = h + 2 * UIVars.CHIP_PAD;
            geom[i] = new float[] { vx - w * 0.5f, baselineY, bgX, bgY, bgW, bgH };
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        uiCtx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        uiCtx.shapes.setColor(0f, 0f, 0f, UIVars.CHIP_BG_ALPHA);
        for (float[] g : geom) uiCtx.shapes.rect(g[2], g[3], g[4], g[5]);
        uiCtx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        uiCtx.batch.begin();
        font.setColor(UIVars.ACCENT);
        for (int i = 0; i < chips.size(); i++) {
            float[] g = geom[i];
            font.draw(uiCtx.batch, chips.get(i).text, g[0], g[1] + uiCtx.layout.height);
        }
        font.setColor(Color.WHITE);
        uiCtx.batch.end();

        font.getData().setScale(prevScale);
    }

    /** One chip ready to render - tile in world space, text already formatted. */
    private record ChipPlacement(Point tile, String text) {}

/** Hit% + damage chip. Used for ranged single-target attacks (wand
     *  MISSILE, melee) where the hit-roll matters. */
    private static String composeHitAndDamage(Mob player, Mob victim) {
        int hitPct = (int) Math.round(MobStats.hitChance(player, victim) * 100.0);
        return hitPct + "% · " + damageString(player, victim, null);
    }

    /** Damage-only chip. Used for always-hit sources (bombs, AoE wands) -
     *  the hit-roll is meaningless because the action lands at the target
     *  tile regardless. For bomb-class sources we use the bomb's own
     *  effectiveDamageRange (so a cherry-bomb damage chip is the bomb's
     *  damage, not the equipped weapon's). */
    private static String composeDamageOnly(Mob player, Mob victim, Item source) {
        return damageString(player, victim, source);
    }

    /** Build the "~6-3=~3 dmg" / "~6 dmg" half of a chip. When {@code source}
     *  is non-null, uses the source item's damage range; otherwise falls
     *  back to the attacker's equipped raw range (melee / generic). Routes
     *  through {@link MobStats#netDamageRange(MinMax, MinMax, MinMax)} so
     *  the chip and the actual combat resolution use the identical formula. */
    private static String damageString(Mob player, Mob victim, Item source) {
        MinMax raw = source != null
                ? ItemStats.effectiveDamageRange(source,
                        ItemStats.effectiveLevel(source, player))
                : MobStats.rawDamageRange(player);
        MinMax armor = MobStats.resistRange(victim);
        MinMax ap = MobStats.apDamageRange(player);
        MinMax net = MobStats.netDamageRange(raw, armor, ap);
        int netMid = (net.min() + net.max()) / 2;
        int rawMid = (raw.min() + raw.max()) / 2;
        int armorMid = (armor.min() + armor.max()) / 2;
        if (armorMid > 0 && rawMid > 0) {
            return "~" + rawMid + "-" + armorMid + "=~" + netMid + " dmg";
        }
        return "~" + netMid + " dmg";
    }

    public void dispose() {
        if (shapes != null) shapes.dispose();
    }

    @Override
    public boolean keyDown(int keycode) {
        if (!active) return false;
        int dx = 0, dy = 0;
        switch (keycode) {
            case Input.Keys.LEFT,  Input.Keys.NUMPAD_4 -> dx = -1;
            case Input.Keys.RIGHT, Input.Keys.NUMPAD_6 -> dx = +1;
            case Input.Keys.UP,    Input.Keys.NUMPAD_8 -> dy = +1;
            case Input.Keys.DOWN,  Input.Keys.NUMPAD_2 -> dy = -1;
            case Input.Keys.NUMPAD_7 -> { dx = -1; dy = +1; }
            case Input.Keys.NUMPAD_9 -> { dx = +1; dy = +1; }
            case Input.Keys.NUMPAD_1 -> { dx = -1; dy = -1; }
            case Input.Keys.NUMPAD_3 -> { dx = +1; dy = -1; }
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER, Input.Keys.SPACE -> {
                confirm();
                return true;
            }
            case Input.Keys.ESCAPE -> { cancel(); return true; }
            case Input.Keys.TAB -> { cycleNextHostile(); return true; }
            default -> { return true; }
        }
        moveTarget(dx, dy);
        return true;
    }

    @Override public boolean keyUp(int keycode) { return active; }

    /**
     * A mouse click or touch anywhere in the world fires the action immediately at that
     * tile - no confirm step. Clicks outside the grid are swallowed but do nothing.
     */
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!active || worldCamera == null || level == null) return false;
        Vector3 world = worldCamera.unproject(new Vector3(screenX, screenY, 0));
        int tx = (int) Math.floor(world.x / LevelRenderer.TILE_SIZE);
        int ty = (int) Math.floor(world.y / LevelRenderer.TILE_SIZE);
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return true;
        target = new Point(tx, ty);
        confirm();
        return true;
    }

    @Override public boolean touchUp(int x, int y, int p, int b)       { return active; }
    @Override public boolean touchDragged(int x, int y, int p)         { return active; }
    @Override public boolean scrolled(float x, float y)                { return active; }

    private void moveTarget(int dx, int dy) {
        if (target == null || (dx == 0 && dy == 0)) return;
        int nx = target.tileX() + dx;
        int ny = target.tileY() + dy;
        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) return;
        target = new Point(nx, ny);
    }

    /** Frames remaining on the "you just cycled" cue: the reticle pulses
     *  brighter and the new target's name floats above the tile. Bumped by
     *  {@link #cycleNextHostile}; duration sourced from {@link UIVars#CYCLE_HIGHLIGHT_FRAMES}. */
    private int cycleHighlightFrames;
    private String cycleHighlightName;

    /** Cycle through visible hostiles so the user can re-aim with a single
     *  keystroke. Iterates in Chebyshev-distance order and picks the entry
     *  AFTER the current target so a repeat-tab rotates through every
     *  hostile rather than re-snapping to the nearest. */
    private void cycleNextHostile() {
        if (player == null || level == null) return;
        java.util.List<Mob> sorted = new ArrayList<>();
        for (Mob m : level.mobs) {
            if (m == player) continue;
            if (m.position == null) continue;
            if (com.bjsp123.rl2.logic.MobSystem.isAlly(m, player)) continue;
            if (m.behavior == Mob.Behavior.INANIMATE) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            sorted.add(m);
        }
        if (sorted.isEmpty()) return;
        final Point origin = player.position;
        sorted.sort((a, b) -> Integer.compare(
                com.bjsp123.rl2.logic.LevelFactoryUtils.chebyshev(a.position, origin),
                com.bjsp123.rl2.logic.LevelFactoryUtils.chebyshev(b.position, origin)));
        // Find the current target's index in the sorted list (if it points at
        // a hostile) and advance to the next one with wrap-around.
        int currentIdx = -1;
        if (target != null) {
            for (int i = 0; i < sorted.size(); i++) {
                Mob m = sorted.get(i);
                if (m.position.tileX() == target.tileX()
                        && m.position.tileY() == target.tileY()) {
                    currentIdx = i;
                    break;
                }
            }
        }
        Mob next = sorted.get((currentIdx + 1) % sorted.size());
        target = next.position;
        cycleHighlightFrames = UIVars.CYCLE_HIGHLIGHT_FRAMES;
        cycleHighlightName   = next.name != null && !next.name.isEmpty()
                ? next.name
                : (next.mobType != null ? next.mobType.toLowerCase() : "target");
    }

    /** Brief floater drawn above the reticle for ~24 frames after Tab-cycle.
     *  Reuses the chip render path (V2 screen-space, small font, opaque
     *  background) so it stays legible regardless of zoom. */
    private void renderCycleHighlight() {
        if (cycleHighlightFrames <= 0 || target == null || uiCtx == null || worldCamera == null) return;
        cycleHighlightFrames--;
        if (cycleHighlightName == null) return;
        projectBuf.set(target.tileX() * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE * 0.5f,
                target.tileY() * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE,
                0f);
        worldCamera.project(projectBuf);
        int sx = Math.round(projectBuf.x);
        int sy = Gdx.graphics.getHeight() - Math.round(projectBuf.y);
        float vx = uiCtx.unprojectX(sx, sy);
        float vy = uiCtx.unprojectY(sx, sy);
        // Lift the name above where the damage chip would sit so they don't
        // overlap on the same target.
        uiCtx.applyProjection();
        com.badlogic.gdx.graphics.g2d.BitmapFont font = uiCtx.fontRegular;
        float prev = font.getScaleX();
        font.getData().setScale(prev * UIVars.CYCLE_HIGHLIGHT_SCALE);
        uiCtx.layout.setText(font, cycleHighlightName);
        float w = uiCtx.layout.width;
        float h = uiCtx.layout.height;
        float bgX = vx - w * 0.5f - UIVars.CHIP_PAD;
        float bgY = vy + UIVars.CYCLE_HIGHLIGHT_LIFT - 2f;
        float bgW = w + 2 * UIVars.CHIP_PAD;
        float bgH = h + 2 * UIVars.CHIP_PAD;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        uiCtx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        uiCtx.shapes.setColor(0f, 0f, 0f, UIVars.CHIP_BG_ALPHA);
        uiCtx.shapes.rect(bgX, bgY, bgW, bgH);
        uiCtx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        uiCtx.batch.begin();
        font.setColor(UIVars.ACCENT);
        font.draw(uiCtx.batch, cycleHighlightName, vx - w * 0.5f, vy + UIVars.CYCLE_HIGHLIGHT_LIFT + h);
        font.setColor(Color.WHITE);
        uiCtx.batch.end();
        font.getData().setScale(prev);
    }
}
