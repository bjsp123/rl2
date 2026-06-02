package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.ui.v2.stage.V2Popup;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking, top-of-screen "first encounter" tip popup.
 *
 * <p>Layout (compact, single panel):
 * <ul>
 *   <li>"Tip:" label on the left (accent colour)</li>
 *   <li>Body text in the middle, wrapping as needed</li>
 *   <li>Icon on the right, if one was supplied</li>
 *   <li>Thin countdown bar along the bottom edge</li>
 * </ul>
 *
 * <p>Position: docked just below the V2Hud top band so it doesn't obscure
 * world or HUD content.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Appears with a brief flash overlay so the player notices it.</li>
 *   <li>Auto-fades after {@link #DURATION_SECONDS} real-time seconds.</li>
 *   <li>Tap inside the panel pins it indefinitely; tap again dismisses;
 *       tap outside also dismisses.</li>
 *   <li>When a second tip is queued while one is visible, the new tip
 *       appears below the current one after a 1 s pause and slides up to
 *       replace it. Pinning suspends the slide so the player can read in
 *       peace.</li>
 *   <li>Game continues underneath (non-blocking).</li>
 * </ul>
 *
 * <p>Body text supports {@code \n} (rendered as line break) and {@code *text*}
 * (rendered as accent-coloured emphasis). The asterisks themselves are
 * stripped from the rendered output.
 */
public final class V2TipPopup implements V2Popup {

    /** Total real-time seconds an unpinned tip stays before auto-fading. */
    private static final float DURATION_SECONDS       = 10f;
    /** Linear alpha fade in the last {@link #FADE_SECONDS} of lifetime. */
    private static final float FADE_SECONDS           = 1f;
    /** Bright overlay shown on the first {@link #FLASH_SECONDS} after a tip
     *  becomes visible so the player notices it without it shouting. */
    private static final float FLASH_SECONDS          = 0.28f;
    /** Pause between a new tip arriving and it sliding up to replace the
     *  current one. Gives the player time to register "another tip is
     *  here" before the handoff. */
    private static final float INCOMING_DELAY_SECONDS = 1f;
    /** Duration of the slide-up handoff between current and incoming. */
    private static final float SLIDE_SECONDS          = 0.45f;
    /** Pixels reserved at the top of the viewport for the V2Hud cluster
     *  (portrait, bars, burger). Tip panel's TOP edge sits this many
     *  pixels below the viewport top. */
    private static final float TOP_HUD_INSET          = 80f;
    /** Vertical gap between the stacked panels during the slide handoff. */
    private static final float STACK_GAP              = 6f;

    private static final float PANEL_PAD     = 8f;
    private static final float LABEL_GAP     = 6f;
    private static final float ICON_GAP      = 8f;
    private static final float ICON_SIZE     = 28f;
    private static final float COUNTDOWN_H   = 3f;

    private final UiCtx ctx;
    private TipEntry current;
    private TipEntry incoming;
    private float incomingDelay;
    private boolean replacing;
    private float slideProgress;
    private boolean pinned;

    /** Hit-test rect for the focused (current) panel in its non-slide
     *  position - clicks against this rect pin/dismiss. */
    private final Rect panel = new Rect();

    public V2TipPopup(UiCtx ctx) {
        this.ctx = ctx;
    }

    public boolean isShowing() { return current != null; }

    /** True when the popup can accept another tip without dropping a queued
     *  one. Used by {@link TipSystem} to gate queue drains. */
    public boolean canAcceptNew() { return current == null || incoming == null; }

    /** Show a tip. Routes the entry to the appropriate slot:
     *  <ul>
     *    <li>Empty popup → becomes the current tip and flashes.</li>
     *    <li>Has current but no incoming → staged as incoming; after the
     *        {@link #INCOMING_DELAY_SECONDS pause} the slide handoff
     *        begins.</li>
     *    <li>Both slots full → incoming is overwritten (newest wins). The
     *        TipSystem queue normally prevents this by waiting for
     *        {@link #canAcceptNew()}.</li>
     *  </ul> */
    public void show(String title, String body, TextureRegion icon) {
        TipEntry e = new TipEntry(body, icon);
        if (current == null) {
            current = e;
            pinned = false;
            return;
        }
        incoming = e;
        incomingDelay = INCOMING_DELAY_SECONDS;
        replacing = false;
        slideProgress = 0f;
    }

    /** Tick the lifecycle. Driven from {@link com.bjsp123.rl2.screen.PlayScreen}
     *  with the unscaled real-time delta - tips don't slow when animation
     *  speed slows. */
    public void tick(float dt) {
        if (current == null) return;
        // Tick down per-panel flash overlays independently.
        if (current.flash > 0f) current.flash = Math.max(0f, current.flash - dt);
        if (incoming != null && incoming.flash > 0f) {
            incoming.flash = Math.max(0f, incoming.flash - dt);
        }
        // Current-tip auto-fade timer - suspended while pinned (player is
        // reading it) or sliding off (the slide IS the dismissal).
        if (!pinned && !replacing) {
            current.elapsed += dt;
            if (current.elapsed >= DURATION_SECONDS) {
                dismissCurrent();
                return;
            }
        }
        // Incoming-tip state machine: count down the pause, then drive the
        // slide. Pinning blocks both phases so the player can finish reading.
        if (incoming != null && !pinned) {
            if (!replacing) {
                incomingDelay -= dt;
                if (incomingDelay <= 0f) {
                    replacing = true;
                    // Reflash the incoming the moment it actually appears.
                    incoming.flash = FLASH_SECONDS;
                }
            } else {
                slideProgress += dt / SLIDE_SECONDS;
                if (slideProgress >= 1f) {
                    current = incoming;
                    pinned = false;
                    incoming = null;
                    replacing = false;
                    slideProgress = 0f;
                    // TipSystem may have more queued; let it know we have a
                    // free slot now (incoming == null).
                    TipSystem.onPopupDismissed();
                }
            }
        }
    }

    /** Click handler. Behaviour:
     *  <ul>
     *    <li>Tap <i>inside</i> the panel pins (first tap) or dismisses
     *        (second tap). The panel is the interactive surface so this
     *        consumes the click - returns {@code true}.</li>
     *    <li>Tap <i>outside</i> the panel starts the fade-out (unpins and
     *        jumps elapsed time into the {@link #FADE_SECONDS fade
     *        window}) but lets the click pass through to the game -
     *        returns {@code false}. Tip dismisses naturally as the fade
     *        completes.</li>
     *  </ul> */
    public boolean handleClick(float vx, float vy) {
        if (current == null) return false;
        boolean inside = panel.contains(vx, vy);
        if (inside) {
            if (pinned) {
                dismissCurrent();
            } else {
                pinned = true;
                current.elapsed = 0f;
            }
            return true;
        }
        // Outside the panel: nudge the tip into its fade-out phase but
        // don't consume the input - the player's click should still hit
        // the game world / HUD beneath.
        pinned = false;
        float fadeStart = DURATION_SECONDS - FADE_SECONDS;
        if (current.elapsed < fadeStart) current.elapsed = fadeStart;
        return false;
    }

    /** Back-compat overload. Treated as an outside-click so it never
     *  consumes - matches the new passthrough semantics. */
    public boolean handleClick() { return handleClick(Float.NaN, Float.NaN); }

    private void dismissCurrent() {
        // Promote queued incoming (if any) into the current slot
        // immediately, cancelling any half-played slide.
        TipEntry next = incoming;
        current = null;
        pinned = false;
        incoming = null;
        incomingDelay = 0f;
        slideProgress = 0f;
        replacing = false;
        if (next != null) {
            current = next;
            current.elapsed = 0f;
            current.flash = FLASH_SECONDS;
        }
        TipSystem.onPopupDismissed();
    }

    @Override public boolean isOpen() { return current != null; }

    @Override
    public void renderSelf() {
        if (current == null) return;

        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float panelW = Math.min(vw * 0.72f, 420f);
        // Both panels use the same width; height comes from per-tip body
        // wrap. The incoming may have a different height than the current
        // - we compute it independently.
        float currentH = computeHeight(current, panelW);
        float incomingH = incoming != null ? computeHeight(incoming, panelW) : 0f;

        float topSlotY = vh - TOP_HUD_INSET - currentH;
        float currentY, incomingY = 0f;
        if (replacing) {
            // Both panels shift up by (currentH + STACK_GAP) over the slide.
            float dy = (currentH + STACK_GAP) * slideProgress;
            currentY  = topSlotY + dy;
            incomingY = topSlotY - (incomingH + STACK_GAP) + dy;
        } else {
            currentY = topSlotY;
        }
        float panelX = (vw - panelW) * 0.5f;

        // Per-panel alpha. Current uses the fade curve (capped by pin) and
        // additionally fades out as it slides off the top. Incoming stays
        // fully opaque - the slide itself is its visual cue.
        float currentAlpha = panelAlpha(current.elapsed);
        if (replacing) currentAlpha *= Math.max(0f, 1f - slideProgress);
        float incomingAlpha = 1f;

        panel.set(panelX, currentY, panelW, currentH);

        // Render incoming first so the current sits on top during the slide.
        if (incoming != null && replacing) {
            renderPanel(incoming, panelX, incomingY, panelW, incomingH,
                    incomingAlpha, /*drawCountdown=*/ false);
        }
        renderPanel(current, panelX, currentY, panelW, currentH,
                currentAlpha, /*drawCountdown=*/ true);
    }

    private float panelAlpha(float elapsed) {
        if (pinned) return 1f;
        float fadeStart = DURATION_SECONDS - FADE_SECONDS;
        if (elapsed <= fadeStart) return 1f;
        return Math.max(0f, 1f - (elapsed - fadeStart) / FADE_SECONDS);
    }

    private float computeHeight(TipEntry tip, float panelW) {
        // "Tip:" label + body text column + optional icon. The label width
        // varies with the font but is short ("Tip:"); we compute it once.
        float labelW = labelWidth();
        float iconColW = tip.icon != null ? ICON_SIZE + ICON_GAP : 0f;
        float bodyMaxW = panelW - 2 * PANEL_PAD - labelW - LABEL_GAP - iconColW;
        List<String> bodyLines = wrappedBody(tip.body, bodyMaxW);
        float lineH = ctx.fontRegular.getLineHeight() + 1f;
        float textColH = lineH * Math.max(1, bodyLines.size());
        float contentH = Math.max(textColH, tip.icon != null ? ICON_SIZE : 0f);
        return contentH + 2 * PANEL_PAD + COUNTDOWN_H;
    }

    private float labelWidth() {
        ctx.layout.setText(ctx.fontRegular, "Tip:");
        return ctx.layout.width;
    }

    private List<String> wrappedBody(String body, float maxBodyW) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        for (String seg : body.split("\\\\n")) {
            List<String> wrapped = new ArrayList<>();
            TextDraw.wrap(ctx.fontRegular, stripEmphasis(seg),
                    maxBodyW, 12, wrapped);
            out.addAll(wrapped);
        }
        return out;
    }

    /** Render one panel - chrome + Tip label + body + optional icon +
     *  optional countdown bar - at the given screen rect with the given
     *  alpha multiplier. */
    private void renderPanel(TipEntry tip, float panelX, float panelY,
                             float panelW, float panelH, float alpha,
                             boolean drawCountdown) {
        if (alpha <= 0f) return;
        ctx.applyProjection();
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(
                com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Shape pass: shadow, parchment fill, 1-px ink border, countdown
        // bar fill, and the bright "just appeared" flash overlay.
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);

        ctx.shapes.setColor(0f, 0f, 0f, 0.5f * alpha);
        ctx.shapes.rect(panelX + UIVars.SHADOW_OFFSET,
                panelY - UIVars.SHADOW_OFFSET, panelW, panelH);

        Color bg = UIVars.INFO_WIN_BG;
        ctx.shapes.setColor(bg.r, bg.g, bg.b, UIVars.PANEL_FILL_ALPHA * alpha);
        ctx.shapes.rect(panelX, panelY, panelW, panelH);

        // Countdown bar - thin yellow strip along the bottom edge. Pinned
        // tips show a full bar (no countdown). Slide-off panels skip the
        // bar so the visual is calm during transition.
        if (drawCountdown) {
            float remaining = pinned ? 1f
                    : Math.max(0f, 1f - tip.elapsed / DURATION_SECONDS);
            Color accent = UIVars.ACCENT;
            ctx.shapes.setColor(accent.r, accent.g, accent.b, 0.85f * alpha);
            ctx.shapes.rect(panelX + 4f, panelY + 2f,
                    (panelW - 8f) * remaining, COUNTDOWN_H);
        }

        // Flash overlay - bright accent on top of the panel that fades out
        // over FLASH_SECONDS. Sits above the fill but below the text pass.
        if (tip.flash > 0f) {
            float flashT = tip.flash / FLASH_SECONDS;
            Color a = UIVars.ACCENT;
            ctx.shapes.setColor(a.r, a.g, a.b, 0.55f * flashT * alpha);
            ctx.shapes.rect(panelX, panelY, panelW, panelH);
        }

        ctx.shapes.end();

        // 1-px inked border drawn as four edge rects so it stays inside
        // the Filled pass (no begin/end swap).
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        Color border = UIVars.INFO_RULE;
        ctx.shapes.setColor(border.r, border.g, border.b, alpha);
        ctx.shapes.rect(panelX,            panelY,            panelW, 1f);
        ctx.shapes.rect(panelX,            panelY + panelH - 1, panelW, 1f);
        ctx.shapes.rect(panelX,            panelY,            1f, panelH);
        ctx.shapes.rect(panelX + panelW-1, panelY,            1f, panelH);
        ctx.shapes.end();

        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);

        // Text + icon pass.
        ctx.batch.begin();

        float labelW = labelWidth();
        float iconColW = tip.icon != null ? ICON_SIZE + ICON_GAP : 0f;
        float bodyMaxW = panelW - 2 * PANEL_PAD - labelW - LABEL_GAP - iconColW;
        List<String> bodyLines = wrappedBody(tip.body, bodyMaxW);
        float lineH = ctx.fontRegular.getLineHeight() + 1f;

        // "Tip:" label - accent colour, top-aligned with the first body line.
        Color accent = UIVars.ACCENT;
        float topInnerY = panelY + panelH - PANEL_PAD;
        ctx.fontRegular.setColor(accent.r, accent.g, accent.b, alpha);
        ctx.fontRegular.draw(ctx.batch, "Tip:",
                panelX + PANEL_PAD, topInnerY);

        // Body text - starts to the right of "Tip:", one line per wrapped
        // entry. Uses TEXT_BODY for body text and ACCENT for *emphasis*
        // runs delimited by asterisks.
        float bodyX = panelX + PANEL_PAD + labelW + LABEL_GAP;
        Color body = UIVars.TEXT_BODY;
        float by = topInnerY;
        for (String line : bodyLines) {
            drawEmphasizedLine(line, bodyX, by, body, accent, alpha);
            by -= lineH;
        }

        // Icon - far right, vertically centered against the body column.
        if (tip.icon != null) {
            float iconX = panelX + panelW - PANEL_PAD - ICON_SIZE;
            float iconY = panelY + panelH * 0.5f - ICON_SIZE * 0.5f;
            ctx.batch.setColor(1f, 1f, 1f, alpha);
            ctx.batch.draw(tip.icon, iconX, iconY, ICON_SIZE, ICON_SIZE);
            ctx.batch.setColor(Color.WHITE);
        }

        ctx.fontRegular.setColor(Color.WHITE);
        ctx.batch.end();
    }

    private static String stripEmphasis(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("*", "");
    }

    /** Draw a body line, painting {@code *...*} runs in {@code accent}
     *  while surrounding text uses {@code body}. Asterisks are not drawn.
     *  Caller must be inside a SpriteBatch begin/end. */
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

    /** Per-tip mutable state. The {@link TipSystem} API still passes a
     *  title but the compact layout doesn't render it - {@link #show} drops
     *  the title and the body carries the message. */
    private static final class TipEntry {
        final String body;
        final TextureRegion icon;
        float elapsed;
        float flash;
        TipEntry(String body, TextureRegion icon) {
            this.body  = body  == null ? "" : body;
            this.icon  = icon;
            this.elapsed = 0f;
            this.flash   = FLASH_SECONDS;
        }
    }
}
