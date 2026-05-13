package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 arena setup — pick a species + level + count for each of two teams.
 * Tap Start Fight to launch {@link V2Arena}, which spawns the chosen mobs
 * onto a fresh arena map and runs the fight.
 */
public final class V2ArenaSetup extends V2Screen {

    /** Tagged team-type. Either a species ({@link #charClass} == null,
     *  {@link #mobType} set to a registry key) or a player class
     *  ({@link #charClass} set, {@link #mobType} ignored). */
    public static final class TeamType {
        public final String mobType;
        public final Mob.CharacterClass charClass;
        public final String label;

        public TeamType(String mobType, Mob.CharacterClass charClass, String label) {
            this.mobType = mobType;
            this.charClass = charClass;
            this.label = label;
        }
    }

    /** Team picked by the user — type + level + count. Consumed by V2Arena
     *  to spawn the mobs on the fight map. */
    public static final class TeamSpec {
        public final TeamType type;
        public final int level;
        public final int count;

        public TeamSpec(TeamType type, int level, int count) {
            this.type = type;
            this.level = level;
            this.count = count;
        }
    }

    private static int teamAIdx = 0, teamBIdx = 3;
    private static int teamALevel = 5, teamBLevel = 5;
    private static int teamACount = 3, teamBCount = 3;
    private static final int[] LEVELS = { 1, 5, 10, 15 };
    private static final int[] COUNTS = { 1, 3, 5, 8 };

    private final Rl2Game game;
    private final Rect window = new Rect();
    private static List<TeamType> types;

    public V2ArenaSetup(Rl2Game game) {
        super(game.ui);
        this.game = game;
    }

    @Override
    protected Rect modalWindow() { return window; }

