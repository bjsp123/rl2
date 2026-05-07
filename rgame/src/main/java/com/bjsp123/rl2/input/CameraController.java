package com.bjsp123.rl2.input;

import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.bjsp123.rl2.world.anim.Animator;
import com.bjsp123.rl2.world.anim.MobAnimState;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.LevelRenderer;

public class CameraController extends InputAdapter {

    // Smaller MIN_ZOOM = closer view (camera "zooms in" further). At 0.08 a 16-px tile fills
    // ~200 px of screen on a 1080p window — close enough to read individual pixels of the
    // tile art without the game becoming a single-tile peep show.
    private static final float MIN_ZOOM = 0.08f;
    private static final float MAX_ZOOM = 5.0f;

    private final OrthographicCamera camera;
    /** Source of per-mob step-interpolation state. Set via {@link #setAnimator}. */
    private Animator animator;

    private float lastX, lastY;
    private boolean touch0Down, touch1Down;
    private final Vector2 touch0 = new Vector2(), touch1 = new Vector2();
    private float lastPinchDist = -1;
    private int lastPlayerTileX = Integer.MIN_VALUE, lastPlayerTileY = Integer.MIN_VALUE;
    /** True iff {@link #followPlayer} ran with an active step animation last frame. Used
     *  to force one extra camera update on the frame where the step finishes — without it,
     *  the early-return below leaves the camera at the last in-flight offset (~2.7 px off
     *  the destination tile centre) and the next step appears to jerk back to align. */
    private boolean lastWasStepping;

    public CameraController(OrthographicCamera camera) {
        this.camera = camera;
    }

    public void setAnimator(Animator animator) {
        this.animator = animator;
    }

    /** Optional gate — when this supplier returns true, every touch / scroll
     *  event is ignored (no pan, no zoom, no state mutation). Used by
     *  {@link com.bjsp123.rl2.screen.PlayScreen} to suppress map drag while a
     *  modal popup is open: per the UI rules, the topmost window owns input
     *  and the world behind should not move under the user's finger. */
    private java.util.function.BooleanSupplier inputBlocker;

    public void setInputBlocker(java.util.function.BooleanSupplier blocker) {
        this.inputBlocker = blocker;
    }

    private boolean blocked() {
        return inputBlocker != null && inputBlocker.getAsBoolean();
    }

    // ── Desktop scroll-wheel zoom ────────────────────────────────────────────

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (blocked()) return false;
        camera.zoom = MathUtils.clamp(camera.zoom + amountY * 0.15f, MIN_ZOOM, MAX_ZOOM);
        return true;
    }

    // ── Touch / mouse ────────────────────────────────────────────────────────

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
        // While a modal popup is up, swallow the touch state so a follow-up
        // touchDragged can't pan the world. We DO clear our own pressed flags
        // first so a stale "still holding" state doesn't persist after the
        // popup closes.
        if (blocked()) {
            touch0Down = false; touch1Down = false; lastPinchDist = -1;
            return false;
        }
        if (pointer == 0) { touch0.set(x, y); touch0Down = true; lastX = x; lastY = y; }
        if (pointer == 1) { touch1.set(x, y); touch1Down = true; lastPinchDist = -1; }
        return false; // don't consume — GameInput also needs touchDown
    }

    @Override
    public boolean touchUp(int x, int y, int pointer, int button) {
        if (pointer == 0) { touch0Down = false; }
        if (pointer == 1) { touch1Down = false; lastPinchDist = -1; }
        return false;
    }

    @Override
    public boolean touchDragged(int x, int y, int pointer) {
        if (blocked()) return false;
        if (pointer == 0) touch0.set(x, y);
        if (pointer == 1) touch1.set(x, y);

        if (touch0Down && touch1Down) {
            // Two-finger pinch zoom
            float dist = touch0.dst(touch1);
            if (lastPinchDist > 0) {
                camera.zoom = MathUtils.clamp(camera.zoom * lastPinchDist / dist, MIN_ZOOM, MAX_ZOOM);
            }
            lastPinchDist = dist;
        } else if (pointer == 0 && touch0Down && !touch1Down) {
            // Single-finger / mouse pan — use unproject so pan respects current zoom
            Vector3 prev = camera.unproject(new Vector3(lastX, lastY, 0));
            Vector3 curr = camera.unproject(new Vector3(x, y, 0));
            camera.translate(prev.x - curr.x, prev.y - curr.y);
        }

        if (pointer == 0) { lastX = x; lastY = y; }
        return false;
    }

    // ── Auto-follow ──────────────────────────────────────────────────────────

    /** Re-center the camera on the player. Tracks the player's interpolated pixel position
     *  during a step animation so the map scrolls smoothly underneath them rather than
     *  jumping a whole tile at the moment {@code mob.position} is updated. When no step is
     *  in progress the early-return on "same tile as last frame" still applies, so a
     *  manually-panned camera doesn't snap back unless the player actually moves. */
    public void followPlayer(Mob player) {
        if (player == null || animator == null) return;
        int tx = player.position.tileX(), ty = player.position.tileY();
        MobAnimState as = animator.stateOf(player);
        boolean stepping = as.stepTotal > 0;
        // The frame after a step ends, the early-return below would lock the camera at the
        // last animation frame's offset (a few px short of tile centre). Override it so we
        // get one final "snap to tile centre" update — fixed by also recomputing on the
        // transition stepping=true → stepping=false.
        boolean justFinishedStepping = lastWasStepping && !stepping;
        lastWasStepping = stepping;
        if (!stepping && !justFinishedStepping
                && tx == lastPlayerTileX && ty == lastPlayerTileY) return;
        lastPlayerTileX = tx;
        lastPlayerTileY = ty;

        float ox = 0f, oy = 0f;
        if (stepping) {
            float t = Math.min(1f, as.stepFrame / (float) as.stepTotal);
            ox = as.stepFromDx * (1f - t) * LevelRenderer.TILE_SIZE;
            oy = as.stepFromDy * (1f - t) * LevelRenderer.TILE_SIZE;
        }
        camera.position.set(
                tx * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE * 0.5f + ox,
                ty * LevelRenderer.TILE_SIZE + LevelRenderer.TILE_SIZE * 0.5f + oy,
                0);
    }

    public OrthographicCamera getCamera() { return camera; }
}
