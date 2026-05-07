package com.bjsp123.rl2.ui.hud;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Disposable;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.model.LogEvent.EventPriority;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.ui.skin.UiPixelScale;
import com.bjsp123.rl2.ui.skin.UiScale;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrolling text log anchored above the bottom-right action cluster. Shows at most
 * {@link #MAX_LINES} filtered entries from {@link EventLog}, newest at the bottom, older lines
 * riding upward as fresh events arrive.
 *
 * <p>The font renders at a fixed screen-pixel size — we divide by {@link UiScale#scale()} so a
 * bigger UI scale shrinks the glyph in stage units to compensate. That keeps the log visually
 * "small and crisp" instead of scaling with the chunky action buttons.
 *
 * <p>Three filter flags mirror the toggles the HUD will expose later:
 * {@link #logOn}, {@link #showLowPriority}, {@link #showNonPlayer}. Defaults match the spec:
 * log on, low-priority hidden, mob-vs-mob chatter hidden.
 */
public class LogView extends Actor implements Disposable {

    public static final int MAX_LINES        = 10;
    public static final int COLLAPSED_LINES  = 2;
    private static final float LINE_GAP_PX = 1f;
    /** Width of the log area in screen pixels — caller can also override via setSize. */
    private static final float DEFAULT_WIDTH_PX = 320f;

    // Filter flags now live on the global LogPreferences so the Settings
    // screen can flip them. The HUD log used to expose four toggle buttons
    // for these; that's been folded into Settings → Log per the UI rules.

    private final BitmapFont font;
    private final List<LogEvent> scratch = new ArrayList<>(MAX_LINES);

    public LogView() {
        this.font = com.bjsp123.rl2.ui.skin.StoneUi.newDefaultFont();
        this.font.setUseIntegerPositions(false);
    }

    /**
     * Preferred height in stage units for MAX_LINES at the current UiScale. Divides by both
     * UiScale AND UiPixelScale so the text log stays at the same screen size regardless of
     * UI pixel scale — i.e., the log text is NOT pixel-upscaled, per the rule "all graphics
     * pixelated except the text log".
     */
    public float preferredHeight() {
        int lines = com.bjsp123.rl2.ui.skin.LogPreferences.expanded()
                ? MAX_LINES : COLLAPSED_LINES;
        float pxScale = logFontScale();
        float lineH = font.getLineHeight() * pxScale;
        return lineH * lines + LINE_GAP_PX * Math.max(0, lines - 1) * pxScale;
    }

    /** Preferred width in stage units — {@link #DEFAULT_WIDTH_PX} screen pixels wide. */
    public float preferredWidth() {
        return DEFAULT_WIDTH_PX * logFontScale();
    }

    /** Stage-units-per-screen-pixel for this log's text. UiScale and UiPixelScale
     *  get divided out so the log renders at a fixed screen-pixel size; the
     *  user-facing {@link com.bjsp123.rl2.ui.skin.LogFontScale} knob multiplies
     *  on top so the player can bump the log without inflating the rest of the
     *  UI. */
    private static float logFontScale() {
        return com.bjsp123.rl2.ui.skin.LogFontScale.scale()
                / (UiScale.scale() * UiPixelScale.scale());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (!com.bjsp123.rl2.ui.skin.LogPreferences.logOn()) return;

        boolean expanded        = com.bjsp123.rl2.ui.skin.LogPreferences.expanded();
        boolean showLowPriority = com.bjsp123.rl2.ui.skin.LogPreferences.showLowPriority();
        boolean showNonPlayer   = com.bjsp123.rl2.ui.skin.LogPreferences.showNonPlayer();
        int cap = expanded ? MAX_LINES : COLLAPSED_LINES;
        List<LogEvent> all = EventLog.all();
        scratch.clear();
        for (int i = all.size() - 1; i >= 0 && scratch.size() < cap; i--) {
            LogEvent e = all.get(i);
            if (e == null) continue;
            if (!showLowPriority && e.priority == EventPriority.LOW) continue;
            if (!showNonPlayer   && !e.involvesPlayer)               continue;
            scratch.add(e);
        }
        if (scratch.isEmpty()) return;

        float pxScale = logFontScale();
        float oldSx = font.getScaleX(), oldSy = font.getScaleY();
        font.getData().setScale(pxScale);
        float lineH = font.getLineHeight();
        float gap   = LINE_GAP_PX * pxScale;

        float x = getX();
        float y0 = getY();

        // scratch[0] is newest → render at bottom. scratch[n-1] oldest → render near top.
        for (int i = 0; i < scratch.size(); i++) {
            LogEvent e = scratch.get(i);
            font.setColor(colorFor(e, i, scratch.size()));
            // font.draw uses the top-left of the text box; line sits between y and y-lineH.
            float topY = y0 + i * (lineH + gap) + lineH;
            font.draw(batch, e.text, x, topY);
        }

        font.getData().setScale(oldSx, oldSy);
        font.setColor(Color.WHITE);
    }

    /** Slight fade toward the top so older lines visually recede. */
    private Color colorFor(LogEvent e, int idx, int total) {
        float fade = 1f - 0.55f * (idx / (float) Math.max(1, total - 1));
        if (e.priority == EventPriority.HIGH) {
            return new Color(1f, 1f, 0.85f, fade);
        }
        return new Color(0.82f, 0.82f, 0.82f, fade);
    }

    @Override
    public void dispose() {
        font.dispose();
    }
}
