package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * V2 window chrome — faint black drop shadow, three-line border (outer
 * light blue-grey, mid blue-grey, inner dark blue-grey), and a warm dark
 * grey interior fill. Drawn entirely from filled rectangles via
 * ShapeRenderer.
 *
 * <p>Pure helper — call {@link #drawShape} between a
 * {@link ShapeRenderer#begin(ShapeRenderer.ShapeType)} /
 * {@link ShapeRenderer#end()} pair. {@link V2Screen} enables GL blending
 * around its shapes pass so the shadow's alpha and the panel's
 * {@link Pal#PANEL_FILL_ALPHA} fill composite correctly.
 *
 * <p>{@link #drawShapeOpaque} is for cells whose interior carries an image
 * (inventory slots, encyclopaedia detail icon backdrop) — those want
 * fully-opaque fill so the sprite sits on a solid surface.
 */
public final class Window {
    private Window() {}

    public static void drawShape(UiCtx ctx, float x, float y, float w, float h) {
        drawShape(ctx, x, y, w, h, Pal.PANEL_FILL_ALPHA);
    }

    public static void drawShapeOpaque(UiCtx ctx, float x, float y, float w, float h) {
        drawShape(ctx, x, y, w, h, 1f);
    }

    public static void drawShape(UiCtx ctx, float x, float y, float w, float h,
                                 float fillAlpha) {
        drawShape(ctx, x, y, w, h, UiColors.WIN_BG, fillAlpha);
    }

    /** Variant that accepts an explicit fill colour. */
    public static void drawShape(UiCtx ctx, float x, float y, float w, float h,
                                 Color fill, float fillAlpha) {
        ShapeRenderer s = ctx.shapes;
        // Pass 1 — faint black drop shadow, offset down-right so the panel
        // appears to float above whatever sits behind it.
        s.setColor(UiColors.SHADOW);
        s.rect(x + Pal.SHADOW_OFFSET, y - Pal.SHADOW_OFFSET, w, h);
        // Pass 2 — three-line border (outer / mid / inner). The interior is
        // left untouched here; pass 3 paints it.
        Edges.drawTriLine(s, x, y, w, h, Pal.WIN_LINE_W);
        // Pass 3 — warm dark-grey interior fill, inset by the full border
        // thickness so it doesn't overpaint the inner-line ring.
        s.setColor(fill.r, fill.g, fill.b, fillAlpha);
        s.rect(x + Pal.WIN_BORDER, y + Pal.WIN_BORDER,
               w - 2 * Pal.WIN_BORDER, h - 2 * Pal.WIN_BORDER);
    }
}
