package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/** Shared layout, chrome, and hit-state for fixed-width V2 tab strips. */
public final class TabStrip {
    public final Rect[] rects;
    private final boolean[] pressed;
    private int active;

    public TabStrip(int count) {
        rects = new Rect[count];
        pressed = new boolean[count];
        for (int i = 0; i < count; i++) rects[i] = new Rect();
    }

    public void layout(Rect window, float pad, float y, float h, float gap) {
        PanelLayout.tabStrip(rects, window, pad, y, h, gap);
    }

    public void setActive(int active) {
        this.active = active;
    }

    public int touchDown(float vx, float vy) {
        for (int i = 0; i < rects.length; i++) {
            if (rects[i].contains(vx, vy)) {
                pressed[i] = true;
                return i;
            }
        }
        return -1;
    }

    public int touchUp(float vx, float vy) {
        for (int i = 0; i < rects.length; i++) {
            if (!pressed[i]) continue;
            pressed[i] = false;
            return rects[i].contains(vx, vy) ? i : -1;
        }
        return -1;
    }

    public boolean hasPressed() {
        for (boolean b : pressed) if (b) return true;
        return false;
    }

    /** Cancel any armed press without firing - callers clear pending taps
     *  when a drag gesture is classified mid-touch. */
    public void clearPressed() {
        java.util.Arrays.fill(pressed, false);
    }

    public void drawShapes(ShapeRenderer s) {
        for (int i = 0; i < rects.length; i++) {
            drawTabShape(s, rects[i], i == active, pressed[i]);
        }
    }

    /** The single V2 tab / toggle-button chrome: tri-line border (accent
     *  when active or held) + darker pressed-style fill when active. Also
     *  used standalone for independent toggle buttons (e.g. the log's
     *  filter toggles) so every tab-shaped control reads identically. */
    public static void drawTabShape(ShapeRenderer s, Rect r,
                                    boolean active, boolean pressed) {
        if (active || pressed) {
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W,
                    UIVars.ACCENT, UIVars.BORDER_MID, UIVars.BORDER_INNER);
        } else {
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
        }
        s.setColor(active ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
        s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
    }

    /** Centred regular-font text labels - for tab strips that label with
     *  words instead of sheet icons. Caller has an active batch. */
    public void drawLabels(UiCtx ctx, String[] labels) {
        for (int i = 0; i < rects.length && i < labels.length; i++) {
            TextDraw.centre(ctx, ctx.fontRegular,
                    i == active ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    labels[i], rects[i].cx(), rects[i].cy() + 6f);
        }
    }

    public void drawIcons(UiCtx ctx, TextureRegion[] icons) {
        for (int i = 0; i < rects.length && i < icons.length; i++) {
            TextureRegion region = icons[i];
            if (region == null) continue;
            Rect r = rects[i];
            ctx.batch.setColor(i == active ? UIVars.ACCENT : UIVars.TEXT_BODY);
            float sz = Math.min(r.w, r.h) * 0.6f;
            ctx.batch.draw(region, r.cx() - sz * 0.5f, r.cy() - sz * 0.5f,
                    sz, sz);
            ctx.batch.setColor(1f, 1f, 1f, 1f);
        }
    }
}