    private static List<TeamType> types() {
        if (types == null) {
            List<TeamType> out = new ArrayList<>();
            out.add(new TeamType(null, Mob.CharacterClass.WARRIOR, "warrior"));
            out.add(new TeamType(null, Mob.CharacterClass.ROGUE,   "rogue"));
            out.add(new TeamType(null, Mob.CharacterClass.MAGE,    "mage"));
            Point dummy = new Point(0, 0);
            for (String t : com.bjsp123.rl2.logic.MobRegistry.knownTypes()) {
                Mob template = MobFactory.spawn(t, dummy);
                String label = template != null && template.name != null
                        ? template.name : t.toLowerCase();
                out.add(new TeamType(t, null, label));
            }
            types = out;
        }
        return types;
    }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(380f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(640f, vh - 100f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        // Layout: header + team A row + vs + team B row + bottom buttons.
        // Each team occupies a full-width horizontal band stacked above
        // the next so the user reads them top-to-bottom rather than as
        // side-by-side columns.
        float pad = 14f;
        float bottomBtnH = 48f;
        float bottomReserved = 16f + 2 * bottomBtnH + 8f;  // matches btnY chain below
        float vsBandH = 30f;
        float headerReserved = headerBandH();
        float remaining = winH - bottomReserved - headerReserved - vsBandH;
        float teamH = remaining * 0.5f;

        float teamATop = window.top() - headerReserved;
        float teamBTop = teamATop - teamH - vsBandH;

        buildTeamRow(true,  window.x + pad, teamATop - teamH, winW - 2 * pad, teamH);
        buildTeamRow(false, window.x + pad, teamBTop - teamH, winW - 2 * pad, teamH);

        // Bottom row: Start Fight + Hall of Fame stacked vertically.
        float btnW = winW - 2 * pad;
        float btnX = window.x + pad;
        float btnY = window.y + 16f + bottomBtnH + 8f;
        buttons.add(new Btn("Start Fight", btnX, btnY, btnW, bottomBtnH,
                () -> {
                    TeamSpec a = new TeamSpec(
                            types().get(teamAIdx), teamALevel, teamACount);
                    TeamSpec b = new TeamSpec(
                            types().get(teamBIdx), teamBLevel, teamBCount);
                    game.pushScreen(new V2Arena(game, a, b));
                }).header());
        btnY -= bottomBtnH + 8f;
        buttons.add(new Btn("Hall of Fame", btnX, btnY, btnW, bottomBtnH,
                () -> game.pushScreen(new V2ArenaHallOfFame(game))).header());

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
        addBurgerItem("Hall of Fame",
                () -> game.pushScreen(new V2ArenaHallOfFame(game)));
    }

    /**
     * Shared geometry for one team band. Returns 7 Y positions:
     *   [0] title baseline
     *   [1] type-nav button bottom (libGDX rect convention)
     *   [2] type name label baseline (vertically centred in the button)
     *   [3] "Level" caption baseline
     *   [4] level-button bottom
     *   [5] "Count" caption baseline
     *   [6] count-button bottom
     * Both {@link #buildTeamRow} and {@link #drawTeamLabels} call this so
     * button placement and text placement are always derived from the same
     * formula and can never drift.
     */
    private float[] teamLayout(float y, float h) {
        float lh   = ctx.lineH();
        float btnH = 32f;
        float[] p  = new float[7];

        float c = y + h;          // start at band top
        c -= lh * 0.5f;           // top padding
        p[0] = c;                 // [0] title baseline
        c -= lh + lh * 0.5f;     // title height + gap below

        p[1] = c - btnH;          // [1] type-nav button bottom
        p[2] = p[1] + btnH * 0.5f + lh * 0.25f; // [2] type label: above centre of button
        c    = p[1];              // advance past buttons

        c -= lh * 0.6f;           // gap below type row
        p[3] = c;                 // [3] "Level" caption baseline
        c -= lh + 4f;             // caption height + small gap before buttons
        p[4] = c - btnH;          // [4] level-button bottom
        c    = p[4];

        c -= lh * 0.6f;           // gap below level buttons
        p[5] = c;                 // [5] "Count" caption baseline
        c -= lh + 4f;
        p[6] = c - btnH;          // [6] count-button bottom
        return p;
    }

    private void buildTeamRow(boolean isA, float x, float y, float w, float h) {
        float[] p      = teamLayout(y, h);
        float btnH     = 32f;
        float typeBtnW = 28f;
        float chooseW  = (w - 9f) / 4f;

        buttons.add(new Btn("<", x, p[1], typeBtnW, btnH,
                () -> shiftType(isA, -1)));
        buttons.add(new Btn(">", x + w - typeBtnW, p[1], typeBtnW, btnH,
                () -> shiftType(isA, +1)));

        for (int i = 0; i < LEVELS.length; i++) {
            final int lvl = LEVELS[i];
            Btn b = new Btn(Integer.toString(lvl),
                    x + i * (chooseW + 3f), p[4], chooseW, btnH,
                    () -> { if (isA) teamALevel = lvl; else teamBLevel = lvl; show(); });
            if ((isA ? teamALevel : teamBLevel) == lvl) b.checked = true;
            buttons.add(b);
        }

        for (int i = 0; i < COUNTS.length; i++) {
            final int cnt = COUNTS[i];
            Btn b = new Btn(Integer.toString(cnt),
                    x + i * (chooseW + 3f), p[6], chooseW, btnH,
                    () -> { if (isA) teamACount = cnt; else teamBCount = cnt; show(); });
            if ((isA ? teamACount : teamBCount) == cnt) b.checked = true;
            buttons.add(b);
        }
    }

    private void shiftType(boolean isA, int delta) {
        int n = types().size();
        if (isA) teamAIdx = ((teamAIdx + delta) % n + n) % n;
        else     teamBIdx = ((teamBIdx + delta) % n + n) % n;
        show();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Arena",
                window.cx(), window.top() - ctx.headerLineH());

        float pad = 14f;
        float bottomBtnH = 48f;
        float bottomReserved = 16f + 2 * bottomBtnH + 8f;
        float vsBandH = 30f;
        float headerReserved = headerBandH();
        float remaining = window.h - bottomReserved - headerReserved - vsBandH;
        float teamH = remaining * 0.5f;

        float teamATop = window.top() - headerReserved;
        float teamBTop = teamATop - teamH - vsBandH;

        drawTeamLabels(ctx, true,  window.x + pad, teamATop - teamH,
                window.w - 2 * pad, teamH);
        drawTeamLabels(ctx, false, window.x + pad, teamBTop - teamH,
                window.w - 2 * pad, teamH);

        // "vs" sits in the band between the two teams.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_WARN, "vs",
                window.cx(), teamBTop + vsBandH * 0.5f + 8f);
    }

    private void drawTeamLabels(UiCtx ctx, boolean isA,
                                float x, float y, float w, float h) {
        float[] p  = teamLayout(y, h);
        float cx   = x + w * 0.5f;
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                isA ? "Team A" : "Team B", cx, p[0]);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.ACCENT,
                types().get(isA ? teamAIdx : teamBIdx).label, cx, p[2]);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Level", cx, p[3]);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Count", cx, p[5]);
    }
}
