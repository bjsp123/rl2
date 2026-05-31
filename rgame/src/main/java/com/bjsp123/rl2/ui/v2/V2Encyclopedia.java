package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Buff.BuffType;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.GemSprites;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.MobSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 encyclopedia popup - modal window with category tabs across the top,
 * a scrolling list of (icon, name) entries, and a detail panel that
 * replaces the list when a row is tapped. Same list/info-only rule that
 * the rest of the V2 chrome follows: a window is one or the other, never
 * both.
 *
 * <p>Tap a list row -> detail view with the entry's icon, name, and
 * description. Tap outside the panel or press Back / ESC -> return to the
 * list. From the list, ESC closes the popup.
 */
public final class V2Encyclopedia implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private enum Cat { ITEMS, CREATURES, BUFFS_PERKS, GEMS, TERRAIN, GUIDE }

    /** Map each tab to a glyph in the shared UI icon sheet. GEMS has no
     *  dedicated icon yet - falls back to OTHER. */
    private static com.bjsp123.rl2.world.render.IconSprites.Icon tabIcon(Cat c) {
        return switch (c) {
            case ITEMS       -> com.bjsp123.rl2.world.render.IconSprites.Icon.ITEMS;
            case CREATURES   -> com.bjsp123.rl2.world.render.IconSprites.Icon.MOBS;
            case BUFFS_PERKS -> com.bjsp123.rl2.world.render.IconSprites.Icon.PERKS;
            case GEMS        -> com.bjsp123.rl2.world.render.IconSprites.Icon.GEMS;
            case TERRAIN     -> com.bjsp123.rl2.world.render.IconSprites.Icon.TERRAIN;
            case GUIDE       -> com.bjsp123.rl2.world.render.IconSprites.Icon.BOOK;
        };
    }

    private final UiCtx ctx;
    private boolean open;
    private Cat currentTab = Cat.ITEMS;
    private final Rect window = new Rect();
    private final Rect[] tabRects = new Rect[Cat.values().length];

    private final boolean[] tabPressed = new boolean[Cat.values().length];

    /** All entries pre-built per category - static reference data. */
    private final java.util.EnumMap<Cat, List<Entry>> entries
            = new java.util.EnumMap<>(Cat.class);

    /** Per-row hit rects for the active tab - rebuilt every frame from
     *  the list of entries that fit inside {@link #window}. */
    private final List<Rect> rowRects = new ArrayList<>();
    private final List<Entry> rowEntries = new ArrayList<>();
    /** Index in {@link #rowEntries} (NOT in the master entry list) of the
     *  row currently held, or -1. */
    private int rowPressed = -1;

    /** List scroll state - clamps {@code scrollY} against total content
     *  height each frame; tab switches reset it to the top. */
    private final Scroller scroller = new Scroller();

    /** Visible band for the scrolling list, stored from layoutRects so
     *  both render passes can apply a scissor clip to the row drawing.
     *  Coords are virtual pixels; {@link #renderShapesPass} and
     *  {@link #renderTextPass} convert to screen-space via
     *  {@link com.badlogic.gdx.utils.viewport.Viewport#calculateScissors}. */
    private final Rect listBand = new Rect();
    private final Rectangle scissorIn = new Rectangle();
    private final Rectangle scissorOut = new Rectangle();

    /** Currently-selected entry - non-null when the detail panel is up. */
    private Entry selected;
    /** Standard back button - viewport-anchored bottom-right, shown only
     *  while a detail panel is up. Replaces the V1 inline back-button
     *  rect at the bottom-left of the detail window. Lazily built when
     *  the detail panel opens; reset to {@code null} on the list view. */
    private BackBtn detailBack;
    /** Square frame around the detail-page sprite. Computed in
     *  {@link #layoutRects()} so both render passes (frame chrome in the
     *  shape pass, aspect-fit sprite in the text pass) read the same rect. */
    private final Rect detailIconFrame = new Rect();
    private TextDraw.TextBlock detailNameBlock =
            TextDraw.block(null, "", 1f, 0, 0f);

    /** Pre-wrapped flavor / details lines for the current detail page -
     *  populated during {@link #layoutRects()} so the shape pass (which
     *  paints the divider rule) and the text pass (which paints the lines)
     *  agree on the layout. */
    private final List<String> detailFlavorLines  = new ArrayList<>();
    private final List<String> detailDetailsLines = new ArrayList<>();
    /** Y of the horizontal divider rule between flavor and details (virtual
     *  pixels), in CONTENT space (before scroll offset is applied), or
     *  {@code Float.NaN} when one half is empty and no rule is drawn. */
    private float detailDividerY = Float.NaN;
    /** Scrollable body region for the detail page - bounded by the icon
     *  frame at the top and the window's bottom edge. Content longer than
     *  the band scrolls vertically, with up/down arrow indicators at the
     *  edges. */
    private final Rect detailBodyBand = new Rect();
    /** Scroll state for the detail body. Reset to top whenever the
     *  selected entry changes (entering a new detail page). */
    private final Scroller detailScroller = new Scroller();
    /** Tracks which entry the {@link #detailScroller} is calibrated for so
     *  it resets to the top on entry change. {@code null} for the list
     *  view. */
    private Entry detailScrollerFor;

    public V2Encyclopedia(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < tabRects.length; i++) tabRects[i] = new Rect();
    }

    private void ensureEntries() {
        if (!entries.isEmpty()) return;
        entries.put(Cat.ITEMS,        buildItemEntries());
        entries.put(Cat.CREATURES,    buildCreatureEntries());
        entries.put(Cat.BUFFS_PERKS,  buildBuffsAndPerksEntries());
        entries.put(Cat.GEMS,         buildGemEntries());
        entries.put(Cat.TERRAIN,      buildTerrainEntries());
        entries.put(Cat.GUIDE,        buildGuideEntries());
    }

    /** {@code true} when the encyclopaedia was opened with a registered
     *  back-stack entry on {@link UiCtx#stack} - used to discriminate
     *  between "list-internal" detail back (returns to list view) and
     *  "popup-stack" detail back (closes encyclopaedia, restores the
     *  caller). */
    private boolean openedFromStack;

    public boolean isOpen() { return open; }
    public void toggle() { open = !open; if (open) ensureEntries(); openedFromStack = false; }
    public void close()  {
        open = false;
        selected = null;
        detailBack = null;
        openedFromStack = false;
    }

    @Override
    public void renderSelf() {
        if (!open) return;
        layoutRects();
        renderShapesPass();
        renderTextPass();
        if (detailBack != null) detailBack.draw(ctx);
    }

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(360f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(UIVars.VIRTUAL_H - 100f, vh - 100f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        if (selected == null) {
            // List view layout - tab strip + row hit rects.
            float pad = 12f;
            float tabH = 32f;
            float tabGap = 4f;
            float innerW = winW - 2 * pad;
            float tabW = (innerW - (tabRects.length - 1) * tabGap) / tabRects.length;
            // Reserve a header band sized to the live header-font cap height
            // (which scales with UiFontScale). The tab strip sits below
            // that band so a 1.5x/2x font scale doesn't bleed the title
            // text into the tabs.
            float headerBand = ctx.headerLineH() + ctx.lineH();
            float tabsY = window.top() - pad - tabH - headerBand;
            for (int i = 0; i < tabRects.length; i++) {
                tabRects[i].set(window.x + pad + i * (tabW + tabGap),
                        tabsY, tabW, tabH);
            }

            // Build per-row hit rects for whatever fits below the tab strip.
            // Row height accommodates a 32x32 icon cell so every list row
            // shows its sprite at a uniform 32 px regardless of source size
            // (small buff icons get scaled UP, big mob sprites get scaled
            // DOWN - same fixed cell either way).
            //
            // The list scrolls - {@link #scrollY} shifts the rendered rows
            // upward when positive, exposing entries from the bottom of the
            // master list. Each row's offset y is derived from its index
            // and the current scroll, then clipped against the visible
            // band [visibleBottom, visibleTop]. Rows entirely outside the
            // band aren't added to {@link #rowRects}, so hit-tests stay
            // bounded to whatever's actually on screen.
            rowRects.clear();
            rowEntries.clear();
            float left = window.x + 14f;
            float rowW = winW - 28f;
            float rowH = 84f;
            float visibleTop    = tabRects[0].y - 6f;
            float visibleBottom = window.y + 14f;
            float visibleH      = visibleTop - visibleBottom;
            List<Entry> list = entries.getOrDefault(currentTab, new ArrayList<>());

            float totalH = list.size() * rowH;
            scroller.setMaxScroll(totalH - visibleH);

            // Stash the visible band so both render passes can scissor-clip
            // the row drawing - scissor lets partially-visible rows render
            // at the edges instead of popping in/out.
            listBand.set(left, visibleBottom, rowW, visibleH);

            for (int i = 0; i < list.size(); i++) {
                float yTop = visibleTop - i * rowH + scroller.scrollY();
                if (yTop <= visibleBottom) break;            // entirely off bottom
                if (yTop - rowH >= visibleTop) continue;     // entirely off top
                rowRects.add(new Rect(left, yTop - rowH, rowW, rowH));
                rowEntries.add(list.get(i));
            }
        } else {
            // Detail-view back affordance - standard viewport-anchored
            // BackBtn so the encyclopaedia matches every other V2 screen.
            // Built lazily so the list view (selected == null) doesn't
            // even create the rect.
            if (detailBack == null) {
                // When opened via openTo(id, onClose) the detail page has
                // no list view above it on the popup stack - back goes
                // directly to whatever opened the encyclopaedia (inventory,
                // look, character stats). Otherwise (encyclopaedia opened
                // from the HUD burger), back drops to the list view.
                detailBack = new BackBtn(ctx, () -> {
                    if (openedFromStack) {
                        // Close the encyclopaedia AND run the back-stack
                        // entry pushed by whatever opened us.
                        close();
                        ctx.stack.back();
                    } else {
                        // List -> detail -> list internal navigation.
                        selected = null;
                    }
                });
            }

            // Sprite frame - sized to 2x the source's largest dimension
            // (per "graphics shown double size in info pages"), with a 64-px
            // floor so tiny buff icons still get a readable frame. Centred
            // horizontally; sits below the title row. The aspect-fit sprite
            // renders inside this frame on the text pass.
            int srcMax = 32;
            if (selected.icon != null) {
                srcMax = Math.max(selected.icon.getRegionWidth(),
                                  selected.icon.getRegionHeight());
            }
            detailNameBlock = TextDraw.block(ctx.fontHeader, selected.name,
                    window.w - 28f, 2, ctx.headerLineH());
            float titleTop = window.top() - ctx.headerLineH();
            float frameSz = Math.max(64f, srcMax * 2f);
            float frameX  = window.cx() - frameSz * 0.5f;
            float frameY  = titleTop - detailNameBlock.height() - 10f - frameSz;
            detailIconFrame.set(frameX, frameY, frameSz, frameSz);

            // Pre-wrap the flavor + details bodies - no truncation cap, the
            // body region scrolls when the content is taller than the
            // visible band. Lines and divider y are shared by the shape pass
            // (which draws the rule) and the text pass (which paints the
            // lines), with the body's scrollY offset applied at render time.
            float lineH = ctx.lineH();
            float bodyTop    = detailIconFrame.y - 24f;
            float bodyBottom = window.y + 16f;
            float visibleH   = bodyTop - bodyBottom;
            detailBodyBand.set(window.x + 14f, bodyBottom,
                    window.w - 28f, visibleH);
            float bodyW = window.w - 28f;
            detailFlavorLines.clear();
            detailDetailsLines.clear();
            TextDraw.wrap(ctx.fontRegular, selected.flavor, bodyW,
                    Integer.MAX_VALUE, detailFlavorLines);
            TextDraw.wrap(ctx.fontRegular, selected.details, bodyW,
                    Integer.MAX_VALUE, detailDetailsLines);
            // A divider rule is drawn whenever both halves have content;
            // otherwise the details just continue from the flavor's last
            // line. The "2 line-slots" gap sits in content space so the
            // rule scrolls with the body.
            boolean hasRule = !detailFlavorLines.isEmpty()
                    && !detailDetailsLines.isEmpty();
            int gapLines = hasRule ? 2 : 0;
            int totalLines = detailFlavorLines.size() + gapLines
                    + detailDetailsLines.size();
            float totalContentH = totalLines * lineH;
            detailDividerY = hasRule
                    ? bodyTop - detailFlavorLines.size() * lineH - 6f
                    : Float.NaN;
            // Reset scroll position when entering a new entry's detail page
            // so the user lands at the top, not wherever the previous
            // entry's scroller happened to be.
            if (detailScrollerFor != selected) {
                detailScroller.resetTop();
                detailScrollerFor = selected;
            }
            detailScroller.setMaxScroll(
                    Math.max(0f, totalContentH - visibleH));
        }
    }

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        if (selected == null) {
            // Tabs.
            for (int i = 0; i < tabRects.length; i++) {
                Rect r = tabRects[i];
                boolean active  = Cat.values()[i] == currentTab;
                boolean pressed = tabPressed[i];
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
            // Scissor-clip the row drawing to the visible band so partial
            // rows at the top / bottom edges render smoothly instead of
            // popping in/out one full row at a time. ShapeRenderer needs
            // a flush before glScissor changes take effect.
            s.flush();
            if (pushListScissor()) {
                // Row press highlight.
                if (rowPressed >= 0 && rowPressed < rowRects.size()) {
                    Rect rr = rowRects.get(rowPressed);
                    s.setColor(UIVars.BTN_PRESSED_BG);
                    s.rect(rr.x, rr.y, rr.w, rr.h);
                }
                // Icon frames - light-warm-grey backdrop + thin tri-line
                // border around the 32x32 cell that holds each row's
                // sprite. Sprite itself paints in the text pass on top.
                for (Rect rr : rowRects) {
                    float fSz = 80f;
                    float fx  = rr.x + 2f;
                    float fy  = rr.y + (rr.h - fSz) * 0.5f;
                    Edges.drawTriLine(s, fx, fy, fSz, fSz, UIVars.HUD_LINE_W);
                    s.setColor(UIVars.ICON_FRAME_BG);
                    s.rect(fx + UIVars.HUD_BORDER, fy + UIVars.HUD_BORDER,
                            fSz - 2 * UIVars.HUD_BORDER, fSz - 2 * UIVars.HUD_BORDER);
                }
                s.flush();
                ScissorStack.popScissors();
            }
        } else {
            // Sprite frame chrome - light-warm-grey backdrop + tri-line
            // border. Sprite itself is painted aspect-fit in the text pass.
            Rect f = detailIconFrame;
            Edges.drawTriLine(s, f.x, f.y, f.w, f.h, UIVars.HUD_LINE_W);
            s.setColor(UIVars.ICON_FRAME_BG);
            s.rect(f.x + UIVars.HUD_BORDER, f.y + UIVars.HUD_BORDER,
                    f.w - 2 * UIVars.HUD_BORDER, f.h - 2 * UIVars.HUD_BORDER);

            // Body region - the divider rule sits in content space and
            // scrolls with the body, so it's clipped to the scissor band
            // along with the text lines themselves. The shape pass only
            // draws the rule; the band's chrome (no border, just the
            // scissor) lives entirely inside the window.
            s.flush();
            if (pushDetailBodyScissor()) {
                if (!Float.isNaN(detailDividerY)) {
                    s.setColor(UIVars.BORDER_MID);
                    s.rect(detailBodyBand.x,
                            detailDividerY + detailScroller.scrollY(),
                            detailBodyBand.w, 1f);
                }
                s.flush();
                ScissorStack.popScissors();
            }
            // Up / down scroll-arrow indicators - drawn outside the
            // scissor so they're never clipped. Only show when there is
            // actually content to reveal in that direction.
            float scroll = detailScroller.scrollY();
            float maxScroll = scrollMax();
            if (scroll > 0.5f) {
                // Sits in the gap between the icon frame and the body
                // band's top edge - not overlapping the first line of
                // text inside the band.
                drawScrollArrow(s,
                        detailBodyBand.cx(),
                        detailBodyBand.top() + 6f,
                        true);
            }
            if (scroll < maxScroll - 0.5f) {
                drawScrollArrow(s,
                        detailBodyBand.cx(),
                        detailBodyBand.y - 6f,
                        false);
            }
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Push a scissor onto the GL stack matching {@link #detailBodyBand}.
     *  Returns {@code true} when the push succeeded - caller must
     *  {@code popScissors} in that case. {@code false} means the band is
     *  empty / off-screen and no clip was applied. */
    private boolean pushDetailBodyScissor() {
        scissorIn.set(detailBodyBand.x, detailBodyBand.y,
                detailBodyBand.w, detailBodyBand.h);
        ctx.viewport.calculateScissors(
                ctx.batch.getTransformMatrix(), scissorIn, scissorOut);
        return ScissorStack.pushScissors(scissorOut);
    }

    /** Approx max scroll for the detail body. {@link Scroller} clamps the
     *  active scroll into {@code [0, maxScrollY]} but doesn't expose the
     *  cap directly, so we recompute it from the band height + content
     *  metrics here for the arrow-visibility check. */
    private float scrollMax() {
        float lineH = ctx.lineH();
        int gapLines = (!detailFlavorLines.isEmpty()
                && !detailDetailsLines.isEmpty()) ? 2 : 0;
        float totalH = (detailFlavorLines.size() + gapLines
                + detailDetailsLines.size()) * lineH;
        return Math.max(0f, totalH - detailBodyBand.h);
    }

    /** Filled triangle pointing up ({@code up = true}) or down. Centred at
     *  ({@code cx}, {@code cy}), sized to fit the body band's edge gutter.
     *  Used as the scroll-affordance hint at the top / bottom of the
     *  scrollable detail body. */
    private static void drawScrollArrow(ShapeRenderer s, float cx, float cy,
                                        boolean up) {
        float w  = 14f;
        float h  = 8f;
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        s.setColor(UIVars.ACCENT);
        if (up) {
            s.triangle(cx - halfW, cy - halfH,
                       cx + halfW, cy - halfH,
                       cx,         cy + halfH);
        } else {
            s.triangle(cx - halfW, cy + halfH,
                       cx + halfW, cy + halfH,
                       cx,         cy - halfH);
        }
    }

    private void renderTextPass() {
        ctx.batch.begin();

        if (selected == null) {
            TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                    TextCatalog.get("ui.encyclopedia.title"),
                    window.cx(), window.top() - ctx.headerLineH());

            // Tabs render as icons - same source sheet as the Settings
            // tab strip. {@link #tabIcon} maps each Cat to a sheet column.
            for (int i = 0; i < tabRects.length; i++) {
                boolean active = Cat.values()[i] == currentTab;
                Rect r = tabRects[i];
                var region = com.bjsp123.rl2.world.render.IconSprites
                        .regionFor(tabIcon(Cat.values()[i]));
                if (region == null) continue;
                ctx.batch.setColor(active ? UIVars.ACCENT : UIVars.TEXT_BODY);
                float sz = Math.min(r.w, r.h) * 0.6f;
                ctx.batch.draw(region,
                        r.cx() - sz * 0.5f, r.cy() - sz * 0.5f, sz, sz);
                ctx.batch.setColor(1f, 1f, 1f, 1f);
            }

            // Same scissor clip as the shape pass so sprites and names at
            // the top / bottom edges render smoothly across the band.
            ctx.batch.flush();
            if (pushListScissor()) {
                for (int i = 0; i < rowRects.size(); i++) {
                    Rect r = rowRects.get(i);
                    Entry e = rowEntries.get(i);
                    // Fixed 80x80 frame - every list row reserves the same
                    // cell regardless of source sprite size. Sprite is
                    // aspect-fit + outlined inside the frame.
                    float fSz = 80f;
                    float fx  = r.x + 2f;
                    float fy  = r.y + (r.h - fSz) * 0.5f;
                    if (e.icon != null) {
                        drawAspectFit(e.icon, fx, fy, fSz, fSz);
                    }
                    TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                            e.name, fx + fSz + 8f, r.y + r.h - 9f,
                            r.right() - (fx + fSz + 12f));
                }
                ctx.batch.flush();
                ScissorStack.popScissors();
            }
        } else {
            TextDraw.wrappedCentre(ctx, ctx.fontHeader, UIVars.ACCENT,
                    detailNameBlock, window.cx(), window.top() - ctx.headerLineH());

            // Detail sprite - aspect-fit inside the frame computed in
            // layoutRects. Frame is 2x source size with a 64-px floor so
            // tiny buff icons still read clearly; the sprite preserves its
            // native aspect ratio.
            if (selected.icon != null) {
                drawAspectFit(selected.icon,
                        detailIconFrame.x, detailIconFrame.y,
                        detailIconFrame.w, detailIconFrame.h);
            }

            // Body - flavor (bright) on top, optional horizontal rule, then
            // mechanical details (dim) below. Lines + divider Y are
            // pre-computed in layoutRects in CONTENT coords; the body
            // region scissors out anything outside the visible band, and
            // the per-line y is offset by the active scrollY.
            float top    = detailIconFrame.y - 24f;
            float lineH  = ctx.lineH();
            float scroll = detailScroller.scrollY();
            ctx.batch.flush();
            if (!pushDetailBodyScissor()) {
                ctx.batch.end();
                return;
            }
            int   line  = 0;
            for (String s : detailFlavorLines) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        s, window.x + UIVars.PAD_CONTENT,
                        top - line * lineH + scroll);
                line++;
            }
            // Skip a couple of slots when a rule is drawn between the halves
            // so the rule doesn't crowd the surrounding text.
            if (!Float.isNaN(detailDividerY)) line += 2;
            for (String s : detailDetailsLines) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        s, window.x + UIVars.PAD_CONTENT,
                        top - line * lineH + scroll);
                line++;
            }
            ctx.batch.flush();
            ScissorStack.popScissors();

        }

        ctx.batch.end();
    }

    /** Push a scissor onto the GL stack matching {@link #listBand}. Returns
     *  {@code true} when the push succeeded - caller must {@code popScissors}
     *  in that case. {@code false} means the band is empty / off-screen and
     *  no clip was applied (caller should skip the wrapped drawing). */
    private boolean pushListScissor() {
        scissorIn.set(listBand.x, listBand.y, listBand.w, listBand.h);
        ctx.viewport.calculateScissors(
                ctx.batch.getTransformMatrix(), scissorIn, scissorOut);
        return ScissorStack.pushScissors(scissorOut);
    }

    /** Draw {@code region} centred inside the {@code (x, y, w, h)} box,
     *  preserving its native aspect ratio. Outline uses the shared
     *  {@link com.bjsp123.rl2.world.render.OutlineRenderer#drawTaps} so width,
     *  darkness, and tap-alpha correction match the world renderer exactly. */
    private void drawAspectFit(TextureRegion region,
                               float x, float y, float w, float h) {
        int srcW = region.getRegionWidth();
        int srcH = region.getRegionHeight();
        if (srcW <= 0 || srcH <= 0) return;
        float scale = Math.min(w / srcW, h / srcH);
        float drawW = srcW * scale;
        float drawH = srcH * scale;
        float drawX = x + (w - drawW) * 0.5f;
        float drawY = y + (h - drawH) * 0.5f;
        com.bjsp123.rl2.world.render.OutlineRenderer.drawTaps(ctx.batch, region, drawX, drawY, drawW, drawH);
        ctx.batch.draw(region, drawX, drawY, drawW, drawH);
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                if (selected != null) {
                    if (detailBack != null && detailBack.hit(vx, vy)) {
                        detailBack.pressed = true;
                        return true;
                    }
                    // Tap inside the scrollable body band arms the
                    // scroller so a drag from here scrolls the body.
                    // The tap itself is consumed (no row click).
                    if (detailBodyBand.contains(vx, vy)) {
                        detailScroller.onTouchDown(vy);
                        return true;
                    }
                    // Tap outside the detail window acts like Back -
                    // returns to the list view, OR closes (and runs the
                    // back-stack callback) when the encyclopaedia was
                    // opened directly to this entry.
                    if (!window.contains(vx, vy)) {
                        if (openedFromStack) {
                            close();
                            ctx.stack.back();
                        } else {
                            selected = null;
                        }
                    }
                    return true;
                }

                for (int i = 0; i < tabRects.length; i++) {
                    if (tabRects[i].contains(vx, vy)) {
                        tabPressed[i] = true;
                        return true;
                    }
                }
                for (int i = 0; i < rowRects.size(); i++) {
                    if (rowRects.get(i).contains(vx, vy)) {
                        rowPressed = i;
                        scroller.onTouchDown(vy);
                        return true;
                    }
                }
                if (!window.contains(vx, vy)) { close(); return true; }
                // Touch landed inside the window but missed every row -
                // arm the scroller anyway so a drag from this point still
                // scrolls the list.
                scroller.onTouchDown(vy);
                return true;
            }

            @Override
            public boolean touchDragged(int sx, int sy, int pointer) {
                if (!open) return false;
                float vy = ctx.unprojectY(sx, sy);
                if (selected != null) {
                    // Detail body - scroll on drag.
                    detailScroller.onTouchDragged(vy);
                    return true;
                }
                if (scroller.onTouchDragged(vy)) {
                    // Drag classified - suppress any pending tap so release
                    // doesn't fire a row click or tab switch.
                    rowPressed = -1;
                    for (int i = 0; i < tabPressed.length; i++) tabPressed[i] = false;
                    return true;
                }
                return false;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (!open) return false;
                if (selected != null) {
                    detailScroller.onScrolled(amountY);
                    return true;
                }
                scroller.onScrolled(amountY);
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Detail body drag release - clear scroller state without
                // firing any tap action.
                if (selected != null && detailScroller.isDragging()) {
                    detailScroller.onTouchUp();
                    return true;
                }

                // Drag suppresses the tap action.
                if (scroller.isDragging()) {
                    scroller.onTouchUp();
                    rowPressed = -1;
                    return true;
                }

                if (detailBack != null && detailBack.pressed) {
                    detailBack.pressed = false;
                    if (detailBack.hit(vx, vy)) detailBack.click();
                    return true;
                }

                for (int i = 0; i < tabRects.length; i++) {
                    if (tabPressed[i]) {
                        tabPressed[i] = false;
                        if (tabRects[i].contains(vx, vy)
                                && currentTab != Cat.values()[i]) {
                            currentTab = Cat.values()[i];
                            scroller.resetTop();
                        }
                        return true;
                    }
                }
                if (rowPressed >= 0 && rowPressed < rowRects.size()) {
                    int idx = rowPressed;
                    rowPressed = -1;
                    if (rowRects.get(idx).contains(vx, vy)) {
                        selected = rowEntries.get(idx);
                    }
                    return true;
                }
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    if (selected != null && !openedFromStack) {
                        selected = null;            // detail -> list
                    } else if (openedFromStack) {
                        close();
                        ctx.stack.back();           // close + restore caller
                    } else {
                        close();                    // root encyclopaedia
                    }
                    return true;
                }
                return false;
            }
        };
    }

    // -- Entry builders -------------------------------------------------------

    private static List<Entry> buildItemEntries() {
        List<Entry> out = new ArrayList<>();
        for (String type : com.bjsp123.rl2.logic.Registries.itemTypes()) {
            Item it = ItemFactory.build(type);
            String name = it.name != null ? it.name : type;
            out.add(new Entry(type, ItemSprites.regionFor(it), name,
                    com.bjsp123.rl2.ui.ItemLore.describeFlavor(it),
                    com.bjsp123.rl2.ui.ItemLore.describeDetails(it)));
        }
        return out;
    }

    private static List<Entry> buildCreatureEntries() {
        List<Entry> out = new ArrayList<>();
        Point dummy = new Point(0, 0);
        for (String t : com.bjsp123.rl2.logic.Registries.mobTypes()) {
            Mob m = MobFactory.spawn(t, dummy);
            if (m == null) continue;
            String name = m.name != null && !m.name.isEmpty() ? m.name : t;
            out.add(new Entry(t, MobSprites.regionFor(m), name,
                    com.bjsp123.rl2.ui.MobLore.describeFlavor(m),
                    com.bjsp123.rl2.ui.MobLore.describeDetails(m)));
        }
        return out;
    }

    private static List<Entry> buildBuffsAndPerksEntries() {
        List<Entry> out = new ArrayList<>();
        for (BuffType t : BuffType.values()) {
            String name = BuffSystem.displayName(t);
            StringBuilder body = new StringBuilder(BuffSystem.description(t));
            // Append a mechanical-details line when one exists for this buff.
            // Optional - falls back to just the description for buffs that
            // don't have a details string yet.
            String details = TextCatalog.getOrDefault("buff." + t.name() + ".details", "");
            if (!details.isEmpty()) {
                body.append("\n\n").append(details);
            }
            out.add(new Entry(t, BuffIcons.regionFor(t), name, body.toString()));
        }
        for (Perk p : Perk.values()) {
            out.add(new Entry(p, null, p.displayName(), p.description()));
        }
        return out;
    }

    private static List<Entry> buildGemEntries() {
        List<Entry> out = new ArrayList<>();
        for (GemSpecies sp : GemSpecies.values()) {
            String desc = TextCatalog.format("ui.encyclopedia.gemDetails",
                    TextCatalog.vars("theme", sp.theme, "tier", sp.tier));
            out.add(new Entry(sp, GemSprites.regionFor(sp, 5), sp.pretty(), desc));
        }
        return out;
    }

    private static List<Entry> buildTerrainEntries() {
        List<Entry> out = new ArrayList<>();
        for (Tile t : Tile.values()) {
            StringBuilder sb = new StringBuilder();
            // Flavor description first.
            String desc = TextCatalog.getOrDefault("tile." + t.name() + ".description", "");
            if (!desc.isEmpty()) sb.append(desc).append("\n\n");
            // Attribute lines. Each predicate yields a short statement so the
            // player can scan "blocks movement", "flammable", "see-through"
            // etc. at a glance.
            sb.append(TextCatalog.get(t.isFloorLike()
                    ? "terrain.attribute.walkable"
                    : "terrain.attribute.blocksMovement")).append("\n");
            sb.append(TextCatalog.get(t.blocksSight()
                    ? "terrain.attribute.blocksSight"
                    : "terrain.attribute.seeThrough")).append("\n");
            if (t.blocksProjectile() && !t.blocksMovement()) {
                sb.append(TextCatalog.get("terrain.attribute.blocksProjectile")).append("\n");
            }
            if (t.canHoldItem()) {
                sb.append(TextCatalog.get("terrain.attribute.canHoldItem")).append("\n");
            }
            if (t == Tile.FLOOR_WOOD) {
                sb.append(TextCatalog.get("terrain.attribute.flammable")).append("\n");
            }
            if (t == Tile.LAMP || t == Tile.BEACON_ACTIVE) {
                sb.append(TextCatalog.get("terrain.attribute.emitsLight")).append("\n");
            }
            if (t == Tile.CHASM) {
                sb.append(TextCatalog.get("terrain.attribute.flyingOnly")).append("\n");
            }
            if (t == Tile.ONETIME_DOOR) {
                sb.append(TextCatalog.get("terrain.attribute.oneWayBreak")).append("\n");
            }
            if (t == Tile.BEACON_ACTIVE) {
                sb.append(TextCatalog.get("terrain.attribute.teleportTarget")).append("\n");
            }
            // Localized tile name (falls back to enum-lowercase if absent).
            String name = TextCatalog.getOrDefault("tile." + t.name(), t.name().toLowerCase());
            out.add(new Entry(t, null, name, sb.toString().trim()));
        }
        return out;
    }

    private static List<Entry> buildGuideEntries() {
        List<Entry> out = new ArrayList<>();
        Point dummy = new Point(0, 0);
        for (com.bjsp123.rl2.GuideRegistry.HelpPage page : com.bjsp123.rl2.GuideRegistry.pages()) {
            TextureRegion icon = regionForKey(page.imageKey, dummy);
            String flavor  = page.para1;
            StringBuilder details = new StringBuilder();
            if (!page.para2.isEmpty()) details.append(page.para2);
            if (!page.para3.isEmpty()) {
                if (details.length() > 0) details.append("\n\n");
                details.append(page.para3);
            }
            out.add(new Entry(page.title, icon, page.title, flavor, details.toString()));
        }
        return out;
    }

    private static TextureRegion regionForKey(String key, Point dummy) {
        if (key == null || key.isEmpty()) return null;
        if (com.bjsp123.rl2.logic.Registries.itemTypes().contains(key)) {
            Item it = ItemFactory.build(key);
            if (it != null) {
                TextureRegion r = ItemSprites.regionFor(it);
                if (r != null) return r;
            }
        }
        if (com.bjsp123.rl2.logic.Registries.mobTypes().contains(key)) {
            Mob m = MobFactory.spawn(key, dummy);
            if (m != null) return MobSprites.regionFor(m);
        }
        return null;
    }

    /** Open the popup pre-selected to a specific entry - used by inventory's
     *  "?" button to jump from an item-detail popup straight to its
     *  encyclopedia page. {@code id} matches whatever the entry was built
     *  with: item type string (Items), mob type string (Creatures),
     *  {@link BuffType} (Buffs), {@link Perk} (Perks), {@link GemSpecies}
     *  (Gems), {@link Tile} (Terrain). When the id is unmatched, falls
     *  through to opening the category's list view without a selection. */
    public void openTo(Object id) {
        openTo(id, null);
    }

    /** Open variant carrying an "on-close" callback - pushes {@code onClose}
     *  onto the shared {@link UiCtx#stack} so back from this opening
     *  unwinds via the same single window stack screens use. The
     *  caller (V2Inventory, V2Look, V2CharacterStats) supplies a
     *  runnable that re-opens itself, restoring its prior state. */
    public void openTo(Object id, Runnable onClose) {
        ensureEntries();
        open = true;
        selected = null;
        openedFromStack = onClose != null;
        if (onClose != null) ctx.stack.push(onClose);
        if (id == null) return;
        for (Cat c : Cat.values()) {
            for (Entry e : entries.getOrDefault(c, new ArrayList<>())) {
                if (id.equals(e.id)) {
                    currentTab = c;
                    selected = e;
                    return;
                }
            }
        }
    }

    /** One row in a tab's list - id (natural key for {@link #openTo}) +
     *  icon + name + flavor / details. {@code flavor} (description blurb)
     *  renders in bright text, then a horizontal rule, then {@code details}
     *  (mechanical stats) in dimmer text; either may be empty. The list
     *  shows just icon + name. */
    private static final class Entry {
        final Object id;
        final TextureRegion icon;
        final String name;
        final String flavor;
        final String details;
        Entry(Object id, TextureRegion icon, String name,
              String flavor, String details) {
            this.id = id;
            this.icon = icon;
            this.name = name;
            this.flavor  = flavor  == null ? "" : flavor;
            this.details = details == null ? "" : details;
        }
        Entry(Object id, TextureRegion icon, String name, String description) {
            this(id, icon, name, description, "");
        }
    }
}
