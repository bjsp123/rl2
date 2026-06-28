package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

/**
 * Victory screen (RL-58) - shown after the end-sequence outro when the player
 * escapes the tomb. Title + portrait + equipped gear, then three tabs:
 * most-used items, run stats, and the score breakdown.
 */
public final class V2Victory extends V2Screen {

    private static final Color GOLD = new Color(1f, 0.85f, 0.4f, 1f);

    private final Rl2Game        game;
    private final HallOfFameEntry record;

    private final Rect window   = new Rect();
    private final Rect portrait = new Rect();
    private final Rect equipRow = new Rect();
    private final Rect content  = new Rect();
    private final TabStrip tabs = new TabStrip(3);
    private int activeTab = 0;
    private float titleY, classY, scoreY;
    private float swirlT;

    private static final String[] TAB_LABELS = { "Items", "Stats", "Score" };

    public V2Victory(Rl2Game game, HallOfFameEntry record) {
        super(game.ui);
        this.game   = game;
        this.record = record;
    }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW(), vh = ctx.worldH();
        float winW = Math.min(380f, vw - UIVars.PAD_MODAL);
        // Size the window dynamically so the tallest tab (items / stats / score)
        // has room for all its text instead of clipping a fixed height. The
        // fixed chrome above the content panel (title + portrait + class + score
        // + equipment row + tab strip) and the button band below sum to a
        // constant; the content panel is sized to its tallest tab. buildLayout's
        // top-down placement reproduces this content height from winH.
        float topStackH = 2f * ctx.headerLineH() + ctx.lineH() + 162f;
        float winH = topStackH + neededContentH() + 74f;
        winH = Math.max(440f, Math.min(winH, vh - 40f));
        float winX = (vw - winW) * 0.5f;
        float winY = (vh - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        float pad = 14f;
        float btnH = 50f, btnY = winY + 14f;

        float y = winY + winH - 14f;
        titleY = y;                              y -= ctx.headerLineH();
        float portSz = 64f;
        portrait.set(winX + (winW - portSz) * 0.5f, y - portSz, portSz, portSz);
        y = portrait.y - 6f;
        classY = y;                              y -= ctx.lineH();
        scoreY = y;                              y -= ctx.headerLineH() + 4f;

        float iconSz = 26f;
        equipRow.set(winX + pad, y - iconSz, winW - 2 * pad, iconSz);
        y = equipRow.y - 10f;

        float tabH = 30f, tabY = y - tabH;
        tabs.layout(window, pad, tabY, tabH, 6f);
        tabs.setActive(activeTab);
        y = tabY - 8f;

        float contentBottom = btnY + btnH + 10f;
        content.set(winX + pad, contentBottom, winW - 2 * pad, Math.max(40f, y - contentBottom));

        Btn mainMenu = new Btn(TextCatalog.get("ui.gameOver.mainMenu"),
                winX + 16f, btnY, winW - 32f, btnH,
                () -> game.setRootScreen(new V2Title(game, ctx))).header();
        buttons.add(mainMenu);
    }

    /** Content-panel height needed to fit the tallest of the three tabs (items /
     *  stats / score), in the current font scale. Drives the window's dynamic
     *  height so no tab clips. Mirrors the row counts of {@link #drawItemsTab},
     *  {@link #drawStatsTab}, and {@link ScoreBreakdown#draw}. */
    private float neededContentH() {
        float lineH = ctx.lineH();
        // Items tab: "Most used" header + 3 rows (lh = lineH + 18).
        float itemsH = 10f + (lineH + 6f) + 3f * (lineH + 18f) + 10f;
        // Stats tab: 7 stat rows.
        float statsH = 12f + 7f * (lineH + 6f) + 8f;
        // Score tab: mobs / gems / food / beacons (+ wraith, + perfect) +
        // difficulty + TOTAL header line.
        int scoreRows = 4;
        if (record.killedGreatWraith) scoreRows++;
        if (record.allBeaconsLit)     scoreRows++;
        scoreRows++;                                  // difficulty row
        float scoreH = 12f + scoreRows * (lineH + 6f) + 4f + ctx.headerLineH() + 12f;
        return Math.max(itemsH, Math.max(statsH, scoreH));
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        return tabs.touchDown(vx, vy) >= 0;
    }

    @Override
    protected boolean onTouchUpInBody(float vx, float vy) {
        int t = tabs.touchUp(vx, vy);
        if (t >= 0) { activeTab = t; tabs.setActive(t); return true; }
        return false;
    }

