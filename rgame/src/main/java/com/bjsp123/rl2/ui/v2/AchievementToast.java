package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Transient unlock-notification banner. Draws a single message line at the
 * top of the visible window for {@link #SHOW_MS} milliseconds, then fades
 * over {@link #FADE_MS}. Multiple unlocks queued in the same frame play
 * back-to-back so a string of first-time achievements doesn't stomp on
 * each other.
 *
 * <p>Owned by {@code PlayScreen}; advanced by the same wall-clock
 * {@code dt} the animator consumes; rendered above the HUD strip and
 * below modal popups.
 */
public final class AchievementToast {

    /** Milliseconds the banner sits at full opacity. */
    private static final int SHOW_MS = 2600;
    /** Milliseconds the banner fades over after the show window. */
    private static final int FADE_MS = 400;
    /** Vertical pad below the top of the visible window. */
    private static final float TOP_INSET_PX = 40f;
    /** Banner height in world pixels. */
    private static final float BANNER_H = 36f;

    private final UiCtx ctx;
    private final Deque<String> pending = new ArrayDeque<>();
    private String currentText;
    private int    remainingMs;

    public AchievementToast(UiCtx ctx) {
        this.ctx = ctx;
    }

    /** Queue {@code text} to be shown. If nothing is currently up, the
     *  banner activates on the next render frame. */
    public void show(String text) {
        if (text == null || text.isEmpty()) return;
        if (currentText == null) {
            currentText = text;
            remainingMs = SHOW_MS + FADE_MS;
        } else {
            pending.add(text);
        }
    }

    /** Cancel any currently-shown banner and drop the queue. Used when
     *  the screen tears down so a stale toast doesn't survive. */
    public void clear() {
        currentText = null;
        remainingMs = 0;
        pending.clear();
    }

    /** Advance the countdown by {@code dtMs} and draw the banner if one
     *  is active. Caller is OUTSIDE any active SpriteBatch — this method
     *  manages its own batch + shape begin/end. */
    public void render(int dtMs) {
        if (currentText == null) return;
        remainingMs -= dtMs;
        if (remainingMs <= 0) {
            currentText = pending.poll();
            remainingMs = currentText != null ? SHOW_MS + FADE_MS : 0;
            if (currentText == null) return;
        }
        float alpha = remainingMs >= FADE_MS
                ? 1f
                : Math.max(0f, remainingMs / (float) FADE_MS);
        drawBanner(currentText, alpha);
    }

    private void drawBanner(String text, float alpha) {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float bannerW = Math.min(360f, vw - 32f);
        float bannerX = (vw - bannerW) * 0.5f;
        float bannerY = vh - TOP_INSET_PX - BANNER_H;

        // Shape pass — Window.drawShape paints the shadow + tri-line
        // border + warm fill in one call; we wrap it in a single
        // begin()/end() block. The fade-to-zero is carried via the
        // fill's alpha argument; the shadow / border lines stay full
        // opacity which reads as the banner snapping cleanly out at the
        // end of the fade.
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Window.drawShape(ctx, bannerX, bannerY, bannerW, BANNER_H,
                UIVars.WIN_BG, alpha);
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // Text pass — accent yellow, faded by alpha.
        ctx.batch.begin();
        Color tint = UIVars.ACCENT;
        TextDraw.centre(ctx, ctx.fontRegular,
                new Color(tint.r, tint.g, tint.b, alpha),
                text, bannerX + bannerW * 0.5f,
                bannerY + BANNER_H * 0.5f + ctx.fontRegular.getCapHeight() * 0.5f);
        ctx.batch.end();
    }
}
