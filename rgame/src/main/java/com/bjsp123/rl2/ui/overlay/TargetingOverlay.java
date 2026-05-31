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
import com.bjsp123.rl2.logic.MobTargeting;
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
        if (history != null) {
            Point p = history.pickInitial(level, player);
            if (p != null) return p;
        }
        Mob hostile = MobTargeting.nearestHostile(player, level);
        return hostile != null ? hostile.position : player.position;
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
    }

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

        int aoeSize = sourceItem != null
                ? ItemStats.effectiveSize(sourceItem,
                        ItemStats.effectiveLevel(sourceItem, player))
                : 0;
        // Wands always land at the target tile. AOE bombs (effectSize >= 2)
        // also always land. Single-target throws (throwing knives, etc.) now
        // roll-to-hit using the same accuracy-vs-evasion math as melee, so
        // they get a hit% chip just like a melee swing.
        boolean isWand = sourceItem != null
                && sourceItem.useBehavior == Item.UseBehavior.WAND;
        boolean alwaysHits = isWand || aoeSize >= 2;

        List<ChipPlacement> chips = new ArrayList<>();
        if (aoeSize >= 2) {
            // AoE: chip per affected mob in the disc.
            for (Point p : ItemSystem.packTilesAround(level, target, aoeSize)) {
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || m == player) continue;
                if (MobSystem_isAlly(player, m)) continue;
                String chip = composeDamageOnly(player, m, sourceItem);
                if (chip == null) continue;
                chips.add(new ChipPlacement(p, chip));
            }
        } else {
            Mob victim = MobQueries.mobAt(level, target);
            if (victim == null || victim == player) return;
            if (MobSystem_isAlly(player, victim)) return;
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
        float chipScale = prevScale * 0.62f;
        font.getData().setScale(chipScale);

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
            float lift = 6f;
            float pad  = 3f;
            float baselineY = vy + lift;
            float bgX = vx - w * 0.5f - pad;
            float bgY = baselineY - 2f;
            float bgW = w + 2 * pad;
            float bgH = h + 2 * pad;
            geom[i] = new float[] { vx - w * 0.5f, baselineY, bgX, bgY, bgW, bgH };
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        uiCtx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        uiCtx.shapes.setColor(0f, 0f, 0f, 0.78f);
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

    /** Quick same-team check that avoids a hard import dependency on
     *  MobSystem.getAttitudeToMob from this file. Returns true when {@code
     *  victim} shares the player's faction (so the chip doesn't show over
     *  pets and allies). */
    private static boolean MobSystem_isAlly(Mob player, Mob victim) {
        if (player == null || victim == null) return false;
        return com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(player, victim)
                == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY;
    }

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
     *  back to the attacker's equipped raw range (melee / generic). */
    private static String damageString(Mob player, Mob victim, Item source) {
        MinMax raw = source != null
                ? ItemStats.effectiveDamageRange(source,
                        ItemStats.effectiveLevel(source, player))
                : MobStats.rawDamageRange(player);
        MinMax armor = MobStats.resistRange(victim);
        MinMax ap = MobStats.apDamageRange(player);
        int rawMid = (raw.min() + raw.max()) / 2;
        int armorMid = (armor.min() + armor.max()) / 2;
        int apMid = (ap.min() + ap.max()) / 2;
        int netMid = Math.max(0, rawMid - armorMid) + apMid;
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
     *  {@link #cycleNextHostile}. */
    private int cycleHighlightFrames;
    private String cycleHighlightName;
    private static final int CYCLE_HIGHLIGHT_DURATION = 24;

    /** Cycle through visible hostiles so the user can re-aim with a single
     *  keystroke. Iterates in Chebyshev-distance order and picks the entry
     *  AFTER the current target so a repeat-tab rotates through every
     *  hostile rather than re-snapping to the nearest. */
    private void cycleNextHostile() {
        if (player == null || level == null) return;
        java.util.List<Mob> sorted = new ArrayList<>();
        int ax = player.position.tileX(), ay = player.position.tileY();
        for (Mob m : level.mobs) {
            if (m == player) continue;
            if (m.position == null) continue;
            if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(m, player)
                    == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) continue;
            if (m.behavior == Mob.Behavior.INANIMATE) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            sorted.add(m);
        }
        if (sorted.isEmpty()) return;
        sorted.sort((a, b) -> {
            int da = Math.max(Math.abs(a.position.tileX() - ax),
                              Math.abs(a.position.tileY() - ay));
            int db = Math.max(Math.abs(b.position.tileX() - ax),
                              Math.abs(b.position.tileY() - ay));
            return Integer.compare(da, db);
        });
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
        cycleHighlightFrames = CYCLE_HIGHLIGHT_DURATION;
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
        float lift = 22f;
        uiCtx.applyProjection();
        com.badlogic.gdx.graphics.g2d.BitmapFont font = uiCtx.fontRegular;
        float prev = font.getScaleX();
        font.getData().setScale(prev * 0.72f);
        uiCtx.layout.setText(font, cycleHighlightName);
        float w = uiCtx.layout.width;
        float h = uiCtx.layout.height;
        float pad = 3f;
        float bgX = vx - w * 0.5f - pad;
        float bgY = vy + lift - 2f;
        float bgW = w + 2 * pad;
        float bgH = h + 2 * pad;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        uiCtx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        uiCtx.shapes.setColor(0f, 0f, 0f, 0.78f);
        uiCtx.shapes.rect(bgX, bgY, bgW, bgH);
        uiCtx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        uiCtx.batch.begin();
        font.setColor(UIVars.ACCENT);
        font.draw(uiCtx.batch, cycleHighlightName, vx - w * 0.5f, vy + lift + h);
        font.setColor(Color.WHITE);
        uiCtx.batch.end();
        font.getData().setScale(prev);
    }
}
