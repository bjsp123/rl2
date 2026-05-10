package com.bjsp123.rl2.ui.v2;

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
        float winW = Math.min(380f, vw - 24f);
        float winH = Math.min(Pal.VIRTUAL_H - 120f, vh - 120f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        back   = new BackBtn(ctx, game::popScreen);
        back.anchorBottomRightOf(window);
        burger = makeBurger();
        addBurgerItem("Arena Setup",
                () -> game.setRootScreen(new V2ArenaSetup(game)));
        addBurgerItem("Title",    () -> game.setRootScreen(new V2Title(game, ctx)));
        addBurgerItem("Settings", () -> game.pushScreen(new V2Settings(game, ctx)));
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();
        TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT, "Arena Hall",
                cx, window.top() - 22f);

        List<ArenaHallOfFameEntry> entries = game.arenaHallOfFame.entries;
        if (entries.isEmpty()) {
            TextDraw.centre(ctx, ctx.fontRegular, Pal.DIM,
                    "No matchups recorded yet.",
                    cx, window.top() - 72f);
            return;
        }

        float left  = window.x + 14f;
        float right = window.right() - 14f;
        float headerY = window.top() - 72f;
        TextDraw.left (ctx, ctx.fontRegular, Pal.DIM, "When  /  Match", left,  headerY);
        TextDraw.right(ctx, ctx.fontRegular, Pal.DIM, "Survivors",      right, headerY);

        // Each entry takes a ~40 px block (timestamp line + match/result line).
        float blockH = 40f;
        float visibleTop    = headerY - 22f;
        float visibleBottom = window.y + 14f;
        float visibleH      = visibleTop - visibleBottom;
        scroller.setMaxScroll(entries.size() * blockH - visibleH);

        for (int i = 0; i < entries.size(); i++) {
            float yTop = visibleTop - i * blockH + scroller.scrollY();
            if (yTop <= visibleBottom) break;
            if (yTop > visibleTop) continue;
            ArenaHallOfFameEntry e = entries.get(i);
            String when   = TS_FMT.format(new Date(e.timestampMillis));
            String match  = e.teamADescription + " vs " + e.teamBDescription;
            String result = "A " + e.teamASurvivors + "  /  B " + e.teamBSurvivors;
            TextDraw.left (ctx, ctx.fontRegular, Pal.WHITE, when, left, yTop);
            float row2 = yTop - 16f;
            if (row2 > visibleBottom) {
                TextDraw.left (ctx, ctx.fontRegular, Pal.DIM,   match,  left,  row2);
                TextDraw.right(ctx, ctx.fontRegular, Pal.WHITE, result, right, row2);
            }
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
