package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;

import java.util.List;

/**
 * Text-drawing helpers. Caller is inside a {@code ctx.batch.begin()/end()}
 * block. All maths uses the layout's measured width so text alignment is
 * pixel-perfect across font sizes.
 *
 * <p>{@code y} parameters are the BASELINE position in libGDX font conventions.
 * The {@code centred*} variants flip the maths to the line top so the visible
 * text reads as centred on the supplied centre point.
 */
public final class TextDraw {
    private TextDraw() {}

    /** Draw {@code text} left-aligned at {@code (x, y)} where y is the line
     *  TOP. Useful when laying out from the top of a window down. */
    public static void left(UiCtx ctx, BitmapFont font, Color colour,
                            String text, float x, float yTop) {
        font.setColor(colour);
        // libGDX baseline = capHeight below line-top.
        font.draw(ctx.batch, text, x, yTop);
    }

    /** Draw {@code text} centred horizontally on {@code cx}; {@code yTop} is
     *  the line top. */
    public static void centre(UiCtx ctx, BitmapFont font, Color colour,
                              String text, float cx, float yTop) {
        font.setColor(colour);
        ctx.layout.setText(font, text);
        font.draw(ctx.batch, text, cx - ctx.layout.width * 0.5f, yTop);
    }

    /** Right-aligned: {@code xRight} is the right edge of the text. */
    public static void right(UiCtx ctx, BitmapFont font, Color colour,
                             String text, float xRight, float yTop) {
        font.setColor(colour);
        ctx.layout.setText(font, text);
        font.draw(ctx.batch, text, xRight - ctx.layout.width, yTop);
    }

    /** Word-wrap {@code text} into {@code out} so every emitted line measures
     *  at most {@code maxWidthPx} wide in {@code font}, capped at
     *  {@code maxLines} entries. Soft-breaks at whitespace where possible;
     *  hard-breaks (mid-word) when a single word is wider than the line so
     *  long unbreakable words can't bleed past the right edge. Honours
     *  embedded {@code '\n'}s as forced line breaks. */
    public static void wrap(BitmapFont font, String text,
                            float maxWidthPx, int maxLines,
                            List<String> out) {
        if (text == null || text.isEmpty() || maxLines <= 0) return;
        if (maxWidthPx <= 0f) return;
        GlyphLayout layout = SCRATCH.get();
        int n = text.length();
        int lineStart = 0;
        int wordStart = 0;
        int i = 0;
        while (i < n && out.size() < maxLines) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                out.add(text.substring(lineStart, i));
                i++;
                lineStart = i;
                wordStart = i;
                continue;
            }
            int probeEnd = i + 1;
            layout.setText(font, text, lineStart, probeEnd, font.getColor(), 0,
                    com.badlogic.gdx.utils.Align.left, false, null);
            if (layout.width <= maxWidthPx) {
                if (ch == ' ') wordStart = i + 1;
                i++;
                continue;
            }
            // Adding char i pushes the line past maxWidthPx — emit the line
            // we have so far. Prefer breaking at the last whitespace; if
            // there isn't one inside this line, hard-break before i so a
            // single long word still gets split.
            int breakAt;
            if (wordStart > lineStart) {
                breakAt = wordStart - 1;                // before the trailing space
                out.add(text.substring(lineStart, breakAt));
                lineStart = wordStart;
            } else {
                breakAt = Math.max(lineStart + 1, i);   // at least one char per line
                out.add(text.substring(lineStart, breakAt));
                lineStart = breakAt;
                wordStart = breakAt;
            }
            i = lineStart;
        }
        if (out.size() < maxLines && lineStart < n) {
            out.add(text.substring(lineStart));
        }
    }

    /** Per-thread {@link GlyphLayout} so wrap measurements don't allocate
     *  per call. */
    private static final ThreadLocal<GlyphLayout> SCRATCH =
            ThreadLocal.withInitial(GlyphLayout::new);
}
