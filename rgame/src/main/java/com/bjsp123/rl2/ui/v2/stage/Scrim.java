package com.bjsp123.rl2.ui.v2.stage;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.ui.v2.UIVars;
import com.bjsp123.rl2.ui.v2.UiCtx;

/**
 * Reusable full-screen modal-dim helper. Not an Actor - popups call
 * {@link #draw} as the first thing inside their {@code renderSelf} so
 * everything below them in the Stage's draw walk is darkened before
 * the popup paints its chrome.
 *
 * <p>Why static rather than an Actor: keeping each popup self-contained
 * means a single popup is one Stage child - easier to reason about,
 * easier to add / remove. The dim is just a one-liner inside the
 * popup's existing shape pass.
 */
public final class Scrim {

    private Scrim() {}

    /** Paint a full-viewport semi-transparent black quad at
     *  {@link UIVars#DIM_ALPHA}. Caller is OUTSIDE both shape and batch
     *  passes; this method opens / closes its own shape pass with GL
     *  blending enabled so the alpha composites correctly over whatever
     *  is already in the framebuffer. */
    public static void draw(UiCtx ctx) {
        draw(ctx, UIVars.DIM_ALPHA);
    }

    /** Variant with explicit alpha - used by sub-popups (e.g. inventory
     *  item-detail) that want a heavier dim than the default. */
    public static void draw(UiCtx ctx, float alpha) {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, alpha);
        s.rect(0f, 0f, ctx.worldW(), ctx.worldH());
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
}
