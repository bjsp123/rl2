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

    private static final Color DIM_WARN = new Color(0.9f, 0.3f, 0.3f, 1f);

    private final Rl2Game        game;
    private final HallOfFameEntry record;
    private final V2Log           log;

    // Layout rects - computed once in buildLayout().
    private final Rect window   = new Rect();
    private final Rect portrait = new Rect();
    private final Rect deathLogFrame = new Rect();
    private float nameY, statsY;

    public V2GameOver(Rl2Game game, HallOfFameEntry record) {
        super(game.ui);
        this.game   = game;
        this.record = record;
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
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(340f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(460f, vh - 80f);
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

        // Death-log frame - panel spanning the body between the stats line
        // and the button row. Inset by 12px on each side from the window.
        float frameX = winX + 12f;
        float frameTopY = statsY - ctx.lineH();
        float frameBottomY = btnY + btnH + 12f;
        float frameH = Math.max(40f, frameTopY - frameBottomY);
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

    @Override
    protected void drawBackground(float delta) {
        // No attract-mode demo behind the death screen - a flat, somber backdrop.
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setProjectionMatrix(ctx.camera.combined);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0.04f, 0.04f, 0.06f, 1f);
        s.rect(0f, 0f, ctx.worldW(), ctx.worldH());
        s.end();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Portrait frame - a simple recessed rect slightly larger than the portrait.
        float pad = 4f;
        ctx.shapes.setColor(UIVars.SLOT_RECESS);
        ctx.shapes.rect(portrait.x - pad, portrait.y - pad,
                portrait.w + 2f * pad, portrait.h + 2f * pad);

        // Death-log frame - inset panel that visually groups the rolling
        // log lines. Same recessed treatment as the portrait frame.
        ctx.shapes.setColor(UIVars.SLOT_RECESS);
        ctx.shapes.rect(deathLogFrame.x, deathLogFrame.y,
                deathLogFrame.w, deathLogFrame.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();

        // "YOU DIED" title.
        TextDraw.centre(ctx, ctx.fontHeader, DIM_WARN,
                TextCatalog.get("ui.gameOver.title"), cx,
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

        // Score + depth.
        String stats = TextCatalog.format("ui.gameOver.stats",
                TextCatalog.vars("score", record.score, "depth", record.depth));
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM, stats, cx, statsY);

        // Death log inside the framed panel. Left-justified, oldest at top,
        // cause-of-death at the bottom rendered in full body-bright; older
        // entries fade with age (90% -> 25% alpha by the topmost line) so
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
    protected void onEscape() {
        game.setRootScreen(new V2Title(game, ctx));
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
