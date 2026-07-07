package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Shared visible-band helper for V2 screens that render manually-scrolled
 * lists. It centralizes max-scroll calculation, row positioning, clipping,
 * and the small scrollbar primitive.
 */
public final class ScrollBand {

    public final Rect rect = new Rect();
    public final Scroller scroller = new Scroller();

    /** Scratch rectangles for {@link #clip} - reused every frame so the
     *  render loop doesn't allocate. */
    private final com.badlogic.gdx.math.Rectangle clipWorld =
            new com.badlogic.gdx.math.Rectangle();
    private final com.badlogic.gdx.math.Rectangle clipScissor =
            new com.badlogic.gdx.math.Rectangle();

    public void set(float x, float yBottom, float w, float h) {
        rect.set(x, yBottom, w, h);
    }

    public float top() { return rect.top(); }
    public float bottom() { return rect.y; }
    public float height() { return rect.h; }

    public void update(float contentH) {
        scroller.setMaxScroll(Math.max(0f, contentH - rect.h));
    }

    public float rowTop(int index, float rowH) {
        return rect.top() - index * rowH + scroller.scrollY();
    }

    public boolean rowVisible(float yTop, float rowH) {
        return yTop - rowH <= rect.top() && yTop >= rect.y;
    }

    public boolean touchDown(float vx, float vy) {
        if (!rect.contains(vx, vy)) return false;
        scroller.onTouchDown(vy);
        return true;
    }

    public boolean touchDragged(float vy) {
        return scroller.onTouchDragged(vy);
    }

    public void scrolled(float amountY, float pixelsPerTick) {
        scroller.onScrolled(amountY, pixelsPerTick);
    }

    /** Mouse-wheel scroll using {@link Scroller#DEFAULT_WHEEL_STEP_PX}.
     *  Every list in the app should call this unless it has a real reason
     *  to use a custom step (e.g. a log that wants one-line-per-tick). */
    public void scrolled(float amountY) {
        scroller.onScrolled(amountY);
    }

    /** Run {@code body} with GL scissor clipped to the band. Works inside a
     *  batch (text/icon) pass OR a ShapeRenderer Filled pass - whichever
     *  renderer is mid-draw is flushed before the scissor is pushed and
     *  again before it pops, so prior draws aren't clipped and clipped
     *  draws aren't deferred past the pop. This is the single clip helper
     *  for every scrolling V2 panel. */
    public void clip(UiCtx ctx, Runnable body) {
        flushActive(ctx);
        clipWorld.set(rect.x, rect.y, rect.w, rect.h);
        com.badlogic.gdx.utils.viewport.Viewport vp = ctx.viewport;
        com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.calculateScissors(
                vp.getCamera(), vp.getScreenX(), vp.getScreenY(),
                vp.getScreenWidth(), vp.getScreenHeight(),
                ctx.batch.getTransformMatrix(), clipWorld, clipScissor);
        if (com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.pushScissors(clipScissor)) {
            try {
                body.run();
                flushActive(ctx);
            } finally {
                com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
            }
        }
    }

    private static void flushActive(UiCtx ctx) {
        if (ctx.batch.isDrawing())  ctx.batch.flush();
        if (ctx.shapes.isDrawing()) ctx.shapes.flush();
    }

    /** The shared V2 scrollbar - a thin track + accent thumb down the right
     *  edge of the band - reflecting the current scroll position. No-op when
     *  nothing is scrollable. Call inside a {@link ShapeRenderer} Filled batch.
     *  This is the single scrollbar affordance for every scrollable panel. */
    public void drawScrollbar(ShapeRenderer s, float contentH) {
        if (contentH <= rect.h || contentH <= 0f) return;
        float barW = 4f;
        float x = rect.right() - barW - 1f;
        s.setColor(UIVars.BORDER_INNER);          // track
        s.rect(x, rect.y, barW, rect.h);
        float barH = Math.max(14f, rect.h * (rect.h / contentH));
        float scrollFrac = scroller.scrollY() / Math.max(1f, contentH - rect.h);
        float barY = rect.y + (rect.h - barH) * (1f - scrollFrac);
        s.setColor(UIVars.ACCENT);                // thumb
        s.rect(x, barY, barW, barH);
    }
}
