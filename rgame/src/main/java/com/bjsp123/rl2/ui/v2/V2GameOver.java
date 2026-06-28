package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

import java.util.ArrayList;
import java.util.List;

/** V2 game-over screen - shown when the player dies. */
public final class V2GameOver extends V2Screen {

    private static final Color DIM_WARN  = new Color(0.9f, 0.3f, 0.3f, 1f);
    private static final Color VICTORY_HL = new Color(1f, 0.85f, 0.4f, 1f);  // warm gold

    private final Rl2Game        game;
    private final HallOfFameEntry record;
    private final V2Log           log;
    /** When non-null this screen is a Hall-of-Fame detail view (pushed, not a
     *  root death screen): adds a back button and ESC returns here instead of
     *  the title. */
    private final Runnable        onBack;

    // Layout rects - computed once in buildLayout().
    private final Rect window   = new Rect();
    private final Rect portrait = new Rect();
    private final Rect deathLogFrame = new Rect();
    private float nameY, statsY;
    /** Body tabs: the death log (0) and the score breakdown (1). */
    private final TabStrip tabs = new TabStrip(2);
    private int activeTab = 0;
    private static final String[] TAB_LABELS = { "Log", "Score" };

    public V2GameOver(Rl2Game game, HallOfFameEntry record) {
        this(game, record, null);
    }

    /** Detail-view constructor (Hall of Fame): {@code onBack} returns to the
     *  list via back button / ESC. */
    public V2GameOver(Rl2Game game, HallOfFameEntry record, Runnable onBack) {
        super(game.ui);
        this.game   = game;
        this.record = record;
        this.onBack = onBack;
        this.log    = new V2Log(game.ui);
    }

    // -- V2Screen lifecycle ----------------------------------------------------

    @Override
    public void show() {
        super.show();
        Gdx.input.setInputProcessor(new InputMultiplexer(log.input(), baseInput()));
    }

