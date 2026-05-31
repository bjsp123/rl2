package com.bjsp123.rl2.input;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.bjsp123.rl2.logic.TargetHistory;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.world.render.LevelRenderer;

/**
 * "Look" mode: a world-space cursor the player drives with arrow keys, mouse clicks, or mouse
 * hover. While active, all keyboard / mouse input is captured (so movement keys and clicks
 * don't leak through to game logic). The HUD's Look button toggles this mode; the Look panel
 * reads {@link #cursor()} each frame and shows info about whatever is at that tile.
 *
 * <p>Every cursor movement records into the shared {@link TargetHistory}, so moving the look
 * cursor over an enemy and then invoking a wand naturally aims at that enemy.
 */
public class LookMode extends InputAdapter {

    private final OrthographicCamera worldCamera;
    private final ShapeRenderer shapes = new ShapeRenderer();

    private Level level;
    private Mob   player;
    private Point cursor;
    private boolean active;
    /** True once the player has confirmed the inspected square via tap/click/cursor.
     *  The look panel is hidden until this flips so the auto-picked nearest mob is a
     *  preview that can be replaced with a single tap before any info is shown. */
    private boolean hasChosen;
    private TargetHistory history;

    public LookMode(OrthographicCamera worldCamera) {
        this.worldCamera = worldCamera;
    }

    public void setPlayer(Mob p)            { this.player = p; }
    public void setLevel(Level l)           { this.level  = l; }
    public void setHistory(TargetHistory h) { this.history = h; }

    public boolean isActive() { return active; }
    public Point   cursor()   { return cursor; }

    /** True when the look panel should be drawn - look is active AND the player has
     *  made a pick (tap/click/cursor) that chose a square to inspect. After
     *  activation but before any input, only the reticle shows; the panel is hidden
     *  until {@link #hasChosen} flips. */
    public boolean isPanelVisible() { return active && hasChosen; }

    public void toggle() { if (active) deactivate(); else activate(); }

    /**
     * Opens Look on the nearest visible mob (any mob other than the player itself);
     * if no other mob is in sight, the cursor lands on the player's own tile. The
     * starting cell is also recorded into the shared {@link TargetHistory} so a
     * follow-up wand or ranged shot snaps to the same target.
     */
    public void activate() {
        if (player == null || level == null) return;
        active = true;
        // Panel hidden until first user input - auto-pick is a preview, not a commit.
        hasChosen = false;
        // Prefer the nearest *hostile* mob - a friendly kitten or tame pet
        // standing 3 tiles away shouldn't steal focus from a wraith 5 tiles
        // away. Fall back to any visible mob, then to the player's own tile.
        Mob nearest = com.bjsp123.rl2.logic.MobTargeting.nearestHostile(player, level);
        if (nearest == null) nearest = nearestVisibleMob(player, level);
        cursor = (nearest != null) ? nearest.position : player.position;
        if (history != null && cursor != null) history.record(level, cursor);
    }

    /** Activate without a player anchor and without an auto-pick. The reticle
     *  starts unset; the next tap lands the cursor and reveals the panel.
     *  Used by the arena (no PLAYER-behaviour mob exists, so {@link #activate}
     *  bails out of its player-required gate). */
    public void activateBlank() {
        if (level == null) return;
        active = true;
        hasChosen = false;
        cursor = null;
    }

