package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.bjsp123.rl2.Rl2Game;

/**
 * V2 title screen — game logo at the top, vertical column of chunky buttons
 * filling the centre, burger top-right. No back button (this is the root).
 *
 * <p>Layout follows CLAUDE.md's "vertical list of large buttons" rule —
 * every entry is the same {@link UIVars#BTN_W} × {@link UIVars#BTN_H}, stacked
 * with a small gap, centred horizontally on the viewport.
 */
public final class V2Title extends V2Screen {

    private final Rl2Game game;

    /** Window rect — drawn behind the button column for visual coherence. */
    private final Rect window = new Rect();
    private static final float WINDOW_PAD = 16f;
    private static final float TITLE_GAP  = 12f;
    private static final float BTN_GAP    = 10f;

    public V2Title(Rl2Game game, UiCtx ctx) {
        super(ctx);
        this.game = game;
    }

    @Override
    protected void buildLayout() {
        // Button width clamps to the available world width minus a margin so
        // a UiScale-shrunk viewport still produces buttons that fit. At small
        // worlds the buttons grow narrower; at the design world (400 wide)
        // they keep their full UIVars.BTN_W = 320.
        float maxBtnW = Math.max(120f, ctx.worldW() - 32f);
        float btnW    = Math.min(UIVars.BTN_W, maxBtnW);

        // Six vertical buttons. Compute total column height first so we can
        // centre the window around them.
        int n = 6;
        float colH = n * UIVars.BTN_H + (n - 1) * BTN_GAP;
        // Reserve room above the column for a "rl2" header inside the window.
        float headerH = ctx.fontHeader.getCapHeight() + 24f;
        float winH = WINDOW_PAD * 2 + headerH + TITLE_GAP + colH;
        float winW = btnW + WINDOW_PAD * 2;
        float winX = (ctx.worldW() - winW) * 0.5f;
        float winY = (ctx.worldH() - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        // Place buttons from the bottom of the window upward — libGDX is
        // y-up so the first button drawn is the lowest one on screen, which
        // matches our vertical list (Quit at the bottom).
        float btnX = winX + WINDOW_PAD;
        float y = winY + WINDOW_PAD;

        addBtn("Quit",          btnX, y, btnW, Gdx.app::exit);
        y += UIVars.BTN_H + BTN_GAP;
        addBtn("Credits",       btnX, y, btnW,
                () -> game.pushScreen(new V2Credits(game)));
        y += UIVars.BTN_H + BTN_GAP;
        addBtn("Settings",      btnX, y, btnW,
                () -> game.pushScreen(new V2Settings(game, ctx)));
        y += UIVars.BTN_H + BTN_GAP;
        addBtn("Arena",         btnX, y, btnW,
                () -> game.pushScreen(new V2ArenaSetup(game)));
        y += UIVars.BTN_H + BTN_GAP;
        addBtn("Hall of Fame",  btnX, y, btnW,
                () -> game.pushScreen(new V2HallOfFame(game)));
        y += UIVars.BTN_H + BTN_GAP;
        addBtn("Saved Games",   btnX, y, btnW,
                () -> game.pushScreen(new V2Saves(game, ctx)));

        // Burger at top-right; no back button (root screen).
        burger = makeBurger();
        addBurgerItem("Settings", () -> game.pushScreen(new V2Settings(game, ctx)));
    }

    private void addBtn(String label, float x, float y, float w, Runnable onClick) {
        Btn b = new Btn(label, x, y, w, UIVars.BTN_H, onClick).header();
        buttons.add(b);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        // Header inside the window — positioned a fixed offset down from
        // the window's top edge.
        float headerY = window.top() - WINDOW_PAD;
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                        "rl2", window.cx(), headerY);
    }
}
