package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Bottom-right back glyph — drawn fixed-position on every V2 screen except
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
        // Default anchor: bottom-right of the virtual viewport. Popups
        // that own a window rect should call {@link #anchorBottomRightOf}
        // each frame so the button hugs the popup's lower-right corner
        // instead.
        float s = Pal.BACK_SIZE;
        rect.set(ctx.worldW() - s - INSET, INSET, s, s);
    }

    /** Re-anchor the button to the bottom-right corner of {@code window},
     *  with the standard {@link #INSET} gap. Called by popups whose window
     *  rect is laid out per-frame so the button moves with it. */
    public void anchorBottomRightOf(Rect window) {
        if (window == null) return;
        float s = Pal.BACK_SIZE;
        rect.set(window.right() - s - INSET, window.y + INSET, s, s);
    }

    public void drawShape(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        // Tri-line border in red — outer bright red, mid mid-red, inner dark
        // red. Distinguishes the back affordance from grey-bordered chrome.
        Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h, Pal.HUD_LINE_W,
                UiColors.WARN_HL, UiColors.TEXT_WARN, UiColors.WARN_SHADE);
        s.setColor(pressed ? UiColors.BTN_PRESSED_BG : UiColors.BTN_BG);
        s.rect(rect.x + Pal.HUD_BORDER, rect.y + Pal.HUD_BORDER,
               rect.w - 2 * Pal.HUD_BORDER, rect.h - 2 * Pal.HUD_BORDER);
    }

    /** Draw the back glyph from the shared UI icon sheet. Caller is inside
     *  a {@link com.badlogic.gdx.graphics.g2d.SpriteBatch} pass. Tinted red
     *  (matching the button's red border) and brighter when pressed. */
    public void drawIcon(UiCtx ctx) {
        var region = com.bjsp123.rl2.world.render.IconSprites
                .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.BACK);
        if (region == null) return;
        ctx.batch.setColor(pressed ? UiColors.WARN_HL : UiColors.TEXT_WARN);
        float sz = Math.min(rect.w, rect.h) * 0.6f;
        ctx.batch.draw(region,
                rect.cx() - sz * 0.5f, rect.cy() - sz * 0.5f, sz, sz);
        ctx.batch.setColor(1f, 1f, 1f, 1f);
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }

    public void click() { if (onClick != null) onClick.run(); }
}
