package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.save.ArenaHallOfFame.ArenaHallOfFameEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** V2 arena hall-of-fame screen - list of past arena matches. */
public final class V2ArenaHallOfFame extends V2Screen {

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final Color WIN_A_COLOR = new Color(0.4f, 0.85f, 0.4f, 1f);

    private final Rl2Game game;
    private final Rect window = new Rect();
    private final ScrollBand listBand = new ScrollBand();
    private float listContentH;

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
        addBurgerItem(TextCatalog.get("ui.arenaHall.setup"),
                () -> game.setRootScreen(new V2ArenaSetup(game)));
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        listBand.drawScrollbar(ctx.shapes, listContentH);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();
        float lh = ctx.lineH();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.arenaHall.title"),
                cx, window.top() - ctx.headerLineH());

        List<ArenaHallOfFameEntry> entries = game.arenaHallOfFame.entries;
        if (entries.isEmpty()) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.get("ui.arenaHall.empty"),
                    cx, window.top() - headerBandH() - lh);
            return;
        }

        float badgeSz     = lh * 2f;
        float badgeX      = window.x + 14f;
        float contentLeft = badgeX + badgeSz + 8f;
        float right       = window.right() - 14f;

        float headerY = window.top() - headerBandH() - lh * 0.5f;
        TextDraw.left (ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                TextCatalog.get("ui.arenaHall.match"), contentLeft, headerY);
        TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                TextCatalog.get("ui.arenaHall.survivors"), right, headerY);

        float visibleTop    = headerY - lh;
        float visibleBottom = window.y + UIVars.BACK_SIZE + 2 * BackBtn.INSET;
        listBand.set(window.x + 8f, visibleBottom,
                window.w - 16f, visibleTop - visibleBottom);

        float textW = Math.max(24f, right - contentLeft);
        float[] rowHeights = new float[entries.size()];
        float[] cumY = new float[entries.size()];
        listContentH = 0f;
        for (int i = 0; i < entries.size(); i++) {
            ArenaHallOfFameEntry e = entries.get(i);
            String match = TextCatalog.format("ui.arenaHall.matchup",
                    TextCatalog.vars("teamA", e.teamADescription, "teamB", e.teamBDescription));
            String result = TextCatalog.format("ui.arenaHall.result",
                    TextCatalog.vars("teamA", e.teamASurvivors, "teamB", e.teamBSurvivors));
            int matchLines = TextDraw.block(ctx.fontRegular, match,
                    textW, 2, lh).lineCount();
            int resultLines = TextDraw.block(ctx.fontRegular, result,
                    textW, 2, lh).lineCount();
            rowHeights[i] = Math.max(badgeSz + 6f,
                    lh * (1.2f + matchLines + resultLines) + 10f);
            cumY[i] = listContentH;
            listContentH += rowHeights[i] + 8f;
        }
        if (entries.size() > 0) listContentH -= 8f;
        listBand.update(listContentH);

        listBand.clip(ctx, () -> {
            for (int i = 0; i < entries.size(); i++) {
                float yTop = listBand.top() - cumY[i] + listBand.scroller.scrollY();
                float rowH = rowHeights[i];
                if (yTop - rowH > listBand.top()) continue;
                if (yTop < listBand.bottom()) break;

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

                String when = TS_FMT.format(new Date(e.timestampMillis));
                String match = TextCatalog.format("ui.arenaHall.matchup",
                        TextCatalog.vars("teamA", e.teamADescription, "teamB", e.teamBDescription));
                String result = TextCatalog.format("ui.arenaHall.result",
                        TextCatalog.vars("teamA", e.teamASurvivors, "teamB", e.teamBSurvivors));
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        when, contentLeft, yTop - lh * 0.35f, textW);
                TextDraw.TextBlock matchBlock = TextDraw.block(ctx.fontRegular,
                        match, textW, 2, lh);
                float lineY = yTop - lh * 1.35f;
                TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        matchBlock, contentLeft, lineY);
                lineY -= matchBlock.height();
                TextDraw.TextBlock resultBlock = TextDraw.block(ctx.fontRegular,
                        result, textW, 2, lh);
                TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        resultBlock, contentLeft, lineY);
            }
        });
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        if (!window.contains(vx, vy)) return false;
        return listBand.touchDown(vx, vy);
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        return listBand.touchDragged(vy);
    }

    @Override
    protected boolean onScrolled(float amountY) {
        listBand.scrolled(amountY, 40f);
        return true;
    }

    @Override
    protected void onEscape() { game.popScreen(); }
}