    @Override
    protected void buildLayout() {
        if (onBack != null) back = new BackBtn(ctx, onBack);
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(340f, vw - UIVars.PAD_MODAL);
        // Size the window dynamically so the TALLER of the two tabs (score / log)
        // has room for all its text, instead of clipping against a fixed height.
        // The fixed chrome above the body frame (title + portrait + name + stats +
        // tab strip) and the button band below it sum to a constant; the body
        // frame is then sized to its content. buildLayout's top-down placement
        // reproduces exactly this frame height from winH, so only winH changes.
        // Sized to the max of both tabs so switching tabs never needs a re-layout.
        float topStackH = 2f * ctx.headerLineH() + 2f * ctx.lineH() + 128f;
        float winH = topStackH + neededBodyH(winW) + 80f;
        winH = Math.max(380f, Math.min(winH, vh - 60f));
        float winX = (vw - winW) * 0.5f;
        float winY = (vh - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        // Portrait box - centred, below "YOU DIED" title.
        float portSz  = 72f;
        float portX   = winX + (winW - portSz) * 0.5f;
        float portTop = winY + winH - headerBandH() - 16f;
        portrait.set(portX, portTop - portSz, portSz, portSz);

        // Text anchor Ys - derived from live font metrics so they scale with UiFontScale.
        nameY  = portrait.y - ctx.spacerLargeY();
        statsY = nameY  - ctx.headerLineH();

        // Buttons - bottom of window.
        float btnH    = 52f;
        float btnGap  = 8f;
        float iconSz  = 52f;
        float btnY    = winY + 16f;

        // Tab strip (Log / Score) above the body panel; the panel shows the
        // selected tab's content.
        float frameX = winX + 12f;
        float frameTopY = statsY - ctx.lineH();
        float frameBottomY = btnY + btnH + 12f;
        float tabH = 26f;
        float tabY = frameTopY - tabH;
        tabs.layout(window, 12f, tabY, tabH, 6f);
        tabs.setActive(activeTab);
        float frameH = Math.max(40f, (tabY - 6f) - frameBottomY);
        deathLogFrame.set(frameX, frameBottomY, winW - 24f, frameH);
        float mainW   = winW - iconSz - btnGap - 32f;
        float mainX   = winX + 16f;
        float iconX   = mainX + mainW + btnGap;

        Btn mainMenu = new Btn(TextCatalog.get("ui.gameOver.mainMenu"), mainX, btnY, mainW, btnH,
                () -> game.setRootScreen(new V2Title(game, ctx))).header();
        buttons.add(mainMenu);

        Btn logBtn = new Btn("", iconX, btnY, iconSz, iconSz,
                () -> { log.open(); });
        logBtn.icon = IconSprites.regionFor(IconSprites.Icon.BOOK);
        buttons.add(logBtn);
    }

    /** Body-frame height needed to fit the taller of the two tabs' content, in
     *  the current font scale. Drives the window's dynamic height so no text is
     *  clipped. Mirrors the row counts of {@link ScoreBreakdown#draw} and the
     *  death-log layout in {@link #drawBodyText}. */
    private float neededBodyH(float winW) {
        float lineH = ctx.lineH();
        // Score tab: mobs / gems / food / beacons (+ wraith, + perfect) +
        // difficulty + TOTAL header line.
        int scoreRows = 4;
        if (record.killedGreatWraith) scoreRows++;
        if (record.allBeaconsLit)     scoreRows++;
        scoreRows++;                                    // difficulty row
        float scoreH = 12f                              // top inset
                + scoreRows * (lineH + 6f)
                + 4f                                    // difficulty's wider gap
                + ctx.headerLineH()                     // TOTAL (header font)
                + 12f;                                  // bottom inset
        // Log tab: wrapped headline (<=3 lines) + recent log lines + insets.
        float maxW = (winW - 24f) - 16f;
        int headLines = 0;
        if (record.deathHeadline != null && !record.deathHeadline.isEmpty()) {
            List<String> hw = new ArrayList<>();
            TextDraw.wrap(ctx.fontRegular, record.deathHeadline, maxW, 3, hw);
            headLines = hw.size();
        }
        int logLines = (record.deathLog != null && !record.deathLog.isEmpty())
                ? record.deathLog.size()
                : ((record.deathMessage != null && !record.deathMessage.isEmpty()) ? 1 : 0);
        float logH = 8f + headLines * lineH + (headLines > 0 ? 4f : 0f)
                + logLines * lineH + 8f;
        return Math.max(scoreH, logH);
    }

    /** Wall-clock accumulator for the swirl backdrop. */
    private float swirlT;

    @Override
    protected void drawBackground(float delta) {
        // No attract-mode demo behind the death screen - the primal swirls over a
        // dark void instead.
        swirlT += delta;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setProjectionMatrix(ctx.camera.combined);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0.04f, 0.04f, 0.06f, 1f);
        s.rect(0f, 0f, ctx.worldW(), ctx.worldH());
        s.end();
        ctx.batch.setProjectionMatrix(ctx.camera.combined);
        ctx.batch.begin();
        SwirlBackground.render(ctx.batch, 0f, 0f, ctx.worldW(), ctx.worldH(), swirlT);
        ctx.batch.end();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Portrait frame - a simple recessed rect slightly larger than the portrait.
        float pad = 4f;
        ctx.shapes.setColor(UIVars.SLOT_RECESS);
        ctx.shapes.rect(portrait.x - pad, portrait.y - pad,
                portrait.w + 2f * pad, portrait.h + 2f * pad);

        // Body panel - inset panel that visually groups the active tab's
        // content. Same recessed treatment as the portrait frame.
        ctx.shapes.setColor(UIVars.SLOT_RECESS);
        ctx.shapes.rect(deathLogFrame.x, deathLogFrame.y,
                deathLogFrame.w, deathLogFrame.h);
        tabs.drawShapes(ctx.shapes);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();

        // Title: gold VICTORY (or PERFECT VICTORY) on a win, red YOU DIED on death.
        String titleKey = record.victory
                ? (record.allBeaconsLit ? "ui.victory.titlePerfect" : "ui.victory.title")
                : "ui.gameOver.title";
        TextDraw.centre(ctx, ctx.fontHeader, record.victory ? VICTORY_HL : DIM_WARN,
                TextCatalog.get(titleKey), cx,
                window.top() - ctx.headerLineH());

        // Portrait.
        CharacterClass cls = parseClass(record.charClass);
        if (cls != null) {
            TextureRegion p = PortraitSprites.regionFor(cls);
            if (p != null) {
                ctx.batch.draw(p, portrait.x, portrait.y, portrait.w, portrait.h);
            }
        }

        // Class name.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_BODY, record.charClass, cx, nameY);

        // Stats: victory shows beacons + score; death shows score + depth.
        String stats = record.victory
                ? TextCatalog.format("ui.victory.stats",
                        TextCatalog.vars("beacons", record.beaconsLit, "score", record.score))
                : TextCatalog.format("ui.gameOver.stats",
                        TextCatalog.vars("score", record.score, "depth", record.depth));
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM, stats, cx, statsY);

