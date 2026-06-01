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
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.ui.skin.Settings;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

import java.util.List;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * V2 in-game HUD - built from primitive ShapeRenderer rects + SpriteBatch
 * texture draws. Replaces the scene2d-based {@link com.bjsp123.rl2.ui.hud.HudRenderer}
 * for active gameplay; instantiated and driven directly by {@code PlayScreen}
 * (no scene2d Stage involvement).
 *
 * <p>Layout, in viewport-relative virtual coords:
 * <ul>
 *   <li><b>Top-left</b> - three stacked status bars (HP / XP / Satiety).</li>
 *   <li><b>Top-right</b> - burger button (opens settings / map / encyclopaedia /
 *       return-to-title via dropdown - dropdown not yet wired in this slice).</li>
 *   <li><b>Bottom-left</b> - Look button.</li>
 *   <li><b>Bottom-right</b> - six action quickslots + an inventory button at the
 *       far right, all flush to the bottom-right corner.</li>
 * </ul>
 *
 * <p>Render lifecycle each frame:
 * <ol>
 *   <li>{@code PlayScreen.render()} runs world-rendering passes</li>
 *   <li>{@code v2Hud.update(player, depth, tick)} - fresh game state</li>
 *   <li>{@code v2Hud.render()} - switches projection to V2 camera, runs
 *       a ShapeRenderer pass for backgrounds + bars, then a SpriteBatch
 *       pass for icons, status line, and burger glyph (drawn via shapes again
 *       at the end since they're geometry, not textures)</li>
 *   <li>uiStage (popups) renders on top - popups still come from V1 for now</li>
 * </ol>
 *
 * <p>Input: {@link #input()} returns an {@link InputProcessor} that the
 * PlayScreen multiplexer should slot AFTER the popup stage and BEFORE the
 * world's own input. Any tap on a HUD button consumes the touch and fires
 * the corresponding callback; taps outside the HUD rects fall through.
 */
public final class V2Hud {

    // -- Layout constants (virtual px, design at 400x720) --------------------
    private static final float MARGIN       = 8f;
    private static final float BAR_W        = 140f;
    private static final float BAR_H        = 14f;
    private static final float BAR_GAP      = 4f;
    /** Scale factor applied to all HUD interactive elements so they read
     *  comfortably at every UiScale setting. */
    private static final float HUD_SCALE    = 1.5f;
    private static final float ACTION_BTN   = 48f * 0.8f * HUD_SCALE;
    private static final float ACTION_GAP   = 4f;
    private static final float ICON_PAD     = 6f  * HUD_SCALE;
    private static final float CLOCK_SIZE   = 18f;

    // -- State ----------------------------------------------------------------
    private final UiCtx ctx;

    /** Live player accessor - re-read every frame so a level-transition
     *  doesn't leave the HUD bound to a stale Mob reference. */
    private Supplier<Mob> playerSupplier;
    private ActionBar actionBar;
    private int depth, tick;

    // Callbacks - same shape as the V1 HudRenderer.setOn* setters, so
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
    private Runnable    onOpenLog;
    /** Tap on a player buff icon -> open buff detail popup. */
    private java.util.function.Consumer<Buff> onBuffTap;
    public void setOnBuffTap(java.util.function.Consumer<Buff> fn) {
        this.onBuffTap = fn;
    }

    // -- Hit rects ------------------------------------------------------------
    private final Rect portraitRect = new Rect();
    private final Rect hpBarRect    = new Rect();
    private final Rect xpBarRect    = new Rect();
    private final Rect clockRect    = new Rect();
    private final Rect[] actionRects = new Rect[ActionBar.SLOTS];
    private final Rect invRect      = new Rect();
    private final Rect lookRect     = new Rect();
    private final Rect burgerRect   = new Rect();
    private final Rect menuPanelRect = new Rect();
    /** Per-menu-item rects, populated when the burger menu is open. */
    private final Rect[] menuItemRects = new Rect[5];
    /** Per-buff-icon hit rects, rebuilt every frame from the live buff
     *  list. Aligned in index with {@link #buffIconTypes}. */
    private final java.util.List<Rect> buffIconRects = new java.util.ArrayList<>();
    private final java.util.List<Buff> buffIconList = new java.util.ArrayList<>();

    // -- Pressed state for visual feedback -----------------------------------
    private final boolean[] actionPressed = new boolean[ActionBar.SLOTS];
    private boolean invPressed, lookPressed, burgerPressed;

    // -- Mana-gain flash state ------------------------------------------------
    private static final float CHARGE_FLASH_FRAMES = 45f;
    private final float[] prevCharge  = new float[ActionBar.SLOTS];
    private final float[] chargeFlash = new float[ActionBar.SLOTS];
    private boolean menuOpen;
    /** True when the action slots are folded into a grid because the viewport
     *  is too narrow for a single row. Set each frame by {@link #layoutRects()};
     *  read by the render and input passes. */
    private boolean gridLayout;
    /** Number of rows used in the grid layout (2 or 3). Only meaningful when
     *  {@link #gridLayout} is true; used to place the log readout above the grid. */
    private int actionGridRows = 2;
    private int menuItemPressed = -1;
    /** Index of the buff icon currently being held; -1 when none. */
    private int buffIconPressed = -1;

    public V2Hud(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < actionRects.length;  i++) actionRects[i]  = new Rect();
        for (int i = 0; i < menuItemRects.length; i++) menuItemRects[i] = new Rect();
    }

    // -- Public API (mirrors HudRenderer.setOn* surface) ---------------------
    public void setPlayerSupplier(Supplier<Mob> s)  { this.playerSupplier = s; }
    public void setActionBar(ActionBar a) {
        this.actionBar = a;
        java.util.Arrays.fill(prevCharge,  0f);
        java.util.Arrays.fill(chargeFlash, 0f);
    }
    public void setOnActionUse(IntConsumer fn)      { this.onActionUse = fn; }
    public void setOnOpenInventory(Runnable fn)     { this.onOpenInventory = fn; }
    public void setOnLook(Runnable fn)              { this.onLook = fn; }
    public void setOnPortraitTap(Runnable fn)       { this.onPortraitTap = fn; }
    public void setOnOpenSettings(Runnable fn)      { this.onOpenSettings = fn; }
    public void setOnOpenEncyclopedia(Runnable fn)  { this.onOpenEncyclopedia = fn; }
    public void setOnOpenLevelInfo(Runnable fn)     { this.onOpenLevelInfo = fn; }
    public void setOnReturnToTitle(Runnable fn)     { this.onReturnToTitle = fn; }
    public void setOnOpenMap(Runnable fn)           { this.onOpenMap = fn; }
    public void setOnOpenLog(Runnable fn)           { this.onOpenLog = fn; }
    public void setSounds(com.bjsp123.rl2.audio.SoundManager s) { this.sounds = s; }
    private com.bjsp123.rl2.audio.SoundManager sounds;

    /** Frame state - depth and game tick for the status line / turn clock;
     *  player read via {@link #playerSupplier}. */
    public void update(int depth, int tick) {
        this.depth = depth;
        this.tick  = tick;
    }

    /** True when the burger dropdown is showing - PlayScreen folds this
     *  into its {@code isAnyPopupOpen()} gate so the world doesn't tick
     *  while the player is reading the menu. */
    public boolean isMenuOpen() { return menuOpen; }

    public void render() {
        layoutRects();
        ctx.applyProjection();
        // Compute low-HP factor once per frame so the chrome tint + the hit
        // flash overlay both read from the same value. 0 above 25% HP, 1 at
        // 1% HP. Linear ramp; clamped.
        Mob p = currentPlayer();
        float hpPct = 100f;
        if (p != null) {
            double maxHp = p.effectiveStats().maxHp;
            if (maxHp > 0) hpPct = (float) (p.hp / maxHp * 100.0);
        }
        float rampStartPct = (float) (com.bjsp123.rl2.logic.GameBalance.LOW_HP_RAMP_START * 100.0);
        lowHpFactor = Math.max(0f, Math.min(1f, (rampStartPct - hpPct) / Math.max(1f, rampStartPct - 1f)));
        pushLowHpChromeTint();
        try {
            renderShapesPass();
            renderTextPass();
            renderBurgerGlyph();   // tiny shapes pass at the end for the menu lines
        } finally {
            popLowHpChromeTint();
        }
        renderHitFlash();
        if (hitFlashFrames > 0) hitFlashFrames--;
    }

    /** Flash the screen red + play a warning sfx. PlayScreen's animator hook
     *  calls this when the player takes damage while HP <=20%. */
    public void triggerLowHpHitFlash() {
        hitFlashFrames = HIT_FLASH_DURATION;
        if (sounds != null) sounds.play("sfx.player.combat.lowHpWarn");
    }

    /** Below 25% HP the HUD chrome reddens linearly; full red at 1%. */
    private float lowHpFactor;
    private int   hitFlashFrames;
    private static final int HIT_FLASH_DURATION = 18;

    /** Saved chrome colors so the low-HP tint can be reverted after the HUD
     *  render. UIVars stores live Color instances - we lerp them toward red
     *  in place, then restore from these snapshots. */
    private final com.badlogic.gdx.graphics.Color savedBorderOuter = new com.badlogic.gdx.graphics.Color();
    private final com.badlogic.gdx.graphics.Color savedBorderMid   = new com.badlogic.gdx.graphics.Color();
    private final com.badlogic.gdx.graphics.Color savedBorderInner = new com.badlogic.gdx.graphics.Color();
    private boolean chromeRedded;

    private void pushLowHpChromeTint() {
        if (lowHpFactor <= 0f) { chromeRedded = false; return; }
        savedBorderOuter.set(UIVars.BORDER_OUTER);
        savedBorderMid  .set(UIVars.BORDER_MID);
        savedBorderInner.set(UIVars.BORDER_INNER);
        com.badlogic.gdx.graphics.Color warn = UIVars.TEXT_WARN;
        UIVars.BORDER_OUTER.set(savedBorderOuter).lerp(warn, lowHpFactor);
        UIVars.BORDER_MID  .set(savedBorderMid)  .lerp(warn, lowHpFactor);
        UIVars.BORDER_INNER.set(savedBorderInner).lerp(warn, lowHpFactor);
        chromeRedded = true;
    }

    private void popLowHpChromeTint() {
        if (!chromeRedded) return;
        UIVars.BORDER_OUTER.set(savedBorderOuter);
        UIVars.BORDER_MID  .set(savedBorderMid);
        UIVars.BORDER_INNER.set(savedBorderInner);
        chromeRedded = false;
    }

    /** Brief red overlay on top of the HUD whenever the player just took a
     *  damaging hit while already below 20% HP. Triggered by
     *  {@link #triggerLowHpHitFlash}; rendered every frame the counter is
     *  positive. */
    private void renderHitFlash() {
        if (hitFlashFrames <= 0) return;
        float t = hitFlashFrames / (float) HIT_FLASH_DURATION;
        float alpha = 0.45f * t;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        ctx.shapes.setColor(1f, 0.1f, 0.1f, alpha);
        ctx.shapes.rect(0, 0, ctx.worldW(), ctx.worldH());
        ctx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // -- Layout --------------------------------------------------------------
    private void layoutRects() {
        float w = ctx.worldW();
        float h = ctx.worldH();

        // Top-left cluster: portrait on the left, three status bars stacked
        // to its right. Portrait is square; bars take the rest of the row.
        float portraitSz = 40f * HUD_SCALE;
        portraitRect.set(MARGIN, h - MARGIN - portraitSz, portraitSz, portraitSz);

        float barX = portraitRect.right() + 4f;
        float by = h - MARGIN - BAR_H;
        hpBarRect.set(barX, by, BAR_W, BAR_H);
        by -= BAR_H + BAR_GAP;
        xpBarRect.set(barX, by, BAR_W, BAR_H);
        // Satiety bar removed - field stays on Mob for save back-compat
        // but the meter isn't drawn or tapped. Clock anchors to xpBarRect
        // now since there's no satiety bar beneath it.
        clockRect.set(MARGIN, xpBarRect.y - CLOCK_SIZE - 8f, CLOCK_SIZE, CLOCK_SIZE);

        // Burger at top-right.
        burgerRect.set(w - MARGIN - ACTION_BTN, h - MARGIN - ACTION_BTN,
                ACTION_BTN, ACTION_BTN);

        // Look at bottom-left.
        lookRect.set(MARGIN, MARGIN, ACTION_BTN, ACTION_BTN);

        // Bottom-right: inventory button at far right, action slots to its left.
        // If the single row would overlap the look button, fold into a grid.
        int n = com.bjsp123.rl2.ui.skin.Settings.quickslotCount();
        invRect.set(w - ACTION_BTN, MARGIN, ACTION_BTN, ACTION_BTN);
        float stripW1row = n * ACTION_BTN + (n - 1) * ACTION_GAP;
        float ax1row = invRect.x - ACTION_GAP - stripW1row;
        gridLayout = ax1row < lookRect.x + lookRect.w + ACTION_GAP;
        if (gridLayout) {
            // Prefer 2 rows (e.g. 4x2 for 8 slots); fall back to 3 rows only
            // when the 2-row grid would overlap the look button.
            int gridCols2 = (n + 1) / 2;
            float gridW2  = gridCols2 * ACTION_BTN + (gridCols2 - 1) * ACTION_GAP;
            float gx2     = invRect.x - ACTION_GAP - gridW2;
            int gridRows  = (gx2 >= lookRect.right() + ACTION_GAP) ? 2 : 3;
            actionGridRows = gridRows;
            int gridCols  = (n + gridRows - 1) / gridRows;
            float gridW   = gridCols * ACTION_BTN + (gridCols - 1) * ACTION_GAP;
            float gx      = invRect.x - ACTION_GAP - gridW;
            for (int i = 0; i < n; i++) {
                int col = i % gridCols;
                int row = i / gridCols;
                actionRects[i].set(
                        gx + col * (ACTION_BTN + ACTION_GAP),
                        MARGIN + row * (ACTION_BTN + ACTION_GAP),
                        ACTION_BTN, ACTION_BTN);
            }
        } else {
            float ax = ax1row;
            for (int i = 0; i < n; i++) {
                actionRects[i].set(ax, MARGIN, ACTION_BTN, ACTION_BTN);
                ax += ACTION_GAP + ACTION_BTN;
            }
        }

        // Burger menu - centred on the viewport as a chunky column-of-
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

    // -- Render passes -------------------------------------------------------
    private void renderShapesPass() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        Mob player = currentPlayer();

        // Status bars (HP + XP only - satiety was removed from the game).
        if (player != null) {
            double maxHp = player.effectiveStats().maxHp;
            float hpFrac = maxHp > 0 ? (float) (player.hp / maxHp) : 0f;
            drawBar(s, hpBarRect, hpFrac, UIVars.TEXT_WARN);

            int xpBase = com.bjsp123.rl2.logic.MobProgression.xpToReach(player.characterLevel);
            int xpStep = com.bjsp123.rl2.logic.MobProgression.xpToAdvanceFrom(player.characterLevel);
            float xpFrac = xpStep > 0 ? (float)(player.xp - xpBase) / xpStep : 1f;
            drawBar(s, xpBarRect, Math.max(0f, Math.min(1f, xpFrac)), UIVars.BAR_XP);
        } else {
            drawBar(s, hpBarRect, 0f, UIVars.TEXT_WARN);
            drawBar(s, xpBarRect, 0f, UIVars.ACCENT);
        }
        drawTurnClock(s);

        // Buttons - chrome only; icons land in the SpriteBatch pass.
        // Action quickslots + inventory button carry item / chest icons,
        // so they paint with the paler SLOT_BG so the icon stays legible.
        for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) drawSlotBtn(s, actionRects[i], actionPressed[i]);
        drawSlotBtn(s, invRect, invPressed);
        // Look + burger have no item icon - plain HUD chrome.
        drawBtn(s, lookRect,   lookPressed);
        drawBtn(s, burgerRect, burgerPressed);

        // Burger menu - modal column-of-buttons window centred on the
        // viewport. Dim everything behind it first, then paint the window
        // and each item as a chunky button (tri-line border + warm fill).
        if (menuOpen) {
            s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            Window.drawShape(ctx,
                    menuPanelRect.x, menuPanelRect.y,
                    menuPanelRect.w, menuPanelRect.h);
            for (int i = 0; i < menuItemRects.length; i++) {
                Rect r = menuItemRects[i];
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
                s.setColor(i == menuItemPressed
                        ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
                s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                        r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
            }
        }

        // Buff duration dot timers - one 1x1 dot per remaining turn (max 8),
        // stacked bottom-to-top to the right of each buff icon.
        Mob dotPlayer = currentPlayer();
        if (dotPlayer != null && dotPlayer.buffs != null && !dotPlayer.buffs.isEmpty()) {
            float iconSz = 16f * HUD_SCALE;
            float iconGap = 2f * HUD_SCALE;
            float bx = MARGIN;
            float by = xpBarRect.y - 30f * HUD_SCALE;
            int max = Math.min(dotPlayer.buffs.size(), 8);
            s.setColor(UIVars.TEXT_DIM);
            for (int i = 0; i < max; i++) {
                Buff b = dotPlayer.buffs.get(i);
                if (b == null || b.type == null || b.durationTicks <= 0) continue;
                if (BuffIcons.regionFor(b.type) == null) continue;
                float dotX = bx + i * (iconSz + iconGap) + iconSz + 1f;
                int dots = Math.min(8, com.bjsp123.rl2.logic.BuffSystem.displayTurns(b.durationTicks));
                for (int d = 0; d < dots; d++) {
                    s.rect(dotX, by + d * 2f, 1f, 1f);
                }
            }
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Draw an HP/XP/Satiety bar - tri-line border, mid warm-grey backdrop,
     *  and a fill bar at {@code frac} in {@code fillColor}. */
    private void drawBar(ShapeRenderer s, Rect r, float frac, Color fillColor) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, 1f);
        s.setColor(UIVars.HUD_BG);
        s.rect(r.x + 3, r.y + 3, r.w - 6, r.h - 6);
        if (frac > 0f) {
            float fw = (r.w - 6) * Math.max(0, Math.min(1, frac));
            s.setColor(fillColor);
            s.rect(r.x + 3, r.y + 3, fw, r.h - 6);
        }
    }

    private void drawTurnClock(ShapeRenderer s) {
        float cx = clockRect.cx();
        float cy = clockRect.cy();
        float r = Math.min(clockRect.w, clockRect.h) * 0.5f;
        s.setColor(UIVars.BORDER_OUTER);
        s.circle(cx, cy, r, 24);
        s.setColor(UIVars.HUD_BG);
        s.circle(cx, cy, Math.max(1f, r - 2f), 24);
        s.setColor(UIVars.TEXT_DIM);
        s.rectLine(cx, cy + r - 3f, cx, cy + r - 1f, 1f);

        float frac = TurnSystem.tickWithinStandardTurn(tick)
                / (float) TurnSystem.STANDARD_TURN_TICKS;
        float angle = (float) (Math.PI * 0.5 - frac * Math.PI * 2.0);
        float hx = cx + (float) Math.cos(angle) * (r - 4f);
        float hy = cy + (float) Math.sin(angle) * (r - 4f);
        s.setColor(UIVars.ACCENT);
        s.rectLine(cx, cy, hx, hy, 2f);
        s.circle(cx, cy, 2f, 12);
    }

    /** Action-quickslot chrome - tri-line border + paler warm-grey fill so
     *  the item icon drawn over it is legible. Used for the bottom-right
     *  action buttons and the inventory button (both carry sprites). */
    private void drawSlotBtn(ShapeRenderer s, Rect r, boolean pressed) {
        ButtonChrome.shape(ctx, r, pressed, false, false, UIVars.SLOT_BG);
    }

    /** Plain HUD button chrome - tri-line border + mid warm-grey fill.
     *  Used for buttons WITHOUT item icons (look button, burger). */
    private void drawBtn(ShapeRenderer s, Rect r, boolean pressed) {
        ButtonChrome.shape(ctx, r, pressed, false, false, UIVars.HUD_BG);
    }


    private void renderTextPass() {
        ctx.batch.begin();

        // Portrait - character class sprite drawn in the top-left cell.
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

        // XP bar right-side labels - character level and unspent-perk star.
        Mob playerXp = currentPlayer();
        if (playerXp != null) {
            String lvlLabel = String.valueOf(playerXp.characterLevel);
            if (playerXp.perkPoints > 0) lvlLabel += " *";
            com.badlogic.gdx.graphics.Color lvlColor =
                    playerXp.perkPoints > 0 ? UIVars.ACCENT : UIVars.TEXT_DIM;
            TextDraw.right(ctx, ctx.fontRegular, lvlColor,
                    lvlLabel, xpBarRect.right() - 2f, xpBarRect.y + xpBarRect.h - 2f);
        }

        // Action button icons - pull from ActionBar.
        if (actionBar != null) {
            for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) {
                Item it = actionBar.get(i);
                if (it == null) continue;
                Rect r = actionRects[i];
                ItemCell.draw(ctx, it, currentPlayer(), r.x, r.y, r.w, r.h, true);
            }
            // Hotkey numbers in the top-left corner of each slot, rendered at
            // ~70% of the regular font so they sit underneath the item +level
            // badge (which uses the full font height). Bound to keys 1..9.
            com.badlogic.gdx.graphics.g2d.BitmapFont f = ctx.fontRegular;
            float prevScale = f.getScaleX();
            f.getData().setScale(prevScale * 0.7f);
            f.setColor(UIVars.TEXT_DIM);
            int n = com.bjsp123.rl2.ui.skin.Settings.quickslotCount();
            for (int i = 0; i < n && i < 9; i++) {
                Rect r = actionRects[i];
                String label = Integer.toString(i + 1);
                ctx.layout.setText(f, label);
                float lx = r.x + 3f;
                float ly = r.y + r.h - 2f;
                f.draw(ctx.batch, label, lx, ly);
            }
            f.getData().setScale(prevScale);
            f.setColor(com.badlogic.gdx.graphics.Color.WHITE);
            // Mana-gain flash - detect charge increase, then draw additive overlay.
            for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) {
                Item it = actionBar.get(i);
                float cur = (it != null) ? it.charge : 0f;
                if (it != null && cur > prevCharge[i] + 0.01f)
                    chargeFlash[i] = CHARGE_FLASH_FRAMES;
                prevCharge[i] = cur;
            }
            for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) {
                if (chargeFlash[i] <= 0f) continue;
                float alpha = (chargeFlash[i] / CHARGE_FLASH_FRAMES) * 0.55f;
                Rect r = actionRects[i];
                ctx.batch.setBlendFunction(
                        com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                        com.badlogic.gdx.graphics.GL20.GL_ONE);
                ctx.batch.setColor(0.35f, 0.75f, 1f, alpha);
                ctx.batch.draw(ctx.whitePixel, r.x, r.y, r.w, r.h);
                ctx.batch.setBlendFunction(
                        com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                        com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
                ctx.batch.setColor(com.badlogic.gdx.graphics.Color.WHITE);
                chargeFlash[i] = Math.max(0f, chargeFlash[i] - 1f);
            }
        }

        // Inventory button - chest icon if available, else "Bag" label.
        TextureRegion chest = IconSprites.regionFor(IconSprites.Icon.INV);
        if (chest != null) {
            ctx.batch.draw(chest,
                    invRect.x + ICON_PAD, invRect.y + ICON_PAD,
                    invRect.w - 2 * ICON_PAD, invRect.h - 2 * ICON_PAD);
        } else {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY, TextCatalog.get("ui.hud.bag"),
                    invRect.cx(), invRect.y + invRect.h * 0.5f + 4);
        }

        // Look button - magnifier icon.
        TextureRegion look = IconSprites.regionFor(IconSprites.Icon.LOOK);
        if (look != null) {
            ctx.batch.draw(look,
                    lookRect.x + ICON_PAD, lookRect.y + ICON_PAD,
                    lookRect.w - 2 * ICON_PAD, lookRect.h - 2 * ICON_PAD);
        }

        // Status line + turn clock - under the satiety bar.
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                TextCatalog.format("ui.hud.status", TextCatalog.vars("depth", depth)),
                clockRect.right() + 5f, clockRect.y + clockRect.h * 0.5f + 5f,
                Math.max(40f, ctx.worldW() - clockRect.right() - 2f * MARGIN));

        // Player buff icons row - under the status line, anchored at the
        // top-left edge so it shares the bars cluster's anchor. Caps at
        // 8 visible buffs to avoid the row bleeding under the right-side
        // chrome on a narrow viewport.
        Mob p = currentPlayer();
        buffIconRects.clear();
        buffIconList.clear();
        if (p != null && p.buffs != null && !p.buffs.isEmpty()) {
            float iconSz = 16f * HUD_SCALE;
            float iconGap = 2f * HUD_SCALE;
            float bx = MARGIN;
            float by = xpBarRect.y - 30f * HUD_SCALE;
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
                buffIconList.add(b);
            }
        }

        // Event log readout - last few entries, drawn above the action
        // quickslot row at the bottom-right. Most-recent line lands closest
        // to the action bar; older lines stack upward. Filtered through
        // {@link LogPreferences} so the user's "show low-priority / mob-vs-
        // mob / log on" toggles drive what shows.
        if (Settings.logOn()) {
            float logRight = invRect.x - ACTION_GAP;
            float logLeft  = MARGIN;
            float logBottom = MARGIN + (gridLayout
                    ? actionGridRows * ACTION_BTN + (actionGridRows - 1) * ACTION_GAP
                    : ACTION_BTN) + 6f;
            float lineH = ctx.lineH();
            int maxLines = Settings.logExpanded() ? 6 : 3;
            // Walk recent entries newest-first; render newest at logBottom
            // and stack older ones upward. tail() returns oldest-first, so
            // iterate in reverse.
            List<LogEvent> recent = EventLog.tail(maxLines * 4);
            int lines = 0;
            for (int i = recent.size() - 1; i >= 0 && lines < maxLines; i--) {
                LogEvent e = recent.get(i);
                if (e == null || e.text == null) continue;
                if (!Settings.showLowPriority()
                        && e.priority == LogEvent.EventPriority.LOW) continue;
                if (!Settings.showNonPlayer()
                        && !e.involvesPlayer) continue;
                com.badlogic.gdx.graphics.Color col =
                        e.priority == LogEvent.EventPriority.HIGH
                                ? UIVars.TEXT_BODY
                                : UIVars.TEXT_DIM;
                TextDraw.leftFit(ctx, ctx.fontRegular, col,
                        e.text, logLeft, logBottom + lines * lineH,
                        Math.max(24f, logRight - logLeft));
                lines++;
                // Soft x-overrun guard - if the rect is narrower on a
                // viewport-shrunk world, stop early rather than spilling
                // under the action quickslots.
                if (logLeft >= logRight) break;
            }
        }

        // Burger menu items - centred header-weight labels, matching the
        // V2Screen menu-screen burger style.
        if (menuOpen) {
            String[] labels = {
                    TextCatalog.get("ui.hud.menu.settings"),
                    TextCatalog.get("ui.hud.menu.map"),
                    TextCatalog.get("ui.hud.menu.encyclopedia"),
                    TextCatalog.get("ui.hud.menu.log"),
                    TextCatalog.get("ui.hud.menu.main") };
            for (int i = 0; i < labels.length; i++) {
                Rect r = menuItemRects[i];
                TextDraw.centre(ctx, ctx.fontHeader,
                        i == menuItemPressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
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
        ButtonChrome.burgerGlyph(ctx, burgerRect, burgerPressed || menuOpen);
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private Mob currentPlayer() {
        return playerSupplier != null ? playerSupplier.get() : null;
    }

    // -- Input ---------------------------------------------------------------
    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Burger menu items intercept first when the menu is open -
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
                for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) {
                    if (actionRects[i].contains(vx, vy)) {
                        actionPressed[i] = true;
                        return true;
                    }
                }
                // Tap on portrait OR status-bar cluster opens character stats.
                if (portraitRect.contains(vx, vy)
                        || hpBarRect.contains(vx, vy)
                        || xpBarRect.contains(vx, vy)) {
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
                            case 3 -> { if (onOpenLog          != null) onOpenLog.run(); }
                            case 4 -> { if (onReturnToTitle    != null) onReturnToTitle.run(); }
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
                            && idx < buffIconList.size()) {
                        onBuffTap.accept(buffIconList.get(idx));
                    }
                    return true;
                }
                for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) {
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
