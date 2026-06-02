package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * V2 window chrome - faint black drop shadow, three-line border (outer
 * light blue-grey, mid blue-grey, inner dark blue-grey), and a warm dark
 * grey interior fill. Drawn entirely from filled rectangles via
 * ShapeRenderer.
 *
 * <p>Pure helper - call {@link #drawShape} between a
 * {@link ShapeRenderer#begin(ShapeRenderer.ShapeType)} /
 * {@link ShapeRenderer#end()} pair. {@link V2Screen} enables GL blending
 * around its shapes pass so the shadow's alpha and the panel's
 * {@link UIVars#PANEL_FILL_ALPHA} fill composite correctly.
 *
 * <p>{@link #drawShapeOpaque} is for cells whose interior carries an image
 * (inventory slots, encyclopaedia detail icon backdrop) - those want
 * fully-opaque fill so the sprite sits on a solid surface.
 */
public final class Window {
    private Window() {}

    public static void drawShape(UiCtx ctx, float x, float y, float w, float h) {
        drawShape(ctx, x, y, w, h, UIVars.PANEL_FILL_ALPHA);
    }

    public static void drawShapeOpaque(UiCtx ctx, float x, float y, float w, float h) {
        drawShape(ctx, x, y, w, h, 1f);
    }

    /** Variant for *info-only* panels (V2Look, V2BuffInfo, tip popup, etc.).
     *  Drops the tri-line panel chrome for a single thin inked border around
     *  a {@link UIVars#INFO_WIN_BG parchment} fill so the panel reads as a
     *  document/page rather than another button-style frame. Shadow is
     *  retained so the page still appears to float above the world.
     *
     *  <p>Caller must be inside a {@code ShapeRenderer.Filled} pass with GL
     *  blending enabled - same contract as {@link #drawShape}. */
    public static void drawInfoShape(UiCtx ctx, float x, float y, float w, float h) {
        ShapeRenderer s = ctx.shapes;
        // Drop shadow - kept so the page reads as resting above the world.
        s.setColor(UIVars.SHADOW);
        s.rect(x + UIVars.SHADOW_OFFSET, y - UIVars.SHADOW_OFFSET, w, h);
        // Single 1-px inked border drawn as four edge rects (we're inside a
        // Filled pass; using ShapeType.Line would require a begin/end swap).
        Color border = UIVars.INFO_RULE;
        s.setColor(border.r, border.g, border.b, 1f);
        s.rect(x,         y,         w, 1f);
        s.rect(x,         y + h - 1, w, 1f);
        s.rect(x,         y,         1f, h);
        s.rect(x + w - 1, y,         1f, h);
        // Parchment fill, inset by the border so it doesn't overpaint the
        // inked edge.
        Color fill = UIVars.INFO_WIN_BG;
        s.setColor(fill.r, fill.g, fill.b, UIVars.PANEL_FILL_ALPHA);
        s.rect(x + 1, y + 1, w - 2, h - 2);
    }

    /** Hairline ruled separator drawn as a 1-px horizontal line at
     *  {@code (x, y)} of width {@code w}. Same {@link UIVars#INFO_RULE}
     *  ink the info-window border uses, so the rule reads as part of the
     *  page. Caller must be inside an active Filled ShapeRenderer pass. */
    public static void drawInfoSeparator(UiCtx ctx, float x, float y, float w) {
        ShapeRenderer s = ctx.shapes;
        Color rule = UIVars.INFO_RULE;
        s.setColor(rule.r, rule.g, rule.b, 0.7f);
        s.rect(x, y, w, 1f);
    }

    public static void drawShape(UiCtx ctx, float x, float y, float w, float h,
                                 float fillAlpha) {
        drawShape(ctx, x, y, w, h, UIVars.WIN_BG, fillAlpha);
    }

    /** Variant that accepts an explicit fill colour. */
    public static void drawShape(UiCtx ctx, float x, float y, float w, float h,
                                 Color fill, float fillAlpha) {
        ShapeRenderer s = ctx.shapes;
        // Pass 1 - faint black drop shadow, offset down-right so the panel
        // appears to float above whatever sits behind it.
        s.setColor(UIVars.SHADOW);
        s.rect(x + UIVars.SHADOW_OFFSET, y - UIVars.SHADOW_OFFSET, w, h);
        // Pass 2 - three-line border (outer / mid / inner). The interior is
        // left untouched here; pass 3 paints it.
        Edges.drawTriLine(s, x, y, w, h, UIVars.WIN_LINE_W);
        // Pass 3 - warm dark-grey interior fill, inset by the full border
        // thickness so it doesn't overpaint the inner-line ring.
        s.setColor(fill.r, fill.g, fill.b, fillAlpha);
        s.rect(x + UIVars.WIN_BORDER, y + UIVars.WIN_BORDER,
               w - 2 * UIVars.WIN_BORDER, h - 2 * UIVars.WIN_BORDER);
    }
}
