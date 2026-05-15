package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.save.SaveMetadata;
import com.bjsp123.rl2.save.SaveSystem;

/**
 * V2 title screen - game logo at the top, vertical column of chunky buttons
 * filling the centre, burger top-right. No back button (this is the root).
 *
 * <p>Layout follows CLAUDE.md's "vertical list of large buttons" rule -
 * every entry is the same {@link UIVars#BTN_W} x {@link UIVars#BTN_H}, stacked
 * with a small gap, centred horizontally on the viewport.
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

        int n = 6;
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

        // Burger at top-right; no back button (root screen).
        burger = makeBurger();
        addBurgerItem(TextCatalog.get("ui.menu.settings"), () -> game.pushScreen(new V2Settings(game, ctx)));
        addBurgerItem(TextCatalog.get("ui.menu.credits"),  () -> game.pushScreen(new V2Credits(game)));
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
    }

    private void drawButtonGlow(ShapeRenderer s, Btn b) {
        for (int i = 4; i >= 1; i--) {
            float grow = i * 5f;
            float alpha = 0.08f + i * 0.035f;
            s.setColor(0f, 0f, 0f, alpha);
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
