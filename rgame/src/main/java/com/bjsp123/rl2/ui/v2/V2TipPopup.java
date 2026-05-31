package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.ui.v2.stage.V2Popup;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking, auto-fading "first encounter" tip popup.
 *
 * <p>Per the design spec:
 * <ul>
 *   <li>Renders centred above the middle of the screen.</li>
 *   <li>Icon on the left, display title on the right, body text below the
 *       title. The icon is a {@link TextureRegion} so any sprite source
 *       works (mob sprite, item sprite, buff icon, perk icon, concept icon).</li>
 *   <li>Click anywhere dismisses immediately.</li>
 *   <li>Auto-fades after 5 real-time seconds.</li>
 *   <li>Game continues underneath (non-blocking).</li>
 * </ul>
 *
 * <p>Body text supports {@code \n} (rendered as line break) and {@code *text*}
 * (rendered as accent-coloured emphasis). The asterisks themselves are
 * stripped from the rendered output.
 *
 * <p>Implements {@link V2Popup} so the {@link com.bjsp123.rl2.ui.v2.stage.V2Stage}
 * z-orders it above the world but underneath modal sub-popups. Input is
 * polled separately from {@link com.bjsp123.rl2.screen.PlayScreen} — there's
 * no input-capture model because the player must keep playing while the tip
 * is visible.
 */
public final class V2TipPopup implements V2Popup {

    /** Total real-time duration a tip stays on screen before auto-fading. */
    private static final float DURATION_SECONDS = 5f;
    /** Last second is a linear alpha fade-out. */
    private static final float FADE_SECONDS     = 1f;

    private final UiCtx ctx;
    private String currentTitle;
    private String currentBody;
    private TextureRegion currentIcon;
    private float elapsedSeconds;
    private boolean showing;
    /** When true, the auto-fade timer is suspended - tip stays up until the
     *  player taps it again. Set by a tap inside the panel; cleared (along
     *  with dismissal) by a subsequent tap inside, or by any tap outside. */
    private boolean pinned;

    private final Rect panel = new Rect();

    public V2TipPopup(UiCtx ctx) {
        this.ctx = ctx;
    }

    public boolean isShowing() { return showing; }

    /** Show a tip. If one is already on-screen the existing tip is replaced
     *  immediately (TipSystem's queue prevents this in normal flow but we
     *  don't reject the call defensively). */
    public void show(String title, String body, TextureRegion icon) {
        this.currentTitle = title == null ? "" : title;
        this.currentBody  = body  == null ? "" : body;
        this.currentIcon  = icon;
        this.elapsedSeconds = 0f;
        this.pinned = false;
        this.showing = true;
    }

    /** Tick the auto-fade timer. Call once per render frame from
     *  {@link com.bjsp123.rl2.screen.PlayScreen}. {@code dtSeconds} is the
     *  unscaled real-time delta — tips don't slow down when animation
     *  speed slows down. Pinned tips ignore the timer until a tap unpins
     *  them. */
    public void tick(float dtSeconds) {
        if (!showing || pinned) return;
        elapsedSeconds += dtSeconds;
        if (elapsedSeconds >= DURATION_SECONDS) dismiss();
    }

    /** Click handler with coordinates. Tap inside the panel toggles pinned
     *  (first tap pins, second tap dismisses); tap outside the panel always
     *  dismisses. Returns true if a tip was open and the tap was consumed
     *  (so the caller can swallow the click before it reaches the world). */
    public boolean handleClick(float vx, float vy) {
        if (!showing) return false;
        boolean inside = panel.contains(vx, vy);
        if (inside) {
            if (pinned) {
                dismiss();
            } else {
                // Reset the fade so the freshly-pinned tip reads at full
                // opacity, not whatever alpha it had drifted to.
                pinned = true;
                elapsedSeconds = 0f;
            }
        } else {
            dismiss();
        }
        return true;
    }

    /** Back-compat overload - dismisses regardless of position. Prefer the
     *  coord-aware {@link #handleClick(float, float)} so the
     *  "tap inside = pin" affordance fires. */
    public boolean handleClick() { return handleClick(Float.NaN, Float.NaN); }

    private void dismiss() {
        showing = false;
        pinned  = false;
        currentTitle = null;
        currentBody  = null;
        currentIcon  = null;
        TipSystem.onPopupDismissed();
    }

    @Override
    public boolean isOpen() { return showing; }

