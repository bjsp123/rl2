package com.bjsp123.rl2.ui.overlay;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TargetHistory;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

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
        Mob hostile = MobSystem.nearestHostile(player, level);
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

        // Reticle for the currently-aimed tile.
        if (target != null) {
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(Color.YELLOW);
            float x = target.tileX() * T;
            float y = target.tileY() * T;
            shapes.rect(x, y, T, T);
            shapes.rect(x + 1, y + 1, T - 2, T - 2);
            shapes.end();
        }
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

    /** Cycle through visible hostiles so the user can re-aim with a single keystroke. */
    private void cycleNextHostile() {
        Mob next = MobSystem.nearestHostile(player, level);
        if (next != null) target = next.position;
    }
}
