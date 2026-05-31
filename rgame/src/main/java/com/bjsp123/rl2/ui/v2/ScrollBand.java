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

    public void clip(UiCtx ctx, Runnable body) {
        ctx.batch.flush();
        com.badlogic.gdx.math.Rectangle worldRect =
                new com.badlogic.gdx.math.Rectangle(rect.x, rect.y, rect.w, rect.h);
        com.badlogic.gdx.math.Rectangle scissor = new com.badlogic.gdx.math.Rectangle();
        com.badlogic.gdx.utils.viewport.Viewport vp = ctx.viewport;
        com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.calculateScissors(
                vp.getCamera(), vp.getScreenX(), vp.getScreenY(),
                vp.getScreenWidth(), vp.getScreenHeight(),
                ctx.batch.getTransformMatrix(), worldRect, scissor);
        if (com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.pushScissors(scissor)) {
            try {
                body.run();
                ctx.batch.flush();
            } finally {
                com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
            }
        }
    }

    public void drawScrollbar(ShapeRenderer s, float contentH) {
        if (contentH <= rect.h || contentH <= 0f) return;
        float barW = 3f;
        float ratio = rect.h / contentH;
        float barH = Math.max(12f, rect.h * ratio);
        float scrollFrac = scroller.scrollY() / Math.max(1f, contentH - rect.h);
        float barY = rect.y + (rect.h - barH) * scrollFrac;
        s.setColor(UIVars.BORDER_MID);
        s.rect(rect.right() - barW, barY, barW, barH);
    }
}
