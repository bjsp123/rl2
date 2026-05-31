package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Horizontal snap-to-tick slider. The {@link #ticks} array is the closed
 * set of values the slider can land on (mirrors a {@code *_CHOICES} array
 * in {@link com.bjsp123.rl2.ui.skin.Settings}). The thumb snaps to the
 * nearest tick on every drag-update and tap-to-set; the host's setter
 * therefore always receives one of the curated values.
 *
 * <p>Two-pass render matches {@link Btn} / {@link Toggle}: shape pass
 * paints the track + ticks + thumb; text pass paints the formatted value
 * label to the right of the track.
 *
 * <p>Hit testing is two-tier. {@link #hitTrack} covers the entire bar; the
 * host treats a hit on the track as "claim the drag", calls
 * {@link #updateFromPointer(float)} on touchDown for tap-to-set, then
 * routes touchDragged through the same method.
 */
public final class Slider {

    public final Rect rect = new Rect();
    public final float[] ticks;
    public int currentIndex;
    public final Consumer<Integer> onChange;
    public final Function<Float, String> labelFmt;
    /** True while the host is actively dragging this slider. Drives the
     *  highlighted-thumb border. Cleared on touchUp. */
    public boolean dragging;

    /** Track inset (per side, virtual px) so the leftmost / rightmost ticks
     *  don't sit flush against the row boundary. */
    private static final float TRACK_INSET = 8f;
    /** Width of the value-label gutter on the right of the track. The track
     *  ends {@code LABEL_GUTTER} px before {@code rect.right()}. */
    private static final float LABEL_GUTTER = 56f;

    public Slider(float x, float y, float w, float h,
                  float[] ticks, int initialIndex,
                  Consumer<Integer> onChange,
                  Function<Float, String> labelFmt) {
        this.rect.set(x, y, w, h);
        this.ticks = ticks;
        this.currentIndex = clampIndex(initialIndex);
        this.onChange = onChange;
        this.labelFmt = labelFmt;
    }

    public boolean hitTrack(float px, float py) {
        return rect.contains(px, py)
                && px <= rect.x + rect.w - LABEL_GUTTER;
    }

    /** Update the slider from a virtual-coord pointer x. Snaps to the
     *  nearest tick and fires {@link #onChange} when the index moved. */
    public void updateFromPointer(float px) {
        float trackL = rect.x + TRACK_INSET;
        float trackR = rect.x + rect.w - LABEL_GUTTER - TRACK_INSET;
        float trackW = Math.max(1f, trackR - trackL);
        float u = (px - trackL) / trackW;
        if (u < 0f) u = 0f;
        if (u > 1f) u = 1f;
        int n = ticks.length;
        int newIdx = Math.round(u * (n - 1));
        if (newIdx != currentIndex) {
            currentIndex = newIdx;
            onChange.accept(currentIndex);
        }
    }

    public void drawShape(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        float trackL = rect.x + TRACK_INSET;
        float trackR = rect.x + rect.w - LABEL_GUTTER - TRACK_INSET;
        float trackY = rect.cy();

        // Track: thin recessed line.
        s.setColor(UIVars.SLOT_RECESS);
        s.rect(trackL, trackY - 2f, trackR - trackL, 4f);

        // Tick marks - small vertical hashes at each allowed value.
        s.setColor(UIVars.BORDER_MID);
        int n = ticks.length;
        for (int i = 0; i < n; i++) {
            float u = n == 1 ? 0.5f : i / (float) (n - 1);
            float tx = trackL + (trackR - trackL) * u;
            s.rect(tx - 1f, trackY - 6f, 2f, 12f);
        }

        // Thumb - taller rectangle at the current tick. Accent border while
        // dragging so the captured target is unambiguous.
        float u = n == 1 ? 0.5f : currentIndex / (float) (n - 1);
        float thumbX = trackL + (trackR - trackL) * u;
        float thumbW = 12f;
        float thumbH = 22f;
        if (dragging) {
            s.setColor(UIVars.ACCENT);
            s.rect(thumbX - thumbW * 0.5f - 2f,
                    trackY - thumbH * 0.5f - 2f,
                    thumbW + 4f, thumbH + 4f);
        }
        s.setColor(UIVars.TEXT_BODY);
        s.rect(thumbX - thumbW * 0.5f, trackY - thumbH * 0.5f,
                thumbW, thumbH);
    }

    public void drawText(UiCtx ctx) {
        BitmapFont font = ctx.fontRegular;
        font.setColor(dragging ? UIVars.ACCENT : UIVars.TEXT_BODY);
        float value = ticks[currentIndex];
        String shown = labelFmt != null ? labelFmt.apply(value)
                                        : Float.toString(value);
        ctx.layout.setText(font, shown);
        // Right-anchored within the label gutter so values of different
        // widths (8 vs. 100%) all hug the same right edge.
        float labelRight = rect.x + rect.w - 4f;
        float labelX = labelRight - ctx.layout.width;
        float labelY = rect.y + (rect.h + ctx.layout.height) * 0.5f;
        font.draw(ctx.batch, shown, labelX, labelY);
    }

    private int clampIndex(int i) {
        if (i < 0) return 0;
        if (i >= ticks.length) return ticks.length - 1;
        return i;
    }

    /** Find the index of the value in {@code ticks} closest to {@code v}.
     *  Used by row builders to derive {@link #currentIndex} from the
     *  current persisted value. */
    public static int nearestIndex(float[] ticks, float v) {
        int best = 0;
        float bestD = Math.abs(ticks[0] - v);
        for (int i = 1; i < ticks.length; i++) {
            float d = Math.abs(ticks[i] - v);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }
}