    /** Nearest visible mob to {@code around}, excluding the mob itself. Chebyshev
     *  distance, matching the rest of the engine's proximity measure. Visible-only:
     *  the player can't open Look on a mob they can't see. */
    private static Mob nearestVisibleMob(Mob around, Level level) {
        if (around == null || level == null || level.mobs == null) return null;
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        int ax = around.position.tileX(), ay = around.position.tileY();
        for (Mob m : level.mobs) {
            if (m == around || m.position == null) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            int d = Math.max(Math.abs(mx - ax), Math.abs(my - ay));
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    public void deactivate() {
        active = false;
        hasChosen = false;
        cursor = null;
    }

    /** Yellow box reticle drawn in world space, just like the targeting overlay. If a mob
     *  sits on the cursor and has a non-null targetPosition, a magenta box is also drawn
     *  on that target tile so the player can see where the mob is heading. */
    public void render() {
        if (!active || cursor == null || worldCamera == null) return;
        shapes.setProjectionMatrix(worldCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(Color.YELLOW);
        drawTileBox(cursor);
        Mob t = mobAtCursor();
        if (t != null && t.targetPosition != null
                && (t.targetPosition.tileX() != cursor.tileX()
                 || t.targetPosition.tileY() != cursor.tileY())) {
            shapes.setColor(Color.MAGENTA);
            drawTileBox(t.targetPosition);
        }
        shapes.end();
    }

    private void drawTileBox(Point p) {
        float x = p.tileX() * (float) LevelRenderer.TILE_SIZE;
        float y = p.tileY() * (float) LevelRenderer.TILE_SIZE;
        shapes.rect(x, y, LevelRenderer.TILE_SIZE, LevelRenderer.TILE_SIZE);
        shapes.rect(x + 1, y + 1, LevelRenderer.TILE_SIZE - 2, LevelRenderer.TILE_SIZE - 2);
    }

    public void dispose() { shapes.dispose(); }

    /** The mob standing on the cursor tile, or null. */
    public Mob mobAtCursor() {
        if (!active || cursor == null || level == null) return null;
        for (Mob m : level.mobs) {
            if (m.position.tileX() == cursor.tileX() && m.position.tileY() == cursor.tileY()) {
                return m;
            }
        }
        return null;
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
            case Input.Keys.ESCAPE,
                 Input.Keys.L -> { deactivate(); return true; }
            case Input.Keys.ENTER,
                 Input.Keys.NUMPAD_ENTER,
                 Input.Keys.SPACE -> {
                // SPACE / ENTER commit the current reticle position - panel
                // appears (state A -> B). Direction keys only PREVIEW: they move
                // the reticle without revealing the panel, so the player can
                // line up a square before committing.
                hasChosen = true;
                return true;
            }
            default -> { return true; }
        }
        // Direction-key behaviour depends on whether the panel is up:
        //   State A (no panel yet): move the reticle silently, panel stays hidden.
        //   State B (panel visible): a direction key dismisses look - once the
        //     player has committed to a square the panel is the focus, and a
        //     direction key signals "I'm done examining, return to the world".
        if (hasChosen) {
            deactivate();
            return true;
        }
        moveCursor(dx, dy);
        return true;
    }

    @Override public boolean keyUp(int keycode) { return active; }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!active || worldCamera == null || level == null) return false;
        // Two-stage tap behaviour. State A (panel hidden, just-activated): a tap
        // moves the reticle to the tapped tile and reveals the panel. State B
        // (panel already visible): a tap ends the look operation. Taps that hit
        // the panel itself never reach here - LookRenderer's framed Container
        // absorbs them - so any touchDown in State B is a tap "away from" the
        // panel, which is the user's dismissal gesture.
        if (hasChosen) {
            deactivate();
            return true;
        }
        Vector3 w = worldCamera.unproject(new Vector3(screenX, screenY, 0));
        int tx = (int) Math.floor(w.x / LevelRenderer.TILE_SIZE);
        int ty = (int) Math.floor(w.y / LevelRenderer.TILE_SIZE);
        if (tx < 0 || ty < 0 || tx >= level.width || ty >= level.height) return true;
        cursor = new Point(tx, ty);
        if (history != null) history.record(level, cursor);
        hasChosen = true;
        return true;
    }

    @Override public boolean touchUp(int x, int y, int p, int b)   { return active; }
    @Override public boolean touchDragged(int x, int y, int p)     { return active; }
    @Override public boolean scrolled(float x, float y)            { return active; }

    private void moveCursor(int dx, int dy) {
        if (cursor == null || (dx == 0 && dy == 0)) return;
        int nx = cursor.tileX() + dx, ny = cursor.tileY() + dy;
        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) return;
        cursor = new Point(nx, ny);
        if (history != null) history.record(level, cursor);
    }
}
