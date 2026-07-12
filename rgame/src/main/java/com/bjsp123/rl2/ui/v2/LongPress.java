package com.bjsp123.rl2.ui.v2;

/**
 * Dumb, reusable long-press tracker. The owner feeds it pointer-0 touch
 * events (in whatever coordinate space the owner prefers - the recorded
 * point is echoed back unchanged from {@link #x()} / {@link #y()}), polls
 * {@link #update()} once per render frame, and gets {@code true} exactly
 * once when the press has been held past {@link #HOLD_MS} without moving
 * beyond {@link #DRAG_CANCEL_PX}.
 *
 * <p>Tap-suppression contract: when the owner acts on a fire it calls
 * {@link #markHandled()}; the owner's touchUp then checks
 * {@link #consumeFired()} FIRST and swallows the event when it returns
 * {@code true}, so the normal tap action never runs after a long-press
 * has already opened something.
 *
 * <p>Times are wall-clock real time ({@code System.currentTimeMillis()}) -
 * a long-press is a physical gesture, not a game-time one.
 */
public final class LongPress {

    /** Real-time hold duration before the press fires. */
    public static final long HOLD_MS = 450;
    /** Movement beyond this distance (in the owner's coordinate space)
     *  cancels the press - matches the tap-vs-drag threshold used by
     *  {@code GameInput} so a camera pan never fires help. */
    public static final float DRAG_CANCEL_PX = 12f;

    private boolean tracking;
    private float startX, startY;
    private long startMs;
    private boolean suppressNextUp;

    /** Pointer-0 press began at ({@code x},{@code y}). */
    public void onTouchDown(float x, float y) {
        tracking = true;
        startX = x;
        startY = y;
        startMs = System.currentTimeMillis();
    }

    /** Pointer-0 drag - cancels the press once it strays past the drag
     *  threshold (the gesture is a pan / scroll, not a hold). */
    public void onTouchDragged(float x, float y) {
        if (!tracking) return;
        float dx = x - startX, dy = y - startY;
        if (dx * dx + dy * dy > DRAG_CANCEL_PX * DRAG_CANCEL_PX) tracking = false;
    }

    /** Stop tracking without firing (release, second pointer, focus loss). */
    public void cancel() {
        tracking = false;
    }

    /** Per-render-frame poll. Returns {@code true} exactly once when the
     *  press has been held past {@link #HOLD_MS}; the owner reads the press
     *  point via {@link #x()} / {@link #y()} and, if it acts on the fire,
     *  calls {@link #markHandled()}. */
    public boolean update() {
        if (!tracking) return false;
        if (System.currentTimeMillis() - startMs < HOLD_MS) return false;
        tracking = false;
        return true;
    }

    /** Recorded press point (same coordinate space the owner fed in). */
    public float x() { return startX; }
    public float y() { return startY; }

    /** The owner acted on the fire - arm suppression of the next touchUp. */
    public void markHandled() {
        suppressNextUp = true;
    }

    /** Called at the TOP of the owner's touchUp: {@code true} once when a
     *  handled fire is pending, in which case the owner must swallow the
     *  event (and clear any pressed-button visuals) instead of running the
     *  normal tap action. */
    public boolean consumeFired() {
        boolean f = suppressNextUp;
        suppressNextUp = false;
        return f;
    }
}
