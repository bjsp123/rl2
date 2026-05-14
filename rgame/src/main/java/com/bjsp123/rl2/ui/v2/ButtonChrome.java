package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/** Shared renderer for V2 button chrome and glyphs. */
public final class ButtonChrome {
    private ButtonChrome() {}

    public static void shape(UiCtx ctx, Rect rect, boolean pressed, boolean checked,
                             boolean warn, Color restingFill) {
        ShapeRenderer s = ctx.shapes;
        if (warn) {
            Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h,
                    UIVars.HUD_LINE_W,
                    UIVars.WARN_HL, UIVars.TEXT_WARN, UIVars.WARN_SHADE);
        } else if (checked && !pressed) {
            Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h,
                    UIVars.HUD_LINE_W,
                    UIVars.ACCENT, UIVars.BORDER_MID, UIVars.BORDER_INNER);
        } else {
            Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h, UIVars.HUD_LINE_W);
        }
        s.setColor(pressed || checked ? UIVars.BTN_PRESSED_BG : restingFill);
        s.rect(rect.x + UIVars.HUD_BORDER, rect.y + UIVars.HUD_BORDER,
                rect.w - 2 * UIVars.HUD_BORDER, rect.h - 2 * UIVars.HUD_BORDER);
    }

    public static void icon(UiCtx ctx, Rect rect, TextureRegion icon,
                            boolean hot, boolean warn) {
        if (icon == null) return;
        Color tint = warn
                ? (hot ? UIVars.WARN_HL : UIVars.TEXT_WARN)
                : (hot ? UIVars.ACCENT : UIVars.TEXT_BODY);
        ctx.batch.setColor(tint);
        float size = Math.min(rect.w, rect.h) * 0.6f;
        ctx.batch.draw(icon,
                rect.cx() - size * 0.5f, rect.cy() - size * 0.5f,
                size, size);
        ctx.batch.setColor(1f, 1f, 1f, 1f);
    }

    public static void burgerGlyph(UiCtx ctx, Rect rect, boolean hot) {
        ShapeRenderer s = ctx.shapes;
        s.setColor(hot ? UIVars.ACCENT : UIVars.TEXT_BODY);
        float cx = rect.cx();
        float cy = rect.cy();
        float barW = rect.w * 0.50f;
        float barH = 4f;
        float gap = 6f;
        for (int i = -1; i <= 1; i++) {
            float by = cy - barH * 0.5f + i * (barH + gap);
            s.rect(cx - barW * 0.5f, by, barW, barH);
        }
    }
}
