package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.save.ArenaHallOfFameEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** V2 arena hall-of-fame screen — list of past arena matches. */
public final class V2ArenaHallOfFame extends V2Screen {

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final Color WIN_A_COLOR = new Color(0.4f, 0.85f, 0.4f, 1f);

    private final Rl2Game game;
    private final Rect window = new Rect();
    private final Scroller scroller = new Scroller();

    public V2ArenaHallOfFame(Rl2Game game) {
        super(game.ui);
        this.game = game;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(380f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(UIVars.VIRTUAL_H - 120f, vh - 120f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
        addBurgerItem("Arena Setup",
                () -> game.setRootScreen(new V2ArenaSetup(game)));
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();
        float lh = ctx.lineH();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Arena Hall",
                cx, window.top() - ctx.headerLineH());

        List<ArenaHallOfFameEntry> entries = game.arenaHallOfFame.entries;
        if (entries.isEmpty()) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    "No matchups recorded yet.",
                    cx, window.top() - headerBandH() - lh);
            return;
        }

        float badgeSz     = lh * 2f;
        float badgeX      = window.x + 14f;
        float contentLeft = badgeX + badgeSz + 8f;
        float right       = window.right() - 14f;

        float headerY = window.top() - headerBandH() - lh * 0.5f;
        TextDraw.left (ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Match",     contentLeft, headerY);
        TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Survivors", right,       headerY);

        float rowH          = lh * 2.5f;
        float visibleTop    = headerY - lh;
        float visibleBottom = window.y + UIVars.BACK_SIZE + 2 * BackBtn.INSET;
        float visibleH      = visibleTop - visibleBottom;
        scroller.setMaxScroll(Math.max(0f, entries.size() * rowH - visibleH));

        for (int i = 0; i < entries.size(); i++) {
            float yTop = visibleTop - i * rowH + scroller.scrollY();
            if (yTop - rowH > visibleTop) continue;
            if (yTop < visibleBottom) break;

            ArenaHallOfFameEntry e = entries.get(i);

            Color badgeColor;
            String badgeLetter;
            if (e.winner == 1) {
                badgeColor  = WIN_A_COLOR;
                badgeLetter = "A";
            } else if (e.winner == 2) {
                badgeColor  = UIVars.ACCENT;
                badgeLetter = "B";
            } else {
                badgeColor  = UIVars.TEXT_DIM;
                badgeLetter = "=";
            }
            float badgeBottom = yTop - badgeSz;
            ctx.batch.setColor(badgeColor);
            ctx.batch.draw(ctx.whitePixel, badgeX, badgeBottom, badgeSz, badgeSz);
            ctx.batch.setColor(Color.WHITE);
            TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_BODY, badgeLetter,
                    badgeX + badgeSz * 0.5f, badgeBottom + badgeSz * 0.5f + lh * 0.25f);

            String when   = TS_FMT.format(new Date(e.timestampMillis));
            String match  = e.teamADescription + " vs " + e.teamBDescription;
            String result = "A " + e.teamASurvivors + "  /  B " + e.teamBSurvivors;
            TextDraw.left (ctx, ctx.fontRegular, UIVars.TEXT_DIM,  when,   contentLeft, yTop - lh * 0.8f);
            TextDraw.left (ctx, ctx.fontRegular, UIVars.TEXT_BODY, match,  contentLeft, yTop - lh * 1.8f);
            TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY, result, right,       yTop - lh * 1.8f);
        }
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        if (!window.contains(vx, vy)) return false;
        scroller.onTouchDown(vy);
        return true;
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        return scroller.onTouchDragged(vy);
    }

    @Override
    protected boolean onScrolled(float amountY) {
        scroller.onScrolled(amountY, 40f);
        return true;
    }

    @Override
    protected void onEscape() { game.popScreen(); }
}
