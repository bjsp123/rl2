package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Bottom-left back glyph - drawn fixed-position on every V2 screen except
 * the title (which is the root). A {@code UIVars.BACK_SIZE} (40px) button
 * showing the {@code IconSprites.Icon.BACK} glyph. Tap unwinds one level of navigation.
 *
 * <p>The screen is responsible for routing the click - {@link V2Screen}
 * provides {@code onBack()} for ESC and tap-outside, and the BackBtn calls
 * the same hook.
 */
public final class BackBtn {

    public static final float INSET = 12f;

    public final Rect rect = new Rect();
    public boolean pressed;
    private final Runnable onClick;

    public BackBtn(UiCtx ctx, Runnable onClick) {
        this.onClick = onClick;
        float s = UIVars.BACK_SIZE;
        rect.set(INSET, INSET, s, s);
    }

    public void drawShape(UiCtx ctx) {
        ButtonChrome.shape(ctx, rect, pressed, false, true, UIVars.BTN_BG);
    }

    /** Single-call draw: owns the full shape + icon renderer lifecycle. */
    public void draw(UiCtx ctx) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawShape(ctx);
        ctx.shapes.end();
        ctx.batch.begin();
        drawIcon(ctx);
        ctx.batch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Draw the back glyph from the shared UI icon sheet. Caller is inside
     *  a {@link com.badlogic.gdx.graphics.g2d.SpriteBatch} pass. Tinted red
     *  (matching the button's red border) and brighter when pressed. */
    public void drawIcon(UiCtx ctx) {
        var region = com.bjsp123.rl2.world.render.IconSprites
                .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.BACK);
        ButtonChrome.icon(ctx, rect, region, pressed, true);
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }

    public void click() { if (onClick != null) onClick.run(); }
}