    @Override
    protected void drawBackground(float delta) {
        swirlT += delta;
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setProjectionMatrix(ctx.camera.combined);
        s.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        s.setColor(0.05f, 0.05f, 0.07f, 1f);
        s.rect(0f, 0f, ctx.worldW(), ctx.worldH());
        s.end();
        ctx.batch.setProjectionMatrix(ctx.camera.combined);
        ctx.batch.begin();
        SwirlBackground.render(ctx.batch, 0f, 0f, ctx.worldW(), ctx.worldH(), swirlT);
        ctx.batch.end();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        ctx.shapes.setColor(UIVars.SLOT_RECESS);
        ctx.shapes.rect(portrait.x - 4f, portrait.y - 4f, portrait.w + 8f, portrait.h + 8f);
        ctx.shapes.rect(content.x, content.y, content.w, content.h);
        tabs.drawShapes(ctx.shapes);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();
        // Title - "Perfect Victory!" when every beacon was lit.
        String titleKey = record.allBeaconsLit ? "ui.victory.titlePerfect" : "ui.victory.title";
        TextDraw.centre(ctx, ctx.fontHeader, GOLD,
                TextCatalog.getOrDefault(titleKey, "Victory!"), cx, titleY);
        // Portrait.
        CharacterClass cls = parseClass(record.charClass);
        if (cls != null) {
            TextureRegion p = PortraitSprites.regionFor(cls);
            if (p != null) ctx.batch.draw(p, portrait.x, portrait.y, portrait.w, portrait.h);
        }
        // Class + score.
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY, record.charClass, cx, classY);
        TextDraw.centre(ctx, ctx.fontHeader, GOLD, "Score  " + record.score, cx, scoreY);

        // Equipped gear - a centred icon row.
        drawEquipment(ctx);

        // Tab labels.
        for (int i = 0; i < tabs.rects.length; i++) {
            Rect r = tabs.rects[i];
            TextDraw.centre(ctx, ctx.fontRegular,
                    i == activeTab ? UIVars.ACCENT : UIVars.TEXT_DIM,
                    TAB_LABELS[i], r.cx(), r.cy() + ctx.fontRegular.getCapHeight() * 0.5f);
        }

        // Active tab content.
        switch (activeTab) {
            case 0 -> drawItemsTab(ctx);
            case 1 -> drawStatsTab(ctx);
            default -> drawScoreTab(ctx);
        }
    }

    private void drawEquipment(UiCtx ctx) {
        if (record.equipmentTypes == null || record.equipmentTypes.isEmpty()) return;
        float iconSz = equipRow.h;
        int n = record.equipmentTypes.size();
        float gap = 4f;
        float rowW = n * iconSz + (n - 1) * gap;
        float sx = window.cx() - rowW * 0.5f;
        float sy = equipRow.y;
        for (String type : record.equipmentTypes) {
            TextureRegion r = ItemSprites.regionFor(type);
            if (r != null) ctx.batch.draw(r, sx, sy, iconSz, iconSz);
            sx += iconSz + gap;
        }
    }

    private void drawItemsTab(UiCtx ctx) {
        float x = content.x + 10f;
        float y = content.y + content.h - 10f;
        float lh = ctx.lineH() + 18f;
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "Most used", x, y);
        y -= ctx.lineH() + 6f;
        y = drawMostUsed(ctx, x, y, "Wand", record.topWand, record.topWandCount, lh);
        y = drawMostUsed(ctx, x, y, "Bomb", record.topBomb, record.topBombCount, lh);
        drawMostUsed(ctx, x, y, "Tool", record.topTool, record.topToolCount, lh);
    }

    private float drawMostUsed(UiCtx ctx, float x, float y, String label,
                               String type, int count, float lh) {
        float iconSz = 28f;
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, label, x, y);
        float vx = x + 64f;
        if (type == null || type.isEmpty()) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, "—", vx, y);
        } else {
            TextureRegion r = ItemSprites.regionFor(type);
            if (r != null) ctx.batch.draw(r, vx, y - iconSz + ctx.fontRegular.getCapHeight(), iconSz, iconSz);
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    itemName(type) + "   x" + count, vx + iconSz + 6f, y);
        }
        return y - lh;
    }

    private void drawStatsTab(UiCtx ctx) {
        float y = content.y + content.h - 12f;
        y = statLine(ctx, y, "Mobs killed",    record.mobsKilled);
        y = statLine(ctx, y, "Turns taken",    record.totalTurns);
        y = statLine(ctx, y, "Items picked up", record.itemsPickedUp);
        y = statLine(ctx, y, "Food eaten",     record.foodEaten);
        y = statLine(ctx, y, "Gems found",     record.gemsFound);
        y = statLine(ctx, y, "Beacons lit",    record.beaconsLit);
        statLine(ctx, y, "Beasts tamed",       record.beastsTamed);
    }

    private float statLine(UiCtx ctx, float y, String label, int value) {
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, label, content.x + 12f, y);
        TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY, Integer.toString(value),
                content.x + content.w - 12f, y);
        return y - (ctx.lineH() + 6f);
    }

    private void drawScoreTab(UiCtx ctx) {
        ScoreBreakdown.draw(ctx, content, record);
    }

    // -- helpers ---------------------------------------------------------------

    private static String itemName(String type) {
        Item it = ItemFactory.build(type);
        return it != null && it.name != null ? it.name : type;
    }

    private static CharacterClass parseClass(String name) {
        if (name == null) return null;
        for (CharacterClass c : CharacterClass.values()) {
            if (c.displayName().equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name)) return c;
        }
        return null;
    }
}