        // Tab labels (Log / Score).
        for (int i = 0; i < tabs.rects.length; i++) {
            Rect tr = tabs.rects[i];
            TextDraw.centre(ctx, ctx.fontRegular,
                    i == activeTab ? UIVars.ACCENT : UIVars.TEXT_DIM,
                    TAB_LABELS[i], tr.cx(), tr.cy() + ctx.fontRegular.getCapHeight() * 0.5f);
        }
        if (activeTab == 1) {
            ScoreBreakdown.draw(ctx, deathLogFrame, record);
            return;
        }

        // Death log inside the framed panel. Left-justified, oldest at top,
        // cause-of-death at the bottom rendered in full body-bright; older
        // entries fade with age (100% -> 25% alpha by the topmost line) so
        // the eye is drawn to the killing blow. Falls back to the single-
        // line deathMessage when deathLog is empty (legacy saves).
        List<String> lines = record.deathLog;
        if ((lines == null || lines.isEmpty())
                && record.deathMessage != null && !record.deathMessage.isEmpty()) {
            lines = new ArrayList<>();
            lines.add(record.deathMessage);
        }
        // Headline ("what killed you") at the top of the death-log frame,
        // bright + warning-tinted. Composed by PlayScreen from the last
        // fatal DamageCause captured by MobSystem.processAttack. Empty for
        // legacy saves - in which case the log lines pack to the top.
        float inset = 8f;
        float maxW = deathLogFrame.w - 2f * inset;
        float headerEndY = deathLogFrame.y + deathLogFrame.h - inset;
        if (record.deathHeadline != null && !record.deathHeadline.isEmpty()) {
            List<String> hwrapped = new ArrayList<>();
            TextDraw.wrap(ctx.fontRegular, record.deathHeadline, maxW, 3, hwrapped);
            float headLineH = ctx.fontRegular.getLineHeight() + 2f;
            float hy = headerEndY;
            for (String line : hwrapped) {
                TextDraw.centre(ctx, ctx.fontRegular, UIVars.WARN_HL,
                        line, deathLogFrame.cx(), hy);
                hy -= headLineH;
            }
            // Reserve the headline band; the log starts one extra line below.
            headerEndY = hy - 4f;
        }
        if (lines != null && !lines.isEmpty()) {
            float lineH = ctx.fontRegular.getLineHeight() + 2f;
            float textX = deathLogFrame.x + inset;
            float y = headerEndY;
            int n = lines.size();
            Color tmp = new Color();
            for (int idx = 0; idx < n; idx++) {
                String entry = lines.get(idx);
                if (entry == null || entry.isEmpty()) continue;
                List<String> wrapped = new ArrayList<>();
                TextDraw.wrap(ctx.fontRegular, entry, maxW, 2, wrapped);
                boolean isCause = idx == n - 1;
                // Age fade: idx=n-1 (cause) -> 1.0, oldest -> 0.25 (linear).
                float ageT = n <= 1 ? 1f : (idx / (float) (n - 1));
                float alpha = 0.25f + 0.75f * ageT;
                Color base = isCause ? UIVars.TEXT_BODY : UIVars.TEXT_DIM;
                tmp.set(base.r, base.g, base.b, alpha);
                for (String line : wrapped) {
                    if (y < deathLogFrame.y + inset) break;
                    TextDraw.left(ctx, ctx.fontRegular, tmp, line, textX, y);
                    y -= lineH;
                }
            }
        }
    }

    @Override
    public void render(float delta) {
        super.render(delta);
        if (log.isOpen()) log.render();
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        return tabs.touchDown(vx, vy) >= 0;
    }

    @Override
    protected boolean onTouchUpInBody(float vx, float vy) {
        int t = tabs.touchUp(vx, vy);
        if (t >= 0) { activeTab = t; tabs.setActive(t); return true; }
        return false;
    }

    @Override
    protected void onEscape() {
        if (onBack != null) onBack.run();
        else game.setRootScreen(new V2Title(game, ctx));
    }

    // -- Helpers ---------------------------------------------------------------

    private static CharacterClass parseClass(String name) {
        if (name == null) return null;
        for (CharacterClass c : CharacterClass.values()) {
            if (c.displayName().equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name))
                return c;
        }
        return null;
    }
}
