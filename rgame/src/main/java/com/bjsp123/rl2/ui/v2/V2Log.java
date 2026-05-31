package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.ui.skin.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 game-log popup - full-screen-dimming modal overlay that shows the
 * scrollable event log with two filter toggles (low-priority / non-player).
 *
 * <p>Render lifecycle: {@link #render()} draws nothing while {@link #isOpen()}
 * is false; PlayScreen calls it after the HUD pass. Input is captured via
 * {@link #input()} - returns an {@link InputProcessor} that consumes all
 * events when open.
 *
 * <p>Scroll convention follows {@link Scroller}: {@code scrollY > 0} means
 * content is pushed up (older entries become visible). "Scroll to bottom"
 * means {@code scrollY = maxScrollY} so the newest entries sit at the
 * visible bottom edge.
 */
public final class V2Log extends BasePopup {

    // -- Constants ------------------------------------------------------------
    private static final float LINE_PAD_L   =  8f;
    private static final float BADGE_R      =  3f;   // radius of the priority dot
    private static final float BADGE_COL_W  = 12f;   // horizontal space reserved for dot
    private static final float HEADER_H     = 36f;
    private static final float FILTER_H     = 28f;
    private static final float FILTER_GAP   =  4f;
    private static final Color LOW_COLOR    = new Color(0.4f, 0.4f, 0.4f, 1f);

    // -- State -----------------------------------------------------------------
    // Layout rects - recomputed each frame while open.
    private final Rect headerRect  = new Rect();
    private final Rect closeBtn    = new Rect();
    private final Rect filterLow   = new Rect();
    private final Rect filterNon   = new Rect();
    private final Rect bodyRect    = new Rect();
    private final ScrollBand bodyBand = new ScrollBand();

    // Filtered entry cache - rebuilt each frame so filter toggles take effect
    // immediately. Each entry may occupy several physical display lines.
    private final List<LogEntry> visibleLines = new ArrayList<>();

    // -- Constructor -----------------------------------------------------------
    public V2Log(UiCtx ctx) {
        super(ctx);
    }

    // -- V2Popup / public API --------------------------------------------------
    @Override protected String popupId() { return "log"; }

    @Override
    protected void onOpened() {
        scrollToBottom();
    }

    /** Snap the scroll so the most-recent entries are visible. */
    public void scrollToBottom() {
        rebuildLines();
        float totalH = totalPhysicalLines() * ctx.lineH();
        float bodyH  = computeBodyH();
        float max = Math.max(0f, totalH - bodyH);
        bodyBand.scroller.resetTop();
        bodyBand.scroller.onScrolled(max / Math.max(1f, ctx.lineH()), ctx.lineH());
    }

    /** Convenience non-V2Popup render - equivalent to {@link #renderSelf()}.
     *  PlayScreen may call this directly if it doesn't use the V2Stage. */
    public void render() {
        renderSelf();
    }

    // -- Layout ----------------------------------------------------------------
    @Override
    protected void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();

        float winW = vw * 0.90f;
        float winH = vh * 0.85f;
        float winX = (vw - winW) * 0.5f;
        float winY = (vh - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        // Header strip - top of window interior (inside WIN_BORDER inset).
        float inset = UIVars.WIN_BORDER;
        float innerX = winX + inset;
        float innerTop = winY + winH - inset;
        float innerW = winW - 2f * inset;

        headerRect.set(innerX, innerTop - HEADER_H, innerW, HEADER_H);

        // Close button - square at the right end of the header strip.
        float closeSz = HEADER_H;
        closeBtn.set(headerRect.right() - closeSz, headerRect.y, closeSz, closeSz);

        // Filter bar - directly below header.
        float filterY = headerRect.y - FILTER_H - 2f;
        float filterW = (innerW - FILTER_GAP) * 0.5f;
        filterLow.set(innerX,               filterY, filterW, FILTER_H);
        filterNon.set(innerX + filterW + FILTER_GAP, filterY, filterW, FILTER_H);

        // Body - everything below filter bar down to window bottom.
        float bodyTop = filterY - 2f;
        float bodyY   = winY + inset;
        bodyRect.set(innerX, bodyY, innerW, bodyTop - bodyY);
        bodyBand.set(bodyRect.x, bodyRect.y, bodyRect.w, bodyRect.h);
    }

    private float computeBodyH() {
        // Used before layoutRects() has run (e.g. scrollToBottom).
        float vh = ctx.worldH();
        float winH = vh * 0.85f;
        float winY = (vh - winH) * 0.5f;
        float inset = UIVars.WIN_BORDER;
        float innerTop = winY + winH - inset;
        float filterBarBottom = innerTop - HEADER_H - 2f - FILTER_H - 2f;
        return filterBarBottom - (winY + inset);
    }

    // -- Line list -------------------------------------------------------------
    private void rebuildLines() {
        visibleLines.clear();
        List<LogEvent> all = EventLog.all();
        boolean showLow = Settings.showLowPriority();
        boolean showNon = Settings.showNonPlayer();
        float ww = wrapWidth();
        for (LogEvent e : all) {
            if (e.priority == LogEvent.EventPriority.LOW && !showLow) continue;
            if (!e.involvesPlayer && !showNon) continue;
            List<String> wrapped = new ArrayList<>();
            TextDraw.wrap(ctx.fontRegular, e.text, ww, 6, wrapped);
            if (wrapped.isEmpty()) wrapped.add(e.text != null ? e.text : "");
            visibleLines.add(new LogEntry(e, wrapped));
        }
    }

    private void updateScroll() {
        float totalH = totalPhysicalLines() * ctx.lineH();
        bodyBand.update(totalH);
    }

    // -- Shape pass ------------------------------------------------------------
    @Override
    protected void renderShapesPass() {
        rebuildLines();
        updateScroll();
        ctx.applyProjection();
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // Dim overlay.
        s.setColor(0f, 0f, 0f, 0.5f);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());

        // Window chrome.
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Header background.
        s.setColor(UIVars.SLOT_RECESS.r, UIVars.SLOT_RECESS.g,
                   UIVars.SLOT_RECESS.b, 1f);
        s.rect(headerRect.x, headerRect.y, headerRect.w, headerRect.h);

        // Filter buttons.
        drawFilterBtnShape(s, filterLow, Settings.showLowPriority());
        drawFilterBtnShape(s, filterNon, Settings.showNonPlayer());

        // Body background.
        s.setColor(UIVars.SLOT_RECESS.r, UIVars.SLOT_RECESS.g,
                   UIVars.SLOT_RECESS.b, 0.7f);
        s.rect(bodyRect.x, bodyRect.y, bodyRect.w, bodyRect.h);

        // Priority dots for visible lines.
        drawLineBadges(s);

        // Scroll indicator - a thin bar on the right edge of the body when
        // the content is taller than the visible area.
        bodyBand.drawScrollbar(s, totalPhysicalLines() * ctx.lineH());

        s.end();
    }

    private void drawFilterBtnShape(ShapeRenderer s, Rect r, boolean active) {
        // Fill.
        Color fill = active ? UIVars.BTN_PRESSED_BG : UIVars.HUD_BG;
        s.setColor(fill);
        s.rect(r.x, r.y, r.w, r.h);

        // Border (tri-line via Edges using HUD_LINE_W).
        float lw = UIVars.HUD_LINE_W;
        Color outer = active ? UIVars.ACCENT   : UIVars.BORDER_OUTER;
        Color mid   = active ? UIVars.ACCENT   : UIVars.BORDER_MID;
        Color inner = active ? UIVars.ACCENT   : UIVars.BORDER_INNER;
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, lw, outer, mid, inner);
    }

    private void drawLineBadges(ShapeRenderer s) {
        if (visibleLines.isEmpty()) return;
        float contentBottom = bodyRect.y - bodyBand.scroller.scrollY();
        float totalH        = totalPhysicalLines() * ctx.lineH();
        float dotX = bodyRect.x + LINE_PAD_L * 0.5f + BADGE_R;

        // Walk entries from oldest (top) to newest (bottom).
        float cursor = contentBottom + totalH; // starts at content top
        for (LogEntry le : visibleLines) {
            float entryTop = cursor;
            float entryBot = cursor - le.lineCount() * ctx.lineH();
            cursor = entryBot;
            float entryMid = (entryTop + entryBot) * 0.5f;
            if (entryMid + BADGE_R < bodyRect.y)     continue;
            if (entryMid - BADGE_R > bodyRect.top()) continue;
            s.setColor(le.event.priority == LogEvent.EventPriority.HIGH
                    ? UIVars.ACCENT : LOW_COLOR);
            s.circle(dotX, entryMid, BADGE_R, 8);
        }
    }

    // -- Text pass -------------------------------------------------------------
    @Override
    protected void renderTextPass() {
        ctx.batch.begin();

        // Header label.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.log.title"),
                headerRect.cx(),
                headerRect.top() - 2f);

        // Close button "x".
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                TextCatalog.get("ui.common.close"),
                closeBtn.cx(),
                closeBtn.top() - 2f);

        // Filter button labels.
        drawFilterLabel(TextCatalog.get("ui.log.lowPriority"), filterLow, Settings.showLowPriority());
        drawFilterLabel(TextCatalog.get("ui.log.nonPlayer"),  filterNon, Settings.showNonPlayer());

        // Log lines.
        bodyBand.clip(ctx, this::drawLogLines);

        ctx.batch.end();
    }

    private void drawFilterLabel(String label, Rect r, boolean active) {
        Color col = active ? UIVars.ACCENT : UIVars.TEXT_DIM;
        TextDraw.centre(ctx, ctx.fontRegular, col,
                label, r.cx(), r.top() - (r.h - ctx.fontRegular.getCapHeight()) * 0.5f);
    }

    private void drawLogLines() {
        if (visibleLines.isEmpty()) return;
        float contentBottom = bodyRect.y - bodyBand.scroller.scrollY();
        float totalH        = totalPhysicalLines() * ctx.lineH();
        float textX = bodyRect.x + LINE_PAD_L + BADGE_COL_W;

        // Walk entries from oldest (top of content) to newest (bottom).
        float cursor = contentBottom + totalH;
        for (LogEntry le : visibleLines) {
            float entryTop = cursor;
            cursor -= le.lineCount() * ctx.lineH();
            // Skip entries entirely outside the body.
            if (entryTop <= bodyRect.y)     break;
            if (cursor   >= bodyRect.top()) continue;
            Color col = lineColor(le.event);
            for (int j = 0; j < le.lines.size(); j++) {
                float lineTop = entryTop - j * ctx.lineH();
                if (lineTop <= bodyRect.y)      break;
                if (lineTop >  bodyRect.top() + ctx.lineH()) continue;
                TextDraw.left(ctx, ctx.fontRegular, col, le.lines.get(j), textX, lineTop);
            }
        }
    }

    private Color lineColor(LogEvent e) {
        if (e.priority == LogEvent.EventPriority.HIGH) {
            return e.involvesPlayer ? UIVars.TEXT_BODY : UIVars.TEXT_DIM;
        }
        return LOW_COLOR;
    }

    // -- Input processor -------------------------------------------------------
    public InputProcessor input() {
        return new InputAdapter() {

            // Note which button was hit on touch-down; fire action on touch-up.
            private boolean maybeClose, maybeLow, maybeNon;
            private boolean draggingBody;
            private boolean maybeOutside;

            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                maybeOutside = !window.contains(vx, vy);
                maybeClose = closeBtn.contains(vx, vy);
                maybeLow   = filterLow.contains(vx, vy);
                maybeNon   = filterNon.contains(vx, vy);
                draggingBody = bodyRect.contains(vx, vy);
                if (draggingBody) {
                    bodyBand.scroller.onTouchDown(vy);
                }
                return true;
            }

            @Override
            public boolean touchDragged(int sx, int sy, int pointer) {
                if (!isOpen()) return false;
                float vy = ctx.unprojectY(sx, sy);
                if (draggingBody) {
                    boolean nowDragging = bodyBand.scroller.onTouchDragged(vy);
                    if (nowDragging) {
                        // Clear button intents once classified as a drag.
                        maybeClose = false;
                        maybeLow   = false;
                        maybeNon   = false;
                    }
                }
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                bodyBand.scroller.onTouchUp();
                if (maybeOutside && !window.contains(vx, vy)) {
                    close();
                } else if (maybeClose && closeBtn.contains(vx, vy)) {
                    close();
                } else if (maybeLow && filterLow.contains(vx, vy)) {
                    Settings.setShowLowPriority(!Settings.showLowPriority());
                    scrollToBottom();
                } else if (maybeNon && filterNon.contains(vx, vy)) {
                    Settings.setShowNonPlayer(!Settings.showNonPlayer());
                    scrollToBottom();
                }
                maybeOutside = false;
                maybeClose   = false;
                maybeLow     = false;
                maybeNon     = false;
                draggingBody = false;
                return true;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (!isOpen()) return false;
                bodyBand.scrolled(amountY);
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!isOpen()) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    close();
                    return true;
                }
                return false;
            }
        };
    }

    // -- Internal helpers ------------------------------------------------------

    /** Max usable text width for a log line - text starts after badge column. */
    private float wrapWidth() {
        float winW  = ctx.worldW() * 0.90f;
        float inset = UIVars.WIN_BORDER;
        return winW - 2f * inset - LINE_PAD_L - BADGE_COL_W;
    }

    /** Total number of physical display lines across all visible entries. */
    private int totalPhysicalLines() {
        int n = 0;
        for (LogEntry le : visibleLines) n += le.lineCount();
        return n;
    }

    /** One log entry together with its pre-wrapped display lines. */
    private static final class LogEntry {
        final LogEvent   event;
        final java.util.List<String> lines;

        LogEntry(LogEvent event, java.util.List<String> lines) {
            this.event = event;
            this.lines = lines;
        }

        int lineCount() { return Math.max(1, lines.size()); }
    }
}
