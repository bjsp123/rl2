package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Top-right burger icon — present on every V2 screen per CLAUDE.md ("burger
 * menu always present"). Draws a 56×56 button containing three short
 * horizontal bars stacked vertically. Tap behaviour is owned by the screen
 * (typically: open a small overlay with Title / Settings / Encyclopaedia).
 *
 * <p>For now the click target is wired but the overlay isn't implemented — it
 * dispatches to a screen-supplied {@link Runnable} that may be a no-op while
 * the V2 chain is being built up.
 */
public final class Burger {

    public static final float SIZE  = 56f;
    public static final float INSET = 12f;

    public final Rect rect = new Rect();
    public boolean pressed;
    private final Runnable onClick;

    public Burger(UiCtx ctx, Runnable onClick) {
        this.onClick = onClick;
        // Anchor at top-right of the current virtual viewport — reads
        // ctx.worldW()/H() so the burger re-positions on layout rebuild
        // after a UiScale change.
        rect.set(ctx.worldW() - SIZE - INSET, ctx.worldH() - SIZE - INSET, SIZE, SIZE);
    }

    public void drawShape(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h, Pal.HUD_LINE_W);
        s.setColor(pressed ? UiColors.BTN_PRESSED_BG : UiColors.HUD_BG);
        s.rect(rect.x + Pal.HUD_BORDER, rect.y + Pal.HUD_BORDER,
               rect.w - 2 * Pal.HUD_BORDER, rect.h - 2 * Pal.HUD_BORDER);

        // Three horizontal bars — width ~50% of the button, vertically spaced
        // so the gap between bars matches the bar height (the canonical
        // "hamburger" silhouette).
        float cx = rect.cx();
        float cy = rect.cy();
        float barW = rect.w * 0.50f;
        float barH = 4f;
        float gap  = 6f;
        s.setColor(pressed ? Pal.ACCENT : Pal.WHITE);
        for (int i = -1; i <= 1; i++) {
            float by = cy - barH * 0.5f + i * (barH + gap);
            s.rect(cx - barW * 0.5f, by, barW, barH);
        }
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }

    public void click() { if (onClick != null) onClick.run(); }
}