    @Override
    public void renderSelf() {
        if (!showing) return;
        // Layout: panel sits centred horizontally, above the middle (y ~= 60%
        // of viewport height). Body wraps to a fixed max width; height grows
        // with line count.
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float panelW = Math.min(vw * 0.72f, 360f);
        float maxBodyW = panelW - 16f - 40f - 8f;  // pad + iconW + gap
        List<String> bodyLines = new ArrayList<>();
        TextDraw.wrap(ctx.fontRegular,
                stripEmphasis(currentBody == null ? "" : currentBody),
                maxBodyW, 12, bodyLines);
        // Wrap on explicit \n too: TextDraw.wrap doesn't honour newlines so
        // pre-split on \n then wrap each segment.
        bodyLines.clear();
        if (currentBody != null) {
            for (String seg : currentBody.split("\\\\n")) {
                List<String> wrapped = new ArrayList<>();
                TextDraw.wrap(ctx.fontRegular, stripEmphasis(seg),
                        maxBodyW, 12, wrapped);
                bodyLines.addAll(wrapped);
            }
        }
        float lineH   = ctx.fontRegular.getLineHeight() + 1f;
        float titleH  = ctx.fontHeader.getLineHeight() + 1f;
        float bodyH   = lineH * Math.max(1, bodyLines.size());
        float iconRow = Math.max(titleH + 4f + bodyH, 44f);
        float panelH  = iconRow + 16f;
        float panelX  = (vw - panelW) * 0.5f;
        float panelY  = vh * 0.55f;
        panel.set(panelX, panelY, panelW, panelH);

        // Alpha curve: full opacity until DURATION - FADE, linear ramp to 0
        // over the final FADE seconds.
        float alpha = 1f;
        float fadeStart = DURATION_SECONDS - FADE_SECONDS;
        if (elapsedSeconds > fadeStart) {
            alpha = Math.max(0f,
                    1f - (elapsedSeconds - fadeStart) / FADE_SECONDS);
        }

        // Shape pass: panel chrome.
        ctx.applyProjection();
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(
                com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Shadow.
        ctx.shapes.setColor(0f, 0f, 0f, 0.5f * alpha);
        ctx.shapes.rect(panel.x + UIVars.SHADOW_OFFSET,
                panel.y - UIVars.SHADOW_OFFSET,
                panel.w, panel.h);
        // Background.
        Color bg = UIVars.WIN_BG;
        ctx.shapes.setColor(bg.r, bg.g, bg.b, UIVars.PANEL_FILL_ALPHA * alpha);
        ctx.shapes.rect(panel.x, panel.y, panel.w, panel.h);
        // Border (single thin line).
        ctx.shapes.end();
        ctx.shapes.begin(ShapeRenderer.ShapeType.Line);
        Color border = UIVars.BORDER_MID;
        ctx.shapes.setColor(border.r, border.g, border.b, alpha);
        ctx.shapes.rect(panel.x, panel.y, panel.w, panel.h);
        ctx.shapes.end();
        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);

        // Text + icon pass.
        ctx.batch.begin();
        float pad = 8f;
        float iconSize = 32f;
        float iconX = panel.x + pad;
        float iconY = panel.y + panel.h - pad - iconSize;
        if (currentIcon != null) {
            ctx.batch.setColor(1f, 1f, 1f, alpha);
            ctx.batch.draw(currentIcon, iconX, iconY, iconSize, iconSize);
            ctx.batch.setColor(Color.WHITE);
        }
        float textX = iconX + iconSize + 8f;
        float titleY = panel.y + panel.h - pad;
        Color title = UIVars.TEXT_BODY;
        ctx.fontHeader.setColor(title.r, title.g, title.b, alpha);
        TextDraw.left(ctx, ctx.fontHeader,
                new Color(title.r, title.g, title.b, alpha),
                currentTitle == null ? "" : currentTitle,
                textX, titleY);
        ctx.fontHeader.setColor(Color.WHITE);

        Color body = UIVars.TEXT_DIM;
        Color accent = UIVars.ACCENT;
        float by = titleY - titleH;
        for (String line : bodyLines) {
            drawEmphasizedLine(line, textX, by, body, accent, alpha);
            by -= lineH;
        }
        ctx.batch.end();
    }

    /** Strip {@code *emphasis*} markers so the wrap pass measures line
     *  widths against the visible text (asterisks aren't drawn). Per-run
     *  colouring happens later in {@link #drawEmphasizedLine}. */
    private static String stripEmphasis(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("*", "");
    }

    /** Render a line of body text, painting runs delimited by {@code *...*}
     *  in {@code accent} (yellow) while the surrounding text uses {@code body}.
     *  Asterisks themselves are not drawn. Segments are laid out left-to-right
     *  using the regular font's glyph layout for spacing.
     *
     *  <p>Caller is inside a SpriteBatch begin/end. */
    private void drawEmphasizedLine(String line, float x, float yBaseline,
                                    Color body, Color accent, float alpha) {
        if (line == null || line.isEmpty()) return;
        float cursor = x;
        int i = 0;
        boolean emph = false;
        StringBuilder run = new StringBuilder();
        while (i <= line.length()) {
            char c = (i < line.length()) ? line.charAt(i) : '\0';
            if (i == line.length() || c == '*') {
                if (run.length() > 0) {
                    Color col = emph ? accent : body;
                    String s = run.toString();
                    ctx.fontRegular.setColor(col.r, col.g, col.b, alpha);
                    ctx.fontRegular.draw(ctx.batch, s, cursor, yBaseline);
                    ctx.layout.setText(ctx.fontRegular, s);
                    cursor += ctx.layout.width;
                    run.setLength(0);
                }
                if (c == '*') emph = !emph;
            } else {
                run.append(c);
            }
            i++;
        }
        ctx.fontRegular.setColor(Color.WHITE);
    }
}
