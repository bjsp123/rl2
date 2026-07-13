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

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * V2 in-game HUD - built from primitive ShapeRenderer rects + SpriteBatch
 * texture draws. Replaces the retired scene2d-based HUD renderer
 * for active gameplay; instantiated and driven directly by {@code PlayScreen}
 * (no scene2d Stage involvement).
 *
 * <p>Layout, in viewport-relative virtual coords:
 * <ul>
 *   <li><b>Top-left</b> - stacked status bars (HP / XP).</li>
 *   <li><b>Top-right</b> - burger button; tap opens the shared
 *       {@link BurgerMenu} dropdown with the standard in-run items
 *       ({@link BurgerMenu#populateStandard}: Settings / Encyclopedia /
 *       Level Info / Map / Log / Main Menu).</li>
 *   <li><b>Bottom-left</b> - Look button.</li>
 *   <li><b>Bottom-right</b> - action quickslots ({@code Settings.quickslotCount()} of them) + an inventory button at the
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
 *   <li>the shared {@code game.ui.v2Stage} (V2 popups) renders on top</li>
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
    private static final float ACTION_BTN   = 48f * 0.9f * HUD_SCALE;
    private static final float ACTION_GAP   = 4f;
    private static final float ICON_PAD     = 6f  * HUD_SCALE;
    private static final float CLOCK_SIZE   = 18f;

    // -- State ----------------------------------------------------------------
    private final UiCtx ctx;

    /** Live player accessor - re-read every frame so a level-transition
     *  doesn't leave the HUD bound to a stale Mob reference. */
    private Supplier<Mob> playerSupplier;
    private ActionBar actionBar;
    private int tick;

    /** Smoothed turn-clock hand position, in continuous turns (integer part =
     *  turns elapsed, fractional part = position within the current turn). Eased
     *  toward the real {@link #tick} each frame so the hand sweeps at frame-rate
     *  resolution instead of snapping a tick at a time. */
    private float clockDisplayTurns;
    private boolean clockDisplayInit;
    /** Exponential catch-up rate (1/sec). Higher = snappier. */
    private static final float CLOCK_EASE_RATE = 9f;
    /** Never let the hand fall more than this far behind the real position, so a
     *  multi-turn jump (a long rest) catches up promptly instead of spinning. */
    private static final float CLOCK_MAX_LAG_TURNS = 1f;

    // Callbacks - PlayScreen wires each HUD affordance to a game action.
    private IntConsumer onActionUse;
    private Runnable    onOpenInventory;
    private Runnable    onLook;
    private Runnable    onAutoExplore;
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
    private final Rect autoRect     = new Rect();
    private final Rect burgerRect   = new Rect();
    /** Shared burger drop-down (geometry + chrome). Item labels are fixed;
     *  releases dispatch through the switch in {@link #input()}. */
    private final BurgerMenu menu = new BurgerMenu();
    /** Per-buff-icon hit rects, rebuilt every frame from the live buff
     *  list. Aligned in index with {@link #buffIconTypes}. */
    private final java.util.List<Rect> buffIconRects = new java.util.ArrayList<>();
    private final java.util.List<Buff> buffIconList = new java.util.ArrayList<>();
    /** Fixed pool backing {@link #buffIconRects} - at most 8 buff icons show, so
     *  reuse the same Rects instead of allocating fresh ones every frame. */
    private final Rect[] buffRectPool = new Rect[8];

    // ---- Per-frame string caches. The HUD renders every frame but these
    // strings only change on discrete game events; TextCatalog.format allocates
    // a map + varargs + several intermediate strings per call, which is real GC
    // pressure on TeaVM. Each cache re-formats only when its key changes.
    private String revivesTextCache;  private int revivesKey = Integer.MIN_VALUE;
    private String portraitLabelCache; private int portraitLabelLevelKey = Integer.MIN_VALUE;
    private boolean portraitLabelStarKey; private Mob.CharacterClass portraitLabelClassKey;

    // -- Portrait expression flash (real-time) --------------------------------
    /** Flash duration for an ANGRY / HAPPY portrait reaction. */
    private static final float EXPRESSION_MS = 350f;
    private com.bjsp123.rl2.world.render.PortraitSprites.Expression expression =
            com.bjsp123.rl2.world.render.PortraitSprites.Expression.NEUTRAL;
    private float expressionMs;

    /** Player took damage: flash the angry portrait with a brief shudder. */
    public void flashAngry() {
        expression = com.bjsp123.rl2.world.render.PortraitSprites.Expression.ANGRY;
        expressionMs = EXPRESSION_MS;
    }

    /** Player picked something up: flash the happy portrait with a little hop. */
    public void flashHappy() {
        // Damage trumps delight - don't overwrite an active angry flash.
        if (expressionMs > 0f
                && expression == com.bjsp123.rl2.world.render.PortraitSprites.Expression.ANGRY) {
            return;
        }
        expression = com.bjsp123.rl2.world.render.PortraitSprites.Expression.HAPPY;
        expressionMs = EXPRESSION_MS;
    }

    // -- Pressed state for visual feedback -----------------------------------
    private final boolean[] actionPressed = new boolean[ActionBar.SLOTS];
    private boolean invPressed, lookPressed, burgerPressed;
    /** Auto-explore button (RL-53): pressed state + active highlight. */
    private boolean autoPressed, autoActive;

    // -- Mana-gain flash state ------------------------------------------------
    private static final float CHARGE_FLASH_FRAMES = 45f;
    private final float[] prevCharge  = new float[ActionBar.SLOTS];
    private final float[] chargeFlash = new float[ActionBar.SLOTS];

    // -- HP / XP bar gain-flash state (RL-24) ---------------------------------
    private static final float BAR_FLASH_FRAMES = 45f;
    private double prevHp = -1, prevXp = -1;
    private float hpFlash, xpFlash;
    /** True when the action slots are folded into a grid because the viewport
     *  is too narrow for a single row. Set each frame by {@link #layoutRects()};
     *  read by the render and input passes. */
    private boolean gridLayout;
    /** Number of rows used in the grid layout (2 or 3). Only meaningful when
     *  {@link #gridLayout} is true; used to place the log readout above the grid. */
    private int actionGridRows = 2;
    /** Index of the buff icon currently being held; -1 when none. */
    private int buffIconPressed = -1;
    /** Horizontal gap between buff icons (pre-HUD_SCALE) - wide enough for
     *  each icon's duration drain bar ({@link UIVars#BUFF_BAR_W} + breathing
     *  room) to sit in the gap without touching the next icon. */
    private static final float BUFF_ICON_GAP = 6f;

    public V2Hud(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < actionRects.length;  i++) actionRects[i]  = new Rect();
        // Standard in-run burger items - actions read the setOn* callback
        // fields at fire time, so PlayScreen can wire them after construction.
        menu.populateStandard(false, true, new BurgerMenu.Destinations() {
            @Override public void openSettings()     { run(onOpenSettings); }
            @Override public void openEncyclopedia() { run(onOpenEncyclopedia); }
            @Override public void openLevelInfo()    { run(onOpenLevelInfo); }
            @Override public void openMap()          { if (onOpenMap != null) onOpenMap.run();
                                                       else run(onOpenLevelInfo); }
            @Override public void openLog()          { run(onOpenLog); }
            @Override public void goMainMenu()       { run(onReturnToTitle); }
        });
    }

    private static void run(Runnable r) { if (r != null) r.run(); }

    // -- Public API (setOn* callback setters) ---------------------------------
    public void setPlayerSupplier(Supplier<Mob> s)  { this.playerSupplier = s; }
    public void setActionBar(ActionBar a) {
        this.actionBar = a;
        java.util.Arrays.fill(prevCharge,  0f);
        java.util.Arrays.fill(chargeFlash, 0f);
    }
    public void setOnActionUse(IntConsumer fn)      { this.onActionUse = fn; }
    public void setOnOpenInventory(Runnable fn)     { this.onOpenInventory = fn; }
    public void setOnLook(Runnable fn)              { this.onLook = fn; }
    public void setOnAutoExplore(Runnable fn)       { this.onAutoExplore = fn; }
    /** Reflect auto-explore on/off so the button reads as toggled. */
    public void setAutoExploreActive(boolean on)    { this.autoActive = on; }
    public void setOnPortraitTap(Runnable fn)       { this.onPortraitTap = fn; }
    public void setOnOpenSettings(Runnable fn)      { this.onOpenSettings = fn; }
    public void setOnOpenEncyclopedia(Runnable fn)  { this.onOpenEncyclopedia = fn; }
    public void setOnOpenLevelInfo(Runnable fn)     { this.onOpenLevelInfo = fn; }
    public void setOnReturnToTitle(Runnable fn)     { this.onReturnToTitle = fn; }
    public void setOnOpenMap(Runnable fn)           { this.onOpenMap = fn; }
    public void setOnOpenLog(Runnable fn)           { this.onOpenLog = fn; }
    public void setSounds(com.bjsp123.rl2.audio.SoundManager s) { this.sounds = s; }
    private com.bjsp123.rl2.audio.SoundManager sounds;

    /** Frame state - game tick for the turn clock; player read via
     *  {@link #playerSupplier}. ({@code depth} is accepted for call-site
     *  stability but no longer displayed - the depth/hazard HUD text was
     *  removed by request.) */
    public void update(int depth, int tick) {
        this.tick = tick;
    }

    /** True when the burger dropdown is showing - PlayScreen folds this
     *  into its {@code isAnyPopupOpen()} gate so the world doesn't tick
     *  while the player is reading the menu. */
    public boolean isMenuOpen() { return menu.open; }

    /** Wall-clock accumulator driving the portrait's unspent-perk-point pulse. */
    private float perkFlashT;
    /** Thickness of the pulsing perk frame around the portrait; the layout
     *  reserves this envelope so the frame never overlaps its neighbours. */
    private static final float FRAME_T = 3f;
    /** Height reserved under the portrait for the "lvN Class" label. */
    private static final float LABEL_H = 14f;
    /** Y of the buff-icon row, computed in layoutRects (below the clock). */
    private float buffRowY;
    /** Scratch colour for the perk-pulse XP bar tint (no per-frame alloc). */
    private final Color perkPulseColor = new Color();

    /** 0..1 oscillation shared by the portrait frame, portrait art, and XP
     *  bar while perk points are unspent. */
    private float perkPulse() {
        return 0.5f + 0.5f * (float) Math.sin(perkFlashT * 6f);
    }

    public void render() {
        layoutRects();
        perkFlashT += Gdx.graphics.getDeltaTime();
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
        } finally {
            popLowHpChromeTint();
        }
        renderHitFlash();
        // Drain on wall-clock time (one frame = 1/60 s) so the flash lasts the
        // same duration at any refresh rate, capping the dt like the world tick.
        if (hitFlashFrames > 0) {
            int dtMs = Math.min(100, (int) (Gdx.graphics.getDeltaTime() * 1000f));
            hitFlashFrames = Math.max(0f,
                    hitFlashFrames - com.bjsp123.rl2.world.anim.Animator.frameDelta(dtMs));
        }
    }

    /** Flash the screen red + play a warning sfx. PlayScreen's animator hook
     *  calls this when the player takes damage while HP <=20%. */
    public void triggerLowHpHitFlash() {
        hitFlashFrames = HIT_FLASH_DURATION;
        if (sounds != null) sounds.play("sfx.player.combat.lowHpWarn");
    }

    /** Below 25% HP the HUD chrome reddens linearly; full red at 1%. */
    private float lowHpFactor;
    private float hitFlashFrames;
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

        // Top-left cluster, stacked strictly downward so nothing collides:
        // portrait (with its pulse-frame envelope reserved), then the
        // "lvN Class" label, then the turn clock, then the buff-icon row.
        // Status bars sit to the portrait's right.
        float portraitSz = 40f * HUD_SCALE;
        portraitRect.set(MARGIN + FRAME_T, h - MARGIN - FRAME_T - portraitSz,
                portraitSz, portraitSz);

        float barX = portraitRect.right() + FRAME_T + 6f;
        float by = h - MARGIN - BAR_H;
        hpBarRect.set(barX, by, BAR_W, BAR_H);
        by -= BAR_H + BAR_GAP;
        xpBarRect.set(barX, by, BAR_W, BAR_H);
        // Label baseline sits just under the pulse frame; the clock goes
        // below the label, and the buff row below the clock - all outside
        // the portrait frame's envelope.
        float labelBottom = portraitRect.y - FRAME_T - LABEL_H;
        clockRect.set(MARGIN, labelBottom - CLOCK_SIZE - 6f, CLOCK_SIZE, CLOCK_SIZE);
        buffRowY = clockRect.y - 8f - 16f * HUD_SCALE;

        // Burger at top-right.
        // Identical to the menu-screen burger (same size, inset, chrome, and
        // glyph) so it reads the same in-play and out (RL-27).
        burgerRect.set(w - MARGIN - UIVars.BURGER_SIZE, h - MARGIN - UIVars.BURGER_SIZE,
                UIVars.BURGER_SIZE, UIVars.BURGER_SIZE);

        // Look at bottom-left; auto-explore stacked directly above it.
        lookRect.set(MARGIN, MARGIN, ACTION_BTN, ACTION_BTN);
        autoRect.set(MARGIN, lookRect.y + lookRect.h + ACTION_GAP, ACTION_BTN, ACTION_BTN);

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

        // Burger menu - shared BurgerMenu geometry, so the in-game pause
        // menu reads identically to the V2Screen menu-screen dropdown.
        if (menu.open) menu.layout(ctx);
    }

    // -- Render passes -------------------------------------------------------
    private void renderShapesPass() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        Mob player = currentPlayer();

        // Status bars (HP + XP).
        if (player != null) {
            double maxHp = player.effectiveStats().maxHp;
            float hpFrac = maxHp > 0 ? (float) (player.hp / maxHp) : 0f;
            drawBar(s, hpBarRect, hpFrac, UIVars.TEXT_WARN);

            int xpBase = com.bjsp123.rl2.logic.MobProgression.xpToReach(player.characterLevel);
            int xpStep = com.bjsp123.rl2.logic.MobProgression.xpToAdvanceFrom(player.characterLevel);
            float xpFrac = xpStep > 0 ? (float)(player.xp - xpBase) / xpStep : 1f;
            // Unspent perk points: the XP bar pulses toward white in step with
            // the portrait so the "spend a point" nudge reads in both places.
            Color xpColor = UIVars.ACCENT;
            if (player.perkPoints > 0) {
                float k = perkPulse() * 0.45f;
                perkPulseColor.set(
                        UIVars.ACCENT.r + (1f - UIVars.ACCENT.r) * k,
                        UIVars.ACCENT.g + (1f - UIVars.ACCENT.g) * k,
                        UIVars.ACCENT.b + (1f - UIVars.ACCENT.b) * k, 1f);
                xpColor = perkPulseColor;
            }
            drawBar(s, xpBarRect, Math.max(0f, Math.min(1f, xpFrac)), xpColor);
        } else {
            drawBar(s, hpBarRect, 0f, UIVars.TEXT_WARN);
            drawBar(s, xpBarRect, 0f, UIVars.ACCENT);
        }
        drawTurnClock(s);

        // Unspent perk points: a pulsing accent frame around the portrait so
        // the player notices there's a point to spend. Drawn in the shapes
        // pass so the portrait sprite (text pass) sits inside the frame.
        if (player != null && player.perkPoints > 0) {
            float pulse = 0.35f + 0.65f * perkPulse();
            Color a = UIVars.ACCENT;
            s.setColor(a.r, a.g, a.b, pulse);
            Rect r = portraitRect;
            float t = FRAME_T;
            s.rect(r.x - t,      r.y - t,      r.w + 2 * t, t);           // bottom
            s.rect(r.x - t,      r.top(),      r.w + 2 * t, t);           // top
            s.rect(r.x - t,      r.y - t,      t,           r.h + 2 * t); // left
            s.rect(r.right(),    r.y - t,      t,           r.h + 2 * t); // right
        }

        // Buttons - chrome only; icons land in the SpriteBatch pass.
        // Action quickslots + inventory button carry item / chest icons,
        // so they paint with the paler SLOT_BG so the icon stays legible.
        for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) drawSlotBtn(s, actionRects[i], actionPressed[i]);
        drawSlotBtn(s, invRect, invPressed);
        // Look + burger have no item icon - plain HUD chrome.
        drawBtn(s, lookRect,   lookPressed);
        drawBtn(s, autoRect,   autoPressed || autoActive);
        drawBtn(s, burgerRect, burgerPressed);

        // Burger menu - modal column-of-buttons window centred on the
        // viewport. Dim everything behind it first, then the shared
        // BurgerMenu paints the window + item chrome.
        if (menu.open) {
            s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            menu.drawShapes(ctx);
        }

        // Buff duration drain bars - a vertical bar hugging each icon's right
        // edge, full height at BUFF_BAR_FULL_TURNS remaining, draining toward
        // the bottom (stacks double as remaining turns). At warn level the
        // bar turns red and blinks at ~2Hz so imminent expiry is glanceable.
        Mob barPlayer = currentPlayer();
        if (barPlayer != null && barPlayer.buffs != null && !barPlayer.buffs.isEmpty()) {
            float iconSz  = 16f * HUD_SCALE;
            float iconGap = BUFF_ICON_GAP * HUD_SCALE;
            float bx = MARGIN;
            float by = buffRowY;
            int max = Math.min(barPlayer.buffs.size(), 8);
            for (int i = 0; i < max; i++) {
                Buff b = barPlayer.buffs.get(i);
                if (b == null || b.type == null || b.stacks <= 0) continue;
                if (BuffIcons.regionFor(b.type) == null) continue;
                float frac = Math.min(b.stacks, UIVars.BUFF_BAR_FULL_TURNS)
                        / (float) UIVars.BUFF_BAR_FULL_TURNS;
                // Duration drain as an underline attached to the icon itself.
                // Deliberately neutral (no low-duration flash or warn colour):
                // buffs may be good, bad, or neutral, so "about to expire" is
                // not inherently a warning.
                float ix = bx + i * (iconSz + iconGap);
                s.setColor(UIVars.TEXT_DIM);
                s.rect(ix, by - 5f, iconSz * frac, 3f);
            }
        }

        // Burger glyph last so it stays bright above the menu dim - folded into
        // this pass instead of a third begin/end cycle (each ShapeRenderer
        // begin/end forces a GL flush; one fewer per frame).
        ButtonChrome.burgerGlyph(ctx, burgerRect, burgerPressed || menu.open);

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Draw an HP/XP bar - tri-line border, mid warm-grey backdrop,
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

    /** Bright additive pulse over a bar's interior while {@code flash} > 0,
     *  fading out as it decays. Called from the batch (text) pass; returns the
     *  decremented counter. Mirrors the mana-gain flash on the action slots. */
    private float drawBarFlash(Rect r, float flash) {
        if (flash <= 0f) return 0f;
        float alpha = (flash / BAR_FLASH_FRAMES) * 0.55f;
        ctx.batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE);
        ctx.batch.setColor(1f, 1f, 1f, alpha);
        ctx.batch.draw(ctx.whitePixel, r.x + 3, r.y + 3, r.w - 6, r.h - 6);
        ctx.batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.batch.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        return Math.max(0f, flash - 1f);
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

        // Ease the hand toward the real tick position each frame (called once
        // per render) so it sweeps continuously rather than snapping. tick is
        // monotonic within a run, so the eased value only ever moves forward;
        // a backward jump (new game / reset) snaps instead of unwinding.
        float targetTurns = tick / (float) TurnSystem.STANDARD_TURN_TICKS;
        if (!clockDisplayInit) {
            clockDisplayTurns = targetTurns;
            clockDisplayInit = true;
        } else if (targetTurns < clockDisplayTurns
                || targetTurns - clockDisplayTurns > CLOCK_MAX_LAG_TURNS) {
            // Reset, or too far behind to chase smoothly - clamp close and ease in.
            clockDisplayTurns = Math.min(targetTurns,
                    Math.max(clockDisplayTurns, targetTurns - CLOCK_MAX_LAG_TURNS));
        }
        float dt = Math.min(0.1f, Gdx.graphics.getDeltaTime());
        float alpha = 1f - (float) Math.exp(-dt * CLOCK_EASE_RATE);
        clockDisplayTurns += (targetTurns - clockDisplayTurns) * alpha;

        float frac = clockDisplayTurns - (float) Math.floor(clockDisplayTurns);
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

        // Portrait - the dedicated portrait art is blank for now, so use the TOP
        // HALF (head + torso) of the player's in-world sprite as a stand-in.
        Mob playerForPortrait = currentPlayer();
        if (playerForPortrait != null && playerForPortrait.characterClass != null) {
            // Expression timer counts down in real time; NEUTRAL when idle.
            expressionMs = Math.max(0f, expressionMs - Gdx.graphics.getDeltaTime() * 1000f);
            PortraitSprites.Expression expr = expressionMs > 0f
                    ? expression : PortraitSprites.Expression.NEUTRAL;
            TextureRegion face = PortraitSprites.regionFor(
                    playerForPortrait.characterClass, expr);
            if (face != null) {
                // Unspent perk points: the portrait breathes in step with the
                // frame + XP bar pulse.
                float grow = playerForPortrait.perkPoints > 0 ? perkPulse() * 2.5f : 0f;
                // ANGRY shudders side to side (decaying jitter); HAPPY hops up
                // (one quick parabolic bounce). Both real-time, ~a third of a
                // second, purely visual.
                float dx = 0f, dy = 0f;
                float t = expressionMs / EXPRESSION_MS;   // 1 -> 0 over the flash
                if (expressionMs > 0f && expression == PortraitSprites.Expression.ANGRY) {
                    dx = (float) Math.sin(expressionMs * 0.09f) * 2.5f * t;
                } else if (expressionMs > 0f && expression == PortraitSprites.Expression.HAPPY) {
                    dy = 4f * (float) Math.sin(Math.PI * Math.min(1f, 1f - t));
                }
                ctx.batch.draw(face,
                        portraitRect.x + 2f - grow + dx, portraitRect.y + 2f - grow + dy,
                        portraitRect.w - 4f + 2f * grow, portraitRect.h - 4f + 2f * grow);
            }
        }

        // "lv5 Rogue" in small writing under the portrait (moved off the xp
        // bar, RL-28). A perk-point star trails it (accent) when points are
        // unspent.
        if (playerForPortrait != null && playerForPortrait.characterClass != null) {
            boolean star = playerForPortrait.perkPoints > 0;
            if (portraitLabelCache == null
                    || portraitLabelLevelKey != playerForPortrait.characterLevel
                    || portraitLabelStarKey != star
                    || portraitLabelClassKey != playerForPortrait.characterClass) {
                String s = "lv" + playerForPortrait.characterLevel + " "
                        + playerForPortrait.characterClass.displayName();
                portraitLabelCache = star ? s + " *" : s;
                portraitLabelLevelKey = playerForPortrait.characterLevel;
                portraitLabelStarKey = star;
                portraitLabelClassKey = playerForPortrait.characterClass;
            }
            String lbl = portraitLabelCache;
            com.badlogic.gdx.graphics.Color c = playerForPortrait.perkPoints > 0
                    ? UIVars.ACCENT : UIVars.TEXT_DIM;
            com.badlogic.gdx.graphics.g2d.BitmapFont f = ctx.fontRegular;
            float prevScale = f.getScaleX();
            f.getData().setScale(prevScale * 0.7f);
            // Below the pulse-frame envelope so the frame never covers it.
            TextDraw.centre(ctx, f, c, lbl, portraitRect.cx(),
                    portraitRect.y - FRAME_T - 2f);
            f.getData().setScale(prevScale);
            f.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        }

        // Character level now renders as "lv5 Rogue" under the portrait
        // (see the portrait block above), not as a bare number on the xp bar.

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
            for (int i = 0; i < n; i++) {
                Rect r = actionRects[i];
                // Hotkey label: 1-9, then 0 / A / B for slots 10-12 (matching
                // GameInput's key mapping).
                String label = com.bjsp123.rl2.ui.hud.ActionBar.slotLabel(i);
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

        // HP / XP bar gain-flash (RL-24) - bright additive pulse when the
        // value rises (heal / xp pickup), mirroring the mana-gain flash above.
        Mob barP = currentPlayer();
        if (barP != null) {
            if (prevHp >= 0 && barP.hp > prevHp + 0.01) hpFlash = BAR_FLASH_FRAMES;
            // Any hp LOSS flashes the angry portrait + shudder - one detection
            // point covers melee, projectiles, DOTs, and environment alike.
            if (prevHp >= 0 && barP.hp < prevHp - 0.01) flashAngry();
            prevHp = barP.hp;
            if (prevXp >= 0 && barP.xp > prevXp + 0.01) xpFlash = BAR_FLASH_FRAMES;
            prevXp = barP.xp;
        }
        hpFlash = drawBarFlash(hpBarRect, hpFlash);
        xpFlash = drawBarFlash(xpBarRect, xpFlash);

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

        // Auto-explore button - compass icon (the MAP icon belongs to the
        // actual map in the burger); tinted accent while active.
        TextureRegion auto = IconSprites.regionFor(IconSprites.Icon.COMPASS);
        if (auto != null) {
            if (autoActive) ctx.batch.setColor(UIVars.ACCENT);
            ctx.batch.draw(auto,
                    autoRect.x + ICON_PAD, autoRect.y + ICON_PAD,
                    autoRect.w - 2 * ICON_PAD, autoRect.h - 2 * ICON_PAD);
            if (autoActive) ctx.batch.setColor(1f, 1f, 1f, 1f);
        }

        // Revive charms (Jade Peaches) - shown only when the player carries some.
        // Formatted strings are cached against their inputs (see field block) -
        // TextCatalog.format per frame is measurable GC pressure on the web build.
        int charms = reviveCharmCount(currentPlayer());
        if (charms > 0) {
            if (revivesKey != charms) {
                revivesTextCache = TextCatalog.format("ui.hud.revives", TextCatalog.vars("n", charms));
                revivesKey = charms;
            }
            TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.ACCENT,
                    revivesTextCache,
                    clockRect.right() + 5f, clockRect.y + clockRect.h * 0.5f + 5f,
                    Math.max(40f, ctx.worldW() - clockRect.right() - 2f * MARGIN));
        }

        // Player buff icons row - under the status line, anchored at the
        // top-left edge so it shares the bars cluster's anchor. Caps at
        // 8 visible buffs to avoid the row bleeding under the right-side
        // chrome on a narrow viewport.
        Mob p = currentPlayer();
        buffIconRects.clear();
        buffIconList.clear();
        if (p != null && p.buffs != null && !p.buffs.isEmpty()) {
            float iconSz = 16f * HUD_SCALE;
            float iconGap = BUFF_ICON_GAP * HUD_SCALE;
            float bx = MARGIN;
            float by = buffRowY;
            int max = Math.min(p.buffs.size(), 8);
            for (int i = 0; i < max; i++) {
                Buff b = p.buffs.get(i);
                if (b == null || b.type == null) continue;
                var region = BuffIcons.regionFor(b.type);
                if (region == null) continue;
                float ix = bx + i * (iconSz + iconGap);
                ctx.batch.draw(region, ix, by, iconSz, iconSz);
                // Pooled Rect - the hit-test list is rebuilt every frame, the
                // Rect objects themselves are not.
                Rect r = buffRectPool[i];
                if (r == null) r = buffRectPool[i] = new Rect();
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
            // and stack older ones upward. Index-walk the log directly -
            // tail() would allocate a sublist wrapper every frame.
            int size = EventLog.size();
            int floor = Math.max(0, size - maxLines * 4);
            int lines = 0;
            for (int i = size - 1; i >= floor && lines < maxLines; i--) {
                LogEvent e = EventLog.get(i);
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

        // Burger menu items - shared BurgerMenu labels, matching the
        // V2Screen menu-screen burger style.
        if (menu.open) menu.drawLabels(ctx);

        ctx.batch.end();
    }

    private Mob currentPlayer() {
        return playerSupplier != null ? playerSupplier.get() : null;
    }

    /** Total Jade Peach revive charms carried (summed stack counts). */
    private static int reviveCharmCount(Mob player) {
        if (player == null || player.inventory == null || player.inventory.bag == null) return 0;
        int n = 0;
        for (Item it : player.inventory.bag) {
            if (it != null && it.revivesOnDeath) n += Math.max(1, it.count);
        }
        return n;
    }

    // -- Long-press help -------------------------------------------------------
    /** One long-pressable HUD element. Exactly one of the three shapes:
     *  a plain chrome element ({@code key} set, e.g. {@code "hud.lookBtn"}),
     *  an action quickslot with a bound item ({@code key} + {@code item}),
     *  or a buff icon ({@code buff} set, {@code key} null - the caller
     *  reuses the buff-info popup instead of a help string). */
    public static final class HelpHit {
        public final String key;
        public final Item item;
        public final Buff buff;
        HelpHit(String key, Item item, Buff buff) {
            this.key = key;
            this.item = item;
            this.buff = buff;
        }
    }

    /** Hit-test ({@code vx},{@code vy}) (virtual coords) against every HUD
     *  element for the long-press help gesture. Returns {@code null} when
     *  the point misses all HUD chrome (the press belongs to the world) or
     *  while the burger drop-down is open. Rects are the same ones the
     *  input processor uses - single source of truth. */
    public HelpHit helpHitAt(float vx, float vy) {
        if (menu.open) return null;
        if (portraitRect.contains(vx, vy)) return new HelpHit("hud.portrait", null, null);
        if (hpBarRect.contains(vx, vy))    return new HelpHit("hud.hpbar", null, null);
        if (xpBarRect.contains(vx, vy))    return new HelpHit("hud.xpbar", null, null);
        if (clockRect.contains(vx, vy))    return new HelpHit("hud.clock", null, null);
        if (burgerRect.contains(vx, vy))   return new HelpHit("hud.burger", null, null);
        if (lookRect.contains(vx, vy))     return new HelpHit("hud.lookBtn", null, null);
        if (autoRect.contains(vx, vy))     return new HelpHit("hud.autoexploreBtn", null, null);
        if (invRect.contains(vx, vy))      return new HelpHit("hud.inventoryBtn", null, null);
        for (int i = 0; i < buffIconRects.size(); i++) {
            if (buffIconRects.get(i).contains(vx, vy) && i < buffIconList.size()) {
                return new HelpHit(null, null, buffIconList.get(i));
            }
        }
        for (int i = 0; i < com.bjsp123.rl2.ui.skin.Settings.quickslotCount(); i++) {
            if (actionRects[i].contains(vx, vy)) {
                // A bound slot describes THAT item; an empty one describes
                // what quickslots are.
                Item bound = actionBar != null ? actionBar.get(i) : null;
                return new HelpHit("hud.actionslot", bound, null);
            }
        }
        return null;
    }

    /** Reset every held-button highlight. Called when a long-press fires -
     *  the release is swallowed upstream, so without this the pressed
     *  visual would stick until the next touch. */
    public void clearPressed() {
        burgerPressed = lookPressed = autoPressed = invPressed = false;
        buffIconPressed = -1;
        java.util.Arrays.fill(actionPressed, false);
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
                // button below. A tap outside the panel (and outside the
                // burger button) closes the menu without firing anything.
                if (menu.open) {
                    return menu.touchDown(vx, vy, burgerRect);
                }

                if (burgerRect.contains(vx, vy))   { burgerPressed = true; return true; }
                if (lookRect.contains(vx, vy))     { lookPressed = true;   return true; }
                if (autoRect.contains(vx, vy))     { autoPressed = true;   return true; }
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

                if (menu.hasPress()) {
                    // BurgerMenu fires the bound Destinations action itself.
                    menu.release(vx, vy);
                    return true;
                }

                if (burgerPressed) {
                    burgerPressed = false;
                    if (burgerRect.contains(vx, vy)) menu.open = !menu.open;
                    return true;
                }
                if (lookPressed) {
                    lookPressed = false;
                    if (lookRect.contains(vx, vy) && onLook != null) onLook.run();
                    return true;
                }
                if (autoPressed) {
                    autoPressed = false;
                    if (autoRect.contains(vx, vy) && onAutoExplore != null) onAutoExplore.run();
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
