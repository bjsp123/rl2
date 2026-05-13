package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Bottom-left back glyph — drawn fixed-position on every V2 screen except
 * the title (which is the root). A 56×56 button containing a left-pointing
 * triangle drawn from primitives. Tap unwinds one level of navigation.
 *
 * <p>The screen is responsible for routing the click — {@link V2Screen}
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
        ShapeRenderer s = ctx.shapes;
        // Tri-line border in red — outer bright red, mid mid-red, inner dark
        // red. Distinguishes the back affordance from grey-bordered chrome.
        Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h, UIVars.HUD_LINE_W,
                UIVars.WARN_HL, UIVars.TEXT_WARN, UIVars.WARN_SHADE);
        s.setColor(pressed ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
        s.rect(rect.x + UIVars.HUD_BORDER, rect.y + UIVars.HUD_BORDER,
               rect.w - 2 * UIVars.HUD_BORDER, rect.h - 2 * UIVars.HUD_BORDER);
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
        if (region == null) return;
        ctx.batch.setColor(pressed ? UIVars.WARN_HL : UIVars.TEXT_WARN);
        float sz = Math.min(rect.w, rect.h) * 0.6f;
        ctx.batch.draw(region,
                rect.cx() - sz * 0.5f, rect.cy() - sz * 0.5f, sz, sz);
        ctx.batch.setColor(1f, 1f, 1f, 1f);
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }

    public void click() { if (onClick != null) onClick.run(); }
}
