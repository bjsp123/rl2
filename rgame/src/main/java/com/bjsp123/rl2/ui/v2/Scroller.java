package com.bjsp123.rl2.ui.v2;

/**
 * Tiny vertical scroll-state holder used by V2 list popups that may have
 * more content than fits in the visible area. Tracks {@code scrollY},
 * clamps it against a caller-supplied {@code maxScrollY}, and converts
 * touch / wheel input deltas into scroll movement.
 *
 * <p>Sign convention - {@code scrollY > 0} means content has been pushed
 * UP (entries from the bottom of the master list become visible). Finger
 * dragging up (worldY increasing) increases {@code scrollY}; wheel-down
 * (positive {@code amountY}) does the same.
 *
 * <p>Drag detection - the scroller flips into "dragging" mode only after
 * the cumulative movement on a single touch exceeds
 * {@link #DRAG_THRESHOLD} pixels. Until then, {@link #onTouchDragged}
 * returns {@code false} and the caller's tap-detection state is preserved
 * - once {@code true} comes back, the caller should clear any pending
 * tap captures so a scroll gesture doesn't fire a row click on release.
 */
public final class Scroller {

    /** Pixels of finger movement on a single touch before {@link #onTouchDragged}
     *  flips into dragging mode and starts consuming events. */
    private static final float DRAG_THRESHOLD = 6f;

    /** Shared wheel-tick step (virtual px) so every list in the app scrolls
     *  at the same speed in response to one mouse-wheel notch. Callers use
     *  the no-arg {@link #onScrolled(float)} to inherit it; the explicit
     *  {@link #onScrolled(float, float)} stays for the rare list that wants
     *  a tailored step (e.g. one-line-per-tick logs). */
    public static final float DEFAULT_WHEEL_STEP_PX = 36f;

    private float scrollY;
    private float maxScrollY;
    private boolean dragging;
    private float dragLastY;

    public float scrollY()    { return scrollY; }
    /** The clamp cap last supplied via {@link #setMaxScroll} - exposed so
     *  callers can test "is there more content below/above" (scroll-arrow
     *  affordances) without recomputing content metrics. */
    public float maxScroll()  { return maxScrollY; }
    public boolean isDragging() { return dragging; }

    /** Caller computes total content height and visible window height each
     *  frame; the difference is how far the user can scroll before hitting
     *  the bottom. Clamps {@link #scrollY} into {@code [0, max]}. */
    public void setMaxScroll(float max) {
        maxScrollY = Math.max(0f, max);
        if (scrollY < 0f)         scrollY = 0f;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    /** Snap to top - used when switching tabs / categories so the new
     *  list's first entry is visible. */
    public void resetTop() {
        scrollY  = 0f;
        dragging = false;
    }

    public void onTouchDown(float vy) {
        dragLastY = vy;
        dragging  = false;
    }

    /** Update scroll based on a drag's current y. Returns {@code true} if
     *  the gesture has been classified as a drag (caller should suppress
     *  tap actions). */
    public boolean onTouchDragged(float vy) {
        float dy = vy - dragLastY;
        dragLastY = vy;
        if (!dragging && Math.abs(dy) > DRAG_THRESHOLD) {
            dragging = true;
        }
        if (dragging) {
            scrollY += dy;
            if (scrollY < 0f)         scrollY = 0f;
            if (scrollY > maxScrollY) scrollY = maxScrollY;
        }
        return dragging;
    }

    public void onTouchUp() {
        dragging = false;
    }

    /** Mouse-wheel scroll. {@code pixelsPerTick} is the per-tick step in
     *  virtual coordinates - typically one row's height. */
    public void onScrolled(float amountY, float pixelsPerTick) {
        scrollY += amountY * pixelsPerTick;
        if (scrollY < 0f)         scrollY = 0f;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    /** Mouse-wheel scroll using {@link #DEFAULT_WHEEL_STEP_PX}. Every list
     *  in the app should call this overload unless it has a real reason to
     *  use a custom step - keeps wheel feel uniform across screens. */
    public void onScrolled(float amountY) {
        onScrolled(amountY, DEFAULT_WHEEL_STEP_PX);
    }
}
