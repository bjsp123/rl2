package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.save.SaveSystem.SaveMetadata;

/**
 * V2 title screen - game logo at the top, vertical column of chunky buttons
 * filling the centre, burger top-right. No back button (this is the root).
 *
 * <p>Layout follows CLAUDE.md's "vertical list of large buttons" rule -
 * every entry is the same width (a viewport-clamped {@code btnW}, max 220) x
 * {@code TITLE_BTN_H} (42), stacked with a small gap, centred horizontally.
 */
public final class V2Title extends V2Screen {

    private final Rl2Game game;
    /** Layout rect around the floating button stack. No outer window is drawn. */
    private final Rect window = new Rect();
    private static final float WINDOW_PAD = 10f;
    private static final float TITLE_GAP  = 10f;
    private static final float BTN_GAP    = 8f;
    private static final float TITLE_BTN_H = 42f;

    public V2Title(Rl2Game game, UiCtx ctx) {
        super(ctx);
        this.game = game;
    }

    @Override
    protected void buildLayout() {
        float maxBtnW = Math.max(120f, ctx.worldW() - 32f);
        float btnW    = Math.min(220f, maxBtnW);

        // Account entry only on platforms with identity (web with Supabase
        // configured); desktop/android show the classic six buttons.
        boolean hasAccount = game.services.auth.isAvailable();
        int n = hasAccount ? 7 : 6;
        float colH = n * TITLE_BTN_H + (n - 1) * BTN_GAP;
        float headerH = ctx.fontHeader.getCapHeight() + 24f;
        float winH = WINDOW_PAD * 2 + headerH + TITLE_GAP + colH;
        float winW = btnW + WINDOW_PAD * 2;
        float winX = (ctx.worldW() - winW) * 0.5f;
        float winY = (ctx.worldH() - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        // Find the most-recently-saved slot (highest timestampMillis).
        int continueSlot = -1;
        long latestTime  = 0;
        for (int i = 0; i < SaveSystem.SLOTS; i++) {
            SaveMetadata m = game.saveSystem.metadata(i);
            if (m != null && m.timestampMillis > latestTime) {
                latestTime   = m.timestampMillis;
                continueSlot = i;
            }
        }
        final int slot   = continueSlot;
        boolean  hasSave = slot >= 0;

        float btnX = winX + WINDOW_PAD;
        float y    = winY + WINDOW_PAD;

        // Bottom → top (libGDX y-up): Quit is lowest, Continue/New Game highest.
        addBtn(TextCatalog.get("ui.title.quit"), btnX, y, btnW, Gdx.app::exit);
        y += TITLE_BTN_H + BTN_GAP;
        if (hasAccount) {
            addBtn(TextCatalog.get("ui.title.account"), btnX, y, btnW,
                    () -> game.pushScreen(new V2Account(game, ctx)));
            y += TITLE_BTN_H + BTN_GAP;
        }
        addBtn(TextCatalog.get("ui.title.settings"), btnX, y, btnW,
                () -> game.pushScreen(new V2Settings(game, ctx)));
        y += TITLE_BTN_H + BTN_GAP;
        addBtn(TextCatalog.get("ui.title.arena"), btnX, y, btnW,
                () -> game.pushScreen(new V2ArenaSetup(game)));
        y += TITLE_BTN_H + BTN_GAP;
        addBtn(TextCatalog.get("ui.title.hallOfFame"), btnX, y, btnW,
                () -> game.pushScreen(new V2HallOfFame(game)));
        y += TITLE_BTN_H + BTN_GAP;
        addBtn(TextCatalog.get("ui.title.savedGames"), btnX, y, btnW,
                () -> game.pushScreen(new V2Saves(game, ctx)));
        y += TITLE_BTN_H + BTN_GAP;
        String topLabel = TextCatalog.get(hasSave ? "ui.title.continue" : "ui.title.newGame");
        Runnable topAction = hasSave
                ? () -> {
                    com.bjsp123.rl2.model.World w = game.saveSystem.load(slot);
                    if (w != null) game.setRootScreen(
                            new com.bjsp123.rl2.screen.PlayScreen(game, slot, w));
                    else game.pushScreen(new V2Saves(game, ctx));
                  }
                : () -> game.pushScreen(new V2Saves(game, ctx));
        addBtn(topLabel, btnX, y, btnW, topAction);

        if (game.music != null) game.music.play(com.bjsp123.rl2.audio.MusicPlayer.Track.TITLE);
        // Burger at top-right; no back button (root screen). Standard items,
        // title variant: Settings / Encyclopedia / Credits, no Main Menu.
        burger = makeBurger();
        addStandardBurgerItems(game, true);
    }

    private void addBtn(String label, float x, float y, float w, Runnable onClick) {
        Btn b = new Btn(label, x, y, w, TITLE_BTN_H, onClick).header();
        buttons.add(b);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        drawEdgeGlow(s);
        for (Btn b : buttons) drawButtonGlow(s, b);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        // Header inside the window - positioned a fixed offset down from
        // the window's top edge.
        float headerY = window.top() - WINDOW_PAD;
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                        TextCatalog.get("ui.title.logo"), window.cx(), headerY);
        // App version / build, bottom-left corner.
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                "v" + com.bjsp123.rl2.util.AppVersion.label(), 10f, 10f + ctx.lineH());
    }

    private void drawButtonGlow(ShapeRenderer s, Btn b) {
        // Many thin concentric rings at a low constant alpha, drawn
        // outer -> inner. The overlap accumulates so the halo is darkest
        // hugging the button and fades smoothly to nothing at the edge,
        // over a narrow spread (no hard outer band, no visible steps).
        int n = 8;
        float spread = 6f;
        for (int i = n; i >= 1; i--) {
            float grow = spread * i / n;
            s.setColor(0f, 0f, 0f, 0.05f);
            s.rect(b.rect.x - grow, b.rect.y - grow,
                    b.rect.w + grow * 2f, b.rect.h + grow * 2f);
        }
    }

    private void drawEdgeGlow(ShapeRenderer s) {
        float w = ctx.worldW();
        float h = ctx.worldH();
        float[] sizes = { 96f, 64f, 36f, 18f };
        float[] alphas = { 0.18f, 0.16f, 0.14f, 0.12f };
        for (int i = 0; i < sizes.length; i++) {
            float d = sizes[i];
            s.setColor(0f, 0f, 0f, alphas[i]);
            s.rect(0f, 0f, w, d);
            s.rect(0f, h - d, w, d);
            s.rect(0f, 0f, d, h);
            s.rect(w - d, 0f, d, h);
        }
    }
}
