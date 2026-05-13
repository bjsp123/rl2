package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;

import java.util.ArrayList;
import java.util.Collections;
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

    public static final class TextBlock {
        public final List<String> lines;
        public final boolean truncated;
        public final float lineHeight;

        private TextBlock(List<String> lines, boolean truncated, float lineHeight) {
            this.lines = Collections.unmodifiableList(lines);
            this.truncated = truncated;
            this.lineHeight = lineHeight;
        }

        public int lineCount() { return lines.size(); }

        public float height() { return lines.size() * lineHeight; }

        public boolean isEmpty() { return lines.isEmpty(); }
    }

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

    public static void leftFit(UiCtx ctx, BitmapFont font, Color colour,
                               String text, float x, float yTop,
                               float maxWidthPx) {
        left(ctx, font, colour, ellipsize(font, text, maxWidthPx), x, yTop);
    }

    public static void centreFit(UiCtx ctx, BitmapFont font, Color colour,
                                 String text, float cx, float yTop,
                                 float maxWidthPx) {
        centre(ctx, font, colour, ellipsize(font, text, maxWidthPx), cx, yTop);
    }

    public static void rightFit(UiCtx ctx, BitmapFont font, Color colour,
                                String text, float xRight, float yTop,
                                float maxWidthPx) {
        right(ctx, font, colour, ellipsize(font, text, maxWidthPx), xRight, yTop);
    }

    public static void wrapped(UiCtx ctx, BitmapFont font, Color colour,
                               TextBlock block, float x, float yTop) {
        float y = yTop;
        for (String line : block.lines) {
            left(ctx, font, colour, line, x, y);
            y -= block.lineHeight;
        }
    }

    public static void wrappedCentre(UiCtx ctx, BitmapFont font, Color colour,
                                     TextBlock block, float cx, float yTop) {
        float y = yTop;
        for (String line : block.lines) {
            centre(ctx, font, colour, line, cx, y);
            y -= block.lineHeight;
        }
    }

    public static String ellipsize(BitmapFont font, String text, float maxWidthPx) {
        if (text == null) return "";
        if (maxWidthPx <= 0f) return "";
        GlyphLayout layout = SCRATCH.get();
        layout.setText(font, text);
        if (layout.width <= maxWidthPx) return text;
        String suffix = "...";
        layout.setText(font, suffix);
        if (layout.width > maxWidthPx) return "";
        int hi = text.length();
        while (hi > 0) {
            String candidate = text.substring(0, hi).trim() + suffix;
            layout.setText(font, candidate);
            if (layout.width <= maxWidthPx) return candidate;
            hi--;
        }
        return suffix;
    }

    public static TextBlock block(BitmapFont font, String text,
                                  float maxWidthPx, int maxLines,
                                  float lineHeight) {
        ArrayList<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxLines <= 0 || maxWidthPx <= 0f) {
            return new TextBlock(lines, false, lineHeight);
        }
        wrap(font, text, maxWidthPx, maxLines, lines);
        boolean truncated = consumedLessThanAll(font, text, maxWidthPx, lines);
        if (truncated && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, ellipsize(font, lines.get(last) + "...", maxWidthPx));
        }
        return new TextBlock(lines, truncated, lineHeight);
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
            // Adding char i pushes the line past maxWidthPx - emit the line
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

    private static boolean consumedLessThanAll(BitmapFont font, String text,
                                               float maxWidthPx,
                                               List<String> lines) {
        if (text == null || text.isEmpty()) return false;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.append(' ');
            joined.append(lines.get(i));
        }
        return joined.length() < text.trim().length();
    }

    /** Per-thread {@link GlyphLayout} so wrap measurements don't allocate
     *  per call. */
    private static final ThreadLocal<GlyphLayout> SCRATCH =
            ThreadLocal.withInitial(GlyphLayout::new);
}
