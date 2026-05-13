package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * A V2 button. Rectangular hit-target with a primitive-drawn cream border,
 * dark fill, and a centred label. State-aware (pressed / checked) so the
 * highlight reads from a glance.
 *
 * <p>Rendering is split into two passes: {@link #drawShape} between
 * ShapeRenderer begin/end, then {@link #drawText} between SpriteBatch
 * begin/end. The screen calls them in that order for every button it owns
 * so all rect primitives flush before any text rendering kicks in.
 *
 * <p>Hit testing: {@link #hit(float, float)} takes virtual-coords pointer
 * position (already unprojected by the viewport).
 */
public final class Btn {

    public final Rect rect = new Rect();
    public String label;
    public Runnable onClick;
    /** Optional icon - when non-null, drawn centred in the button rect
     *  INSTEAD of the label. Used by {@link com.bjsp123.rl2.ui.v2.V2Settings}
     *  for tab buttons (icon strip across the top of the panel). */
    public TextureRegion icon;
    /** {@code true} while a finger is held down on this button. Drives the
     *  pressed highlight; reset to {@code false} when the touch ends. */
    public boolean pressed;
    /** {@code true} for sticky toggle / radio buttons (e.g. tab strip's
     *  active tab, settings choice grid's selected value). */
    public boolean checked;
    /** {@code true} = render with the header font; {@code false} = regular. */
    public boolean header;
    /** {@code true} = red border + red label (destructive / danger action). */
    public boolean warn;

    public Btn(String label, float x, float y, float w, float h, Runnable onClick) {
        this.label = label;
        this.rect.set(x, y, w, h);
        this.onClick = onClick;
    }

    public Btn header() { this.header = true; return this; }

    /** Draw the button's chrome (tri-line border + fill). Caller is inside
     *  a {@link ShapeRenderer.ShapeType#Filled} block. Pressed buttons get
     *  a brighter fill; checked-not-pressed buttons get an accent-yellow
     *  outer line so the active state pops. */
    public void drawShape(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        boolean hot = pressed || checked;
        Color fill = hot ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG;
        if (warn) {
            Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h,
                    UIVars.HUD_LINE_W,
                    UIVars.WARN_HL, UIVars.TEXT_WARN, UIVars.WARN_SHADE);
        } else if (checked && !pressed) {
            Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h,
                    UIVars.HUD_LINE_W,
                    UIVars.ACCENT, UIVars.BORDER_MID, UIVars.BORDER_INNER);
        } else {
            Edges.drawTriLine(s, rect.x, rect.y, rect.w, rect.h,
                    UIVars.HUD_LINE_W);
        }
        s.setColor(fill);
        s.rect(rect.x + UIVars.HUD_BORDER, rect.y + UIVars.HUD_BORDER,
               rect.w - 2 * UIVars.HUD_BORDER, rect.h - 2 * UIVars.HUD_BORDER);
    }

    /** Draw the centred label or icon. Caller is inside a SpriteBatch
     *  begin/end. If {@link #icon} is non-null the label is suppressed -
     *  the icon is drawn centred at 60% of the button's smaller side,
     *  tinted yellow when pressed/checked and white otherwise. */
    public void drawText(UiCtx ctx) {
        boolean hot = pressed || checked;
        if (icon != null) {
            Color tint = hot ? UIVars.ACCENT : UIVars.TEXT_BODY;
            ctx.batch.setColor(tint);
            float size = Math.min(rect.w, rect.h) * 0.6f;
            ctx.batch.draw(icon,
                    rect.cx() - size * 0.5f, rect.cy() - size * 0.5f,
                    size, size);
            ctx.batch.setColor(1f, 1f, 1f, 1f);
            return;
        }
        BitmapFont font = header ? ctx.fontHeader : ctx.fontRegular;
        font.setColor(warn ? (hot ? UIVars.WARN_HL : UIVars.TEXT_WARN)
                           : (hot ? UIVars.ACCENT : UIVars.TEXT_BODY));
        String shown = TextDraw.ellipsize(font, label,
                rect.w - 2f * (UIVars.HUD_BORDER + 4f));
        ctx.layout.setText(font, shown);
        float tx = rect.x + (rect.w - ctx.layout.width)  * 0.5f;
        // y is the BASELINE position in libGDX; we want the visible text
        // vertically centred in the button. layout.height excludes descenders
        // so add a small fudge so descenders don't push the line low.
        float ty = rect.y + (rect.h + ctx.layout.height) * 0.5f;
        font.draw(ctx.batch, shown, tx, ty);
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }
}
