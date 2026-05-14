package com.bjsp123.rl2.ui.v2;

/** Utility for running SpriteBatch drawing inside a viewport-aware scissor. */
public final class ClipRect {
    private ClipRect() {}

    public static void run(UiCtx ctx, Rect rect, Runnable body) {
        ctx.batch.flush();
        com.badlogic.gdx.math.Rectangle worldRect =
                new com.badlogic.gdx.math.Rectangle(rect.x, rect.y, rect.w, rect.h);
        com.badlogic.gdx.math.Rectangle scissor =
                new com.badlogic.gdx.math.Rectangle();
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
}
