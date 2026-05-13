package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Three-line concentric border drawing — every chromed V2 element wears
 * the same 3-line frame: a {@code lineW}-thick outer line, a {@code lineW}-
 * thick middle line, and a {@code lineW}-thick inner line, painted from
 * lightest (outer) through mid to darkest (inner). Total border width is
 * {@code 3 × lineW}.
 *
 * <p>Implementation paints three nested filled rectangles. The inner ones
 * overpaint the centres of the outer ones, leaving each colour visible
 * only as a 1-line ring. The interior of the innermost ring is left
 * untouched — the caller fills it with their panel / button / slot
 * colour separately.
 *
 * <p>Caller is inside a {@link ShapeRenderer.ShapeType#Filled} block.
 */
public final class Edges {
    private Edges() {}

    /** Convenience overload that pulls the three border colours from
     *  {@link UIVars}. */
    public static void drawTriLine(ShapeRenderer s,
                                   float x, float y, float w, float h,
                                   float lineW) {
        drawTriLine(s, x, y, w, h, lineW,
                UIVars.BORDER_OUTER, UIVars.BORDER_MID, UIVars.BORDER_INNER);
    }

    /** Three nested filled rects forming a tri-line border. */
    public static void drawTriLine(ShapeRenderer s,
                                   float x, float y, float w, float h, float lineW,
                                   Color outer, Color mid, Color inner) {
        if (w <= 0 || h <= 0) return;
        s.setColor(outer);
        s.rect(x, y, w, h);
        if (w > 2 * lineW && h > 2 * lineW) {
            s.setColor(mid);
            s.rect(x + lineW, y + lineW, w - 2 * lineW, h - 2 * lineW);
        }
        if (w > 4 * lineW && h > 4 * lineW) {
            s.setColor(inner);
            s.rect(x + 2 * lineW, y + 2 * lineW, w - 4 * lineW, h - 4 * lineW);
        }
    }
}
