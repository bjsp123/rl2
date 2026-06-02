package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;

/** V2 credits screen - single window with a few centred text lines. */
public final class V2Credits extends V2Screen {

    private final Rl2Game game;
    private final Rect window = new Rect();

    public V2Credits(Rl2Game game) {
        super(game.ui);
        this.game = game;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(320f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(360f, vh - 120f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();
        float top = window.top() - ctx.headerLineH();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.credits.title"), cx, top);
        top -= ctx.headerLineH() * 2f;
        String[] lines = {
                TextCatalog.get("ui.credits.line.game"),
                "",
                TextCatalog.get("ui.credits.line.engine"),
                TextCatalog.get("ui.credits.line.code"),
                TextCatalog.get("ui.credits.line.font"),
                TextCatalog.get("ui.credits.line.fontBy"),
                "",
                TextCatalog.get("ui.credits.line.thanks"),
        };
        for (String s : lines) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY, s, cx, top);
            top -= ctx.lineH();
        }
    }
}
