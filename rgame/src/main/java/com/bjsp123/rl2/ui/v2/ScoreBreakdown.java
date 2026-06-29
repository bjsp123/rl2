package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.model.HallOfFameEntry;

/**
 * Shared score-breakdown renderer for the victory ({@link V2Victory}) and
 * game-over ({@link V2GameOver}) screens: per-component lines (count x weight =
 * subtotal), the difficulty multiplier, and the final total. Mirrors
 * {@link GameBalance#runScore}.
 */
final class ScoreBreakdown {

    private ScoreBreakdown() {}

    /** Draw the breakdown for {@code r} top-down inside {@code area}. */
    static void draw(UiCtx ctx, Rect area, HallOfFameEntry r) {
        float y = area.y + area.h - 12f;
        y = line(ctx, area, y, "Mobs killed", r.mobsKilled, GameBalance.SCORE_PER_MOB);
        y = line(ctx, area, y, "Gems found",  r.gemsFound,  GameBalance.SCORE_PER_GEM);
        y = line(ctx, area, y, "Food eaten",  r.foodEaten,  GameBalance.SCORE_PER_FOOD);
        y = line(ctx, area, y, "Beacons lit", r.beaconsLit, GameBalance.SCORE_PER_BEACON_LIT);
        if (r.killedGreatWraith) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Great Wraith slain", area.x + 12f, y);
            TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    "+" + GameBalance.SCORE_WRAITH_BONUS, area.x + area.w - 12f, y);
            y -= ctx.lineH() + 6f;
        }
        if (r.allBeaconsLit) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Perfect (all beacons)", area.x + 12f, y);
            TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    "+" + GameBalance.PERFECT_VICTORY_BONUS, area.x + area.w - 12f, y);
            y -= ctx.lineH() + 6f;
        }
        double mult = GameBalance.scoreMultiplier(parseDiff(r.difficulty));
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                "Difficulty (" + r.difficulty + ")", area.x + 12f, y);
        TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                "x" + trim(mult), area.x + area.w - 12f, y);
        y -= ctx.lineH() + 10f;
        TextDraw.left(ctx, ctx.fontHeader, UIVars.GOLD, "TOTAL", area.x + 12f, y);
        TextDraw.right(ctx, ctx.fontHeader, UIVars.GOLD, Integer.toString(r.score),
                area.x + area.w - 12f, y);
    }

    private static float line(UiCtx ctx, Rect area, float y, String label, int count, int per) {
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                label + "  " + count + " x" + per, area.x + 12f, y);
        TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                Integer.toString(count * per), area.x + area.w - 12f, y);
        return y - (ctx.lineH() + 6f);
    }

    static GameBalance.Difficulty parseDiff(String s) {
        try { return GameBalance.Difficulty.valueOf(s); }
        catch (Exception e) { return GameBalance.Difficulty.NORMAL; }
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? Integer.toString((int) d) : String.valueOf(d);
    }
}
