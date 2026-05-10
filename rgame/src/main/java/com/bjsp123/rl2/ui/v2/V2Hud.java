package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.ui.hud.ActionBar;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.ui.skin.LogPreferences;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

import java.util.List;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * V2 in-game HUD — built from primitive ShapeRenderer rects + SpriteBatch
 * texture draws. Replaces the scene2d-based {@link com.bjsp123.rl2.ui.hud.HudRenderer}
 * for active gameplay; instantiated and driven directly by {@code PlayScreen}
 * (no scene2d Stage involvement).
 *
 * <p>Layout, in viewport-relative virtual coords:
 * <ul>
 *   <li><b>Top-left</b> — three stacked status bars (HP / XP / Satiety).</li>
 *   <li><b>Top-right</b> — burger button (opens settings / map / encyclopaedia /
 *       return-to-title via dropdown — dropdown not yet wired in this slice).</li>
 *   <li><b>Bottom-left</b> — Look button.</li>
 *   <li><b>Bottom-right</b> — six action quickslots + an inventory button at the
 *       far right, all flush to the bottom-right corner.</li>
 * </ul>
 *
 * <p>Render lifecycle each frame:
 * <ol>
 *   <li>{@code PlayScreen.render()} runs world-rendering passes</li>
 *   <li>{@code v2Hud.update(player, depth, turn, tick)} — fresh game state</li>
 *   <li>{@code v2Hud.render()} — switches projection to V2 camera, runs
 *       a ShapeRenderer pass for backgrounds + bars, then a SpriteBatch
 *       pass for icons, status line, and burger glyph (drawn via shapes again
 *       at the end since they're geometry, not textures)</li>
 *   <li>uiStage (popups) renders on top — popups still come from V1 for now</li>
 * </ol>
 *
 * <p>Input: {@link #input()} returns an {@link InputProcessor} that the
 * PlayScreen multiplexer should slot AFTER the popup stage and BEFORE the
 * world's own input. Any tap on a HUD button consumes the touch and fires
 * the corresponding callback; taps outside the HUD rects fall through.
 */
public final class V2Hud {

    // ── Layout constants (virtual px, design at 400×720) ────────────────────
    private static final float MARGIN       = 8f;
    private static final float BAR_W        = 140f;
    private static final float BAR_H        = 14f;
    private static final float BAR_GAP      = 4f;
    private static final float ACTION_BTN   = 48f;
    private static final float ACTION_GAP   = 4f;
    private static final float INV_BTN_W    = 56f;
    private static final float INV_BTN_H    = 56f;
    private static final float MISC_BTN     = 48f;
    private static final float ICON_PAD     = 6f;

    // ── State ────────────────────────────────────────────────────────────────
    private final UiCtx ctx;

    /** Live player accessor — re-read every frame so a level-transition
     *  doesn't leave the HUD bound to a stale Mob reference. */
    private Supplier<Mob> playerSupplier;
    private ActionBar actionBar;
    private int depth, turn;

    // Callbacks — same shape as the V1 HudRenderer.setOn* setters, so
    // PlayScreen's existing wiring carries over with minimal changes.
    private IntConsumer onActionUse;
    private Runnable    onOpenInventory;
    private Runnable    onLook;
    private Runnable    onPortraitTap;
    private Runnable    onOpenSettings;
    private Runnable    onOpenEncyclopedia;
    private Runnable    onOpenLevelInfo;
    private Runnable    onReturnToTitle;
    private Runnable    onOpenMap;
    /** Tap on a player buff icon → open encyclopedia at that buff. */
    private java.util.function.Consumer<com.bjsp123.rl2.model.Buff.BuffType> onBuffTap;
    public void setOnBuffTap(java.util.function.Consumer<com.bjsp123.rl2.model.Buff.BuffType> fn) {
        this.onBuffTap = fn;
    }

    // ── Hit rects ────────────────────────────────────────────────────────────
    private final Rect portraitRect = new Rect();
    private final Rect hpBarRect    = new Rect();
    private final Rect xpBarRect    = new Rect();
    private final Rect satBarRect   = new Rect();
    private final Rect[] actionRects = new Rect[6];
    private final Rect invRect      = new Rect();
    private final Rect lookRect     = new Rect();
    private final Rect burgerRect   = new Rect();
    private final Rect menuPanelRect = new Rect();
    /** Per-menu-item rects, populated when the burger menu is open. */
    private final Rect[] menuItemRects = new Rect[4];
    /** Per-buff-icon hit rects, rebuilt every frame from the live buff
     *  list. Aligned in index with {@link #buffIconTypes}. */
    private final java.util.List<Rect> buffIconRects = new java.util.ArrayList<>();
    private final java.util.List<com.bjsp123.rl2.model.Buff.BuffType> buffIconTypes
            = new java.util.ArrayList<>();

    // ── Pressed state for visual feedback ───────────────────────────────────
    private final boolean[] actionPressed = new boolean[6];
    private boolean invPressed, lookPressed, burgerPressed;
    private boolean menuOpen;
    private int menuItemPressed = -1;
    /** Index of the buff icon currently being held; -1 when none. */
    private int buffIconPressed = -1;

    public V2Hud(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < actionRects.length;  i++) actionRects[i]  = new Rect();
        for (int i = 0; i < menuItemRects.length; i++) menuItemRects[i] = new Rect();
    }

    // ── Public API (mirrors HudRenderer.setOn* surface) ─────────────────────
    public void setPlayerSupplier(Supplier<Mob> s)  { this.playerSupplier = s; }
    public void setActionBar(ActionBar a)           { this.actionBar = a; }
    public void setOnActionUse(IntConsumer fn)      { this.onActionUse = fn; }
    public void setOnOpenInventory(Runnable fn)     { this.onOpenInventory = fn; }
    public void setOnLook(Runnable fn)              { this.onLook = fn; }
    public void setOnPortraitTap(Runnable fn)       { this.onPortraitTap = fn; }
    public void setOnOpenSettings(Runnable fn)      { this.onOpenSettings = fn; }
    public void setOnOpenEncyclopedia(Runnable fn)  { this.onOpenEncyclopedia = fn; }
    public void setOnOpenLevelInfo(Runnable fn)     { this.onOpenLevelInfo = fn; }
    public void setOnReturnToTitle(Runnable fn)     { this.onReturnToTitle = fn; }
    public void setOnOpenMap(Runnable fn)           { this.onOpenMap = fn; }

    /** Frame state — depth / turn for the status line; player read via
     *  {@link #playerSupplier}. The {@code tick} parameter is accepted for
     *  parity with the legacy HUD's call signature but isn't surfaced in
     *  the V2 status line. */
    public void update(int depth, int turn, int tick) {
        this.depth = depth;
        this.turn  = turn;
    }

    /** True when the burger dropdown is showing — PlayScreen folds this
     *  into its {@code isAnyPopupOpen()} gate so the world doesn't tick
     *  while the player is reading the menu. */
    public boolean isMenuOpen() { return menuOpen; }

    public void render() {
        layoutRects();
        ctx.applyProjection();
        renderShapesPass();
        renderTextPass();
        renderBurgerGlyph();   // tiny shapes pass at the end for the menu lines
    }

    // ── Layout ──────────────────────────────────────────────────────────────
    private void layoutRects() {
        float w = ctx.worldW();
        float h = ctx.worldH();

        // Top-left cluster: portrait on the left, three status bars stacked
        // to its right. Portrait is square; bars take the rest of the row.
        float portraitSz = 40f;
        portraitRect.set(MARGIN, h - MARGIN - portraitSz, portraitSz, portraitSz);

        float barX = portraitRect.right() + 4f;
        float by = h - MARGIN - BAR_H;
        hpBarRect.set(barX, by, BAR_W, BAR_H);
        by -= BAR_H + BAR_GAP;
        xpBarRect.set(barX, by, BAR_W, BAR_H);
        by -= BAR_H + BAR_GAP;
        satBarRect.set(barX, by, BAR_W, BAR_H);

        // Burger at top-right.
        burgerRect.set(w - MARGIN - MISC_BTN, h - MARGIN - MISC_BTN,
                MISC_BTN, MISC_BTN);

        // Look at bottom-left.
        lookRect.set(MARGIN, MARGIN, MISC_BTN, MISC_BTN);

        // Bottom-right strip: action 0..5 left-to-right (so slot 1 reads
        // as the leftmost button, matching the keyboard's number-row),
        // then the inventory button at the far right.
        invRect.set(w - INV_BTN_W, MARGIN, INV_BTN_W, INV_BTN_H);
        float stripW = 6 * ACTION_BTN + 5 * ACTION_GAP;
        float ax = invRect.x - ACTION_GAP - stripW;
        for (int i = 0; i < 6; i++) {
            actionRects[i].set(ax, MARGIN, ACTION_BTN, ACTION_BTN);
            ax += ACTION_GAP + ACTION_BTN;
        }

        // Burger menu — centred on the viewport as a chunky column-of-
        // buttons window. Same shape as the V2Screen menu-screen burger so
        // the in-game pause menu reads identically.
        if (menuOpen) {
            int   nItems  = menuItemRects.length;
            float itemH   = 56f;
            float itemGap = 8f;
            float padX    = 18f;
            float padTop  = 18f;
            float padBot  = 18f;
            float panelW  = Math.min(320f, ctx.worldW() - 24f);
            float panelH  = padTop + padBot + nItems * itemH
                    + (nItems - 1) * itemGap;
            float panelX  = (ctx.worldW() - panelW) * 0.5f;
            float panelY  = (ctx.worldH() - panelH) * 0.5f;
            menuPanelRect.set(panelX, panelY, panelW, panelH);
            for (int i = 0; i < nItems; i++) {
                float iy = panelY + panelH - padTop
                        - (i + 1) * itemH - i * itemGap;
                menuItemRects[i].set(panelX + padX, iy,
                        panelW - 2 * padX, itemH);
            }
        }
    }

    // ── Render passes ───────────────────────────────────────────────────────
    private void renderShapesPass() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        Mob player = currentPlayer();

        // Status bars.
        if (player != null) {
            double maxHp = player.effectiveStats().maxHp;
            float hpFrac = maxHp > 0 ? (float) (player.hp / maxHp) : 0f;
            drawBar(s, hpBarRect, hpFrac, Pal.WARN);

            // XP not modelled yet — empty bar.
            drawBar(s, xpBarRect, 0f, Pal.ACCENT);

            int satMax = com.bjsp123.rl2.logic.GameBalance.STARTING_SATIETY;
            float satFrac = satMax > 0 ? (float) player.satiety / satMax : 0f;
            drawBar(s, satBarRect, satFrac, Pal.BORDER_BRIGHT);
        } else {
            drawBar(s, hpBarRect,  0f, Pal.WARN);
            drawBar(s, xpBarRect,  0f, Pal.ACCENT);
            drawBar(s, satBarRect, 0f, Pal.BORDER_BRIGHT);
        }

        // Buttons — chrome only; icons land in the SpriteBatch pass.
        // Action quickslots + inventory button carry item / chest icons,
        // so they paint with the paler SLOT_BG so the icon stays legible.
        for (int i = 0; i < 6; i++) drawSlotBtn(s, actionRects[i], actionPressed[i]);
        drawSlotBtn(s, invRect, invPressed);
        // Look + burger have no item icon — plain HUD chrome.
        drawBtn(s, lookRect,   lookPressed);
        drawBtn(s, burgerRect, burgerPressed);

        // Burger menu — modal column-of-buttons window centred on the
        // viewport. Dim everything behind it first, then paint the window
        // and each item as a chunky button (tri-line border + warm fill).
        if (menuOpen) {
            s.setColor(0f, 0f, 0f, Pal.DIM_ALPHA);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            Window.drawShape(ctx,
                    menuPanelRect.x, menuPanelRect.y,
                    menuPanelRect.w, menuPanelRect.h);
            for (int i = 0; i < menuItemRects.length; i++) {
                Rect r = menuItemRects[i];
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
                s.setColor(i == menuItemPressed
                        ? UiColors.BTN_PRESSED_BG : UiColors.BTN_BG);
                s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                        r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
            }
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Draw an HP/XP/Satiety bar — tri-line border, mid warm-grey backdrop,
     *  and a fill bar at {@code frac} in {@code fillColor}. */
    private void drawBar(ShapeRenderer s, Rect r, float frac, Color fillColor) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, 1f);
        s.setColor(UiColors.HUD_BG);
        s.rect(r.x + 3, r.y + 3, r.w - 6, r.h - 6);
        if (frac > 0f) {
            float fw = (r.w - 6) * Math.max(0, Math.min(1, frac));
            s.setColor(fillColor);
            s.rect(r.x + 3, r.y + 3, fw, r.h - 6);
        }
    }

    /** Action-quickslot chrome — tri-line border + paler warm-grey fill so
     *  the item icon drawn over it is legible. Used for the bottom-right
     *  action buttons and the inventory button (both carry sprites). */
    private void drawSlotBtn(ShapeRenderer s, Rect r, boolean pressed) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
        s.setColor(pressed ? UiColors.BTN_PRESSED_BG : UiColors.SLOT_BG);
        s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
               r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
    }

    /** Plain HUD button chrome — tri-line border + mid warm-grey fill.
     *  Used for buttons WITHOUT item icons (look button, burger). */
    private void drawBtn(ShapeRenderer s, Rect r, boolean pressed) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
        s.setColor(pressed ? UiColors.BTN_PRESSED_BG : UiColors.HUD_BG);
        s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
               r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
    }

    private void renderTextPass() {
        ctx.batch.begin();

        // Portrait — character class sprite drawn in the top-left cell.
        Mob playerForPortrait = currentPlayer();
        if (playerForPortrait != null && playerForPortrait.characterClass != null) {
            TextureRegion portrait = PortraitSprites.regionFor(
                    playerForPortrait.characterClass);
            if (portrait != null) {
                ctx.batch.draw(portrait,
                        portraitRect.x + 4f, portraitRect.y + 4f,
                        portraitRect.w - 8f, portraitRect.h - 8f);
            }
        }

        // Action button icons — pull from ActionBar.
        if (actionBar != null) {
            for (int i = 0; i < 6; i++) {
                Item it = actionBar.get(i);
                if (it == null) continue;
                TextureRegion region = ItemSprites.regionFor(it);
                if (region == null) continue;
                Rect r = actionRects[i];
                ctx.batch.draw(region,
                        r.x + ICON_PAD, r.y + ICON_PAD,
                        r.w - 2 * ICON_PAD, r.h - 2 * ICON_PAD);
            }
        }

        // Inventory button — chest icon if available, else "Bag" label.
        TextureRegion chest = IconSprites.regionFor(IconSprites.Icon.INV);
        if (chest != null) {
            ctx.batch.draw(chest,
                    invRect.x + ICON_PAD, invRect.y + ICON_PAD,
                    invRect.w - 2 * ICON_PAD, invRect.h - 2 * ICON_PAD);
        } else {
            TextDraw.centre(ctx, ctx.fontRegular, Pal.WHITE, "Bag",
                    invRect.cx(), invRect.y + invRect.h * 0.5f + 4);
        }

        // Look button — magnifier icon.
        TextureRegion look = IconSprites.regionFor(IconSprites.Icon.LOOK);
        if (look != null) {
            ctx.batch.draw(look,
                    lookRect.x + ICON_PAD, lookRect.y + ICON_PAD,
                    lookRect.w - 2 * ICON_PAD, lookRect.h - 2 * ICON_PAD);
        }

        // Status line — under the satiety bar.
        TextDraw.left(ctx, ctx.fontRegular, Pal.DIM,
                "Lvl " + depth + "   Turn " + turn,
                MARGIN, satBarRect.y - 6f);

        // Player buff icons row — under the status line, anchored at the
        // top-left edge so it shares the bars cluster's anchor. Caps at
        // 8 visible buffs to avoid the row bleeding under the right-side
        // chrome on a narrow viewport.
        Mob p = currentPlayer();
        buffIconRects.clear();
        buffIconTypes.clear();
        if (p != null && p.buffs != null && !p.buffs.isEmpty()) {
            float iconSz = 16f;
            float iconGap = 2f;
            float bx = MARGIN;
            float by = satBarRect.y - 30f;
            int max = Math.min(p.buffs.size(), 8);
            for (int i = 0; i < max; i++) {
                Buff b = p.buffs.get(i);
                if (b == null || b.type == null) continue;
                var region = BuffIcons.regionFor(b.type);
                if (region == null) continue;
                float ix = bx + i * (iconSz + iconGap);
                ctx.batch.draw(region, ix, by, iconSz, iconSz);
                Rect r = new Rect();
                r.set(ix, by, iconSz, iconSz);
                buffIconRects.add(r);
                buffIconTypes.add(b.type);
            }
        }

        // Event log readout — last few entries, drawn above the action
        // quickslot row at the bottom-right. Most-recent line lands closest
        // to the action bar; older lines stack upward. Filtered through
        // {@link LogPreferences} so the user's "show low-priority / mob-vs-
        // mob / log on" toggles drive what shows.
        if (LogPreferences.logOn()) {
            float logRight = invRect.x - ACTION_GAP;
            float logLeft  = MARGIN;
            float logBottom = MARGIN + ACTION_BTN + 6f;
            float lineH = 16f;
            int maxLines = LogPreferences.expanded() ? 6 : 3;
            // Walk recent entries newest-first; render newest at logBottom
            // and stack older ones upward. tail() returns oldest-first, so
            // iterate in reverse.
            List<LogEvent> recent = EventLog.tail(maxLines * 4);
            int lines = 0;
            for (int i = recent.size() - 1; i >= 0 && lines < maxLines; i--) {
                LogEvent e = recent.get(i);
                if (e == null || e.text == null) continue;
                if (!LogPreferences.showLowPriority()
                        && e.priority == LogEvent.EventPriority.LOW) continue;
                if (!LogPreferences.showNonPlayer()
                        && !e.involvesPlayer) continue;
                com.badlogic.gdx.graphics.Color col =
                        e.priority == LogEvent.EventPriority.HIGH
                                ? UiColors.TEXT_BODY
                                : UiColors.TEXT_DIM;
                String text = e.text;
                // Naive cap so a long line doesn't overshoot the action bar.
                int maxChars = 56;
                if (text.length() > maxChars) text = text.substring(0, maxChars - 1) + "…";
                TextDraw.left(ctx, ctx.fontRegular, col,
                        text, logLeft, logBottom + lines * lineH);
                lines++;
                // Soft x-overrun guard — if the rect is narrower on a
                // viewport-shrunk world, stop early rather than spilling
                // under the action quickslots.
                if (logLeft >= logRight) break;
            }
        }

        // Burger menu items — centred header-weight labels, matching the
        // V2Screen menu-screen burger style.
        if (menuOpen) {
            String[] labels = { "Settings", "Map", "Encyclopaedia", "Quit to Title" };
            for (int i = 0; i < labels.length; i++) {
                Rect r = menuItemRects[i];
                TextDraw.centre(ctx, ctx.fontHeader,
                        i == menuItemPressed ? Pal.ACCENT : Pal.WHITE,
                        labels[i], r.cx(), r.cy() + 8f);
            }
        }

        ctx.batch.end();
    }

    private void renderBurgerGlyph() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(burgerPressed || menuOpen ? Pal.ACCENT : Pal.WHITE);
        float cx   = burgerRect.cx();
        float cy   = burgerRect.cy();
        float barW = burgerRect.w * 0.5f;
        float barH = 3f;
        float gap  = 5f;
        for (int i = -1; i <= 1; i++) {
            s.rect(cx - barW * 0.5f, cy - barH * 0.5f + i * (barH + gap),
                    barW, barH);
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private Mob currentPlayer() {
        return playerSupplier != null ? playerSupplier.get() : null;
    }

    // ── Input ───────────────────────────────────────────────────────────────
    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Burger menu items intercept first when the menu is open —
                // so tapping a label fires it rather than the underlying HUD
                // button below.
                if (menuOpen) {
                    for (int i = 0; i < menuItemRects.length; i++) {
                        if (menuItemRects[i].contains(vx, vy)) {
                            menuItemPressed = i;
                            return true;
                        }
                    }
                    // Tap outside the panel (and outside the burger button)
                    // closes the menu without firing anything.
                    if (!menuPanelRect.contains(vx, vy)
                            && !burgerRect.contains(vx, vy)) {
                        menuOpen = false;
                        return true;
                    }
                }

                if (burgerRect.contains(vx, vy))   { burgerPressed = true; return true; }
                if (lookRect.contains(vx, vy))     { lookPressed = true;   return true; }
                if (invRect.contains(vx, vy))      { invPressed = true;    return true; }
                for (int i = 0; i < buffIconRects.size(); i++) {
                    if (buffIconRects.get(i).contains(vx, vy)) {
                        buffIconPressed = i;
                        return true;
                    }
                }
                for (int i = 0; i < 6; i++) {
                    if (actionRects[i].contains(vx, vy)) {
                        actionPressed[i] = true;
                        return true;
                    }
                }
                // Tap on portrait OR status-bar cluster opens character stats.
                if (portraitRect.contains(vx, vy)
                        || hpBarRect.contains(vx, vy)
                        || xpBarRect.contains(vx, vy)
                        || satBarRect.contains(vx, vy)) {
                    if (onPortraitTap != null) onPortraitTap.run();
                    return true;
                }
                return false;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                if (menuItemPressed >= 0) {
                    int idx = menuItemPressed;
                    menuItemPressed = -1;
                    if (menuItemRects[idx].contains(vx, vy)) {
                        menuOpen = false;
                        switch (idx) {
                            case 0 -> { if (onOpenSettings     != null) onOpenSettings.run(); }
                            case 1 -> { if (onOpenMap          != null) onOpenMap.run();
                                        else if (onOpenLevelInfo != null) onOpenLevelInfo.run(); }
                            case 2 -> { if (onOpenEncyclopedia != null) onOpenEncyclopedia.run(); }
                            case 3 -> { if (onReturnToTitle    != null) onReturnToTitle.run(); }
                        }
                    }
                    return true;
                }

                if (burgerPressed) {
                    burgerPressed = false;
                    if (burgerRect.contains(vx, vy)) menuOpen = !menuOpen;
                    return true;
                }
                if (lookPressed) {
                    lookPressed = false;
                    if (lookRect.contains(vx, vy) && onLook != null) onLook.run();
                    return true;
                }
                if (invPressed) {
                    invPressed = false;
                    if (invRect.contains(vx, vy) && onOpenInventory != null) {
                        onOpenInventory.run();
                    }
                    return true;
                }
                if (buffIconPressed >= 0) {
                    int idx = buffIconPressed;
                    buffIconPressed = -1;
                    if (idx < buffIconRects.size()
                            && buffIconRects.get(idx).contains(vx, vy)
                            && onBuffTap != null
                            && idx < buffIconTypes.size()) {
                        onBuffTap.accept(buffIconTypes.get(idx));
                    }
                    return true;
                }
                for (int i = 0; i < 6; i++) {
                    if (actionPressed[i]) {
                        actionPressed[i] = false;
                        if (actionRects[i].contains(vx, vy) && onActionUse != null) {
                            onActionUse.accept(i);
                        }
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
