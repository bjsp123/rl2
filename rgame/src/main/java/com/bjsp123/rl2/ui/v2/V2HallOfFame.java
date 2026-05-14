package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.save.Achievement;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

import java.util.ArrayList;
import java.util.List;

/** V2 hall-of-fame screen - two tabs:
 *  <ul>
 *    <li><b>Heroes</b> (character icon) - scrolling list of past runs.</li>
 *    <li><b>Achievements</b> (map icon) - list of every achievement,
 *        greyed out when not yet earned.</li>
 *  </ul>
 *  Each tab owns its own {@link Scroller} so switching tabs preserves
 *  per-tab scroll position. */
public final class V2HallOfFame extends V2Screen {

    private enum Tab { HEROES, ACHIEVEMENTS }

    private final Rl2Game game;
    private final Rect window = new Rect();
    private final TabStrip tabs = new TabStrip(Tab.values().length);

    private Tab currentTab = Tab.HEROES;
    private final ScrollBand heroesBand       = new ScrollBand();
    private final ScrollBand achievementsBand = new ScrollBand();
    private float heroesContentH;
    private float achievementsContentH;

    public V2HallOfFame(Rl2Game game) {
        super(game.ui);
        this.game = game;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(360f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(UIVars.VIRTUAL_H - 144f, vh - 144f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        // Tab strip - square-ish icon buttons just below the header band.
        // Sized off the live header font so a UiFontScale change doesn't
        // collide with the header text.
        float pad = 12f;
        float tabH = 36f;
        float tabGap = 6f;
        float headerBand = headerBandH();
        float tabsY = window.top() - pad - tabH - headerBand;
        tabs.layout(window, pad, tabsY, tabH, tabGap);
        tabs.setActive(currentTab.ordinal());
        configureBands();

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        tabs.drawShapes(ctx.shapes);
        activeBand().drawScrollbar(ctx.shapes,
                currentTab == Tab.HEROES ? heroesContentH : achievementsContentH);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.hall.title"),
                window.cx(), window.top() - ctx.headerLineH());

        // Tab icons - drawn in the text pass since icons are sprites.
        IconSprites.Icon[] icons = {
                IconSprites.Icon.CHARACTER, IconSprites.Icon.MAP
        };
        TextureRegion[] regions = new TextureRegion[icons.length];
        for (int i = 0; i < icons.length; i++) {
            regions[i] = IconSprites.regionFor(icons[i]);
        }
        tabs.drawIcons(ctx, regions);

        switch (currentTab) {
            case HEROES       -> drawHeroesTab(ctx);
            case ACHIEVEMENTS -> drawAchievementsTab(ctx);
        }
    }

    private float bandTop() { return tabs.rects[0].y - 16f; }
    private float bandBottom() { return window.y + UIVars.BACK_SIZE + 2 * BackBtn.INSET; }

    private void drawHeroesTab(UiCtx ctx) {
        List<HallOfFameEntry> entries = game.hallOfFame.entries;
        float bodyLeft  = window.x + 12f;
        float bodyRight = window.right() - 12f;
        float lh        = ctx.lineH();
        float portSz    = 40f;
        float textLeft  = bodyLeft + portSz + 8f;
        float maxDeathW = bodyRight - textLeft;
        float rowGap    = 12f;
        float rowVPad   = lh * 0.5f;

        float visibleTop    = bandTop();
        float visibleBottom = bandBottom();
        float visibleH      = visibleTop - visibleBottom;
        heroesBand.set(window.x, visibleBottom, window.w, visibleH);

        if (entries.isEmpty()) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.get("ui.hall.empty"),
                    window.cx(), visibleTop - 24f);
            return;
        }

        // Pre-pass: measure each row's pixel height from its wrapped death message
        // (capped at 2 lines so rows don't grow unbounded).
        float[] rowHeights = new float[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            HallOfFameEntry e = entries.get(i);
            String dm = e.deathMessage;
            float deathH = 0f;
            if (dm != null && !dm.isEmpty()) {
                List<String> tmp = new ArrayList<>();
                TextDraw.wrap(ctx.fontRegular, dm, maxDeathW, 2, tmp);
                deathH = tmp.size() * lh;
            }
            float chipH = hasStatChips(e) ? lh : 0f;
            rowHeights[i] = Math.max(portSz + 2 * rowVPad,
                    rowVPad + lh + chipH + lh * 0.3f + deathH + rowVPad);
        }

        // Cumulative Y offsets (pixels from visibleTop downward).
        float[] cumY = new float[entries.size()];
        float totalH = 0f;
        for (int i = 0; i < entries.size(); i++) {
            cumY[i] = totalH;
            totalH += rowHeights[i] + rowGap;
        }
        if (entries.size() > 0) totalH -= rowGap;
        heroesContentH = Math.max(0f, totalH);
        heroesBand.update(heroesContentH);

        heroesBand.clip(ctx, () -> {
            float scroll = heroesBand.scroller.scrollY();
            for (int i = 0; i < entries.size(); i++) {
                float rh   = rowHeights[i];
                float yTop = visibleTop - cumY[i] + scroll;
                float yBot = yTop - rh;
                if (yBot > visibleTop)     continue;
                if (yTop <= visibleBottom) break;
                HallOfFameEntry e = entries.get(i);

                // Portrait on the left, vertically centred in the row.
                com.bjsp123.rl2.model.Mob.CharacterClass cls = parseClass(e.charClass);
                if (cls != null) {
                    TextureRegion port = PortraitSprites.regionFor(cls);
                    if (port != null) {
                        ctx.batch.draw(port, bodyLeft,
                                yBot + (rh - portSz) * 0.5f, portSz, portSz);
                    }
                }

                // Upper line: rank + class name / level + depth.
                float upperY = yTop - rowVPad;
                float summaryW = 112f;
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        (i + 1) + "  " + e.charClass, textLeft, upperY,
                        Math.max(40f, bodyRight - textLeft - summaryW - 8f));
                TextDraw.rightFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        TextCatalog.format("ui.hall.summary",
                                TextCatalog.vars("level", e.level, "depth", e.depth)),
                        bodyRight, upperY,
                        summaryW);

                // Stat chips - turns / beasts tamed / favourite perk.
                float nextY = upperY - lh;
                if (hasStatChips(e)) {
                    TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            statChipLine(e), textLeft, nextY, maxDeathW);
                    nextY -= lh;
                }

                // Death message - word-wrapped, max 2 lines.
                String dm = e.deathMessage;
                if (dm == null || dm.isEmpty()) dm = TextCatalog.get("ui.hall.unknownDeath");
                TextDraw.TextBlock dmLines = TextDraw.block(ctx.fontRegular,
                        dm, maxDeathW, 2, lh);
                float deathY = nextY - lh * 0.5f;
                TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        dmLines, textLeft, deathY);

            }
        });
    }

    private static boolean hasStatChips(HallOfFameEntry e) {
        return e.totalTurns > 0
                || e.beastsTamed > 0
                || (e.favPerk != null && !e.favPerk.isEmpty());
    }

    private static String statChipLine(HallOfFameEntry e) {
        StringBuilder sb = new StringBuilder();
        if (e.totalTurns > 0)
            sb.append(TextCatalog.format("ui.hall.turns",
                    TextCatalog.vars("turns", e.totalTurns)));
        if (e.beastsTamed > 0) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(TextCatalog.format("ui.hall.tamed",
                    TextCatalog.vars("count", e.beastsTamed)));
        }
        if (e.favPerk != null && !e.favPerk.isEmpty()) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(e.favPerk);
        }
        return sb.toString();
    }

    private static com.bjsp123.rl2.model.Mob.CharacterClass parseClass(String name) {
        if (name == null) return null;
        for (com.bjsp123.rl2.model.Mob.CharacterClass c :
                com.bjsp123.rl2.model.Mob.CharacterClass.values()) {
            if (c.displayName().equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name))
                return c;
        }
        return null;
    }

    private void drawAchievementsTab(UiCtx ctx) {
        Achievement[] all = Achievement.values();
        float left  = window.x + UIVars.PAD_CONTENT;
        float right = window.right() - UIVars.PAD_CONTENT;
        float visibleTop    = bandTop();
        float visibleBottom = bandBottom();
        float visibleH      = visibleTop - visibleBottom;
        float rowH          = 36f;
        achievementsBand.set(window.x, visibleBottom, window.w, visibleH);
        achievementsContentH = all.length * rowH;
        achievementsBand.update(achievementsContentH);

        achievementsBand.clip(ctx, () -> {
            for (int i = 0; i < all.length; i++) {
                float yTop = visibleTop - i * rowH + achievementsBand.scroller.scrollY();
                if (yTop <= visibleBottom) break;
                if (yTop > visibleTop)     continue;
                Achievement a = all[i];
                boolean unlocked = game.achievements != null
                        && game.achievements.isUnlocked(a);
                // Hidden + locked entries render as a censored row so the
                // player only sees the surprise once they've earned it.
                boolean censored = a.hidden && !unlocked;
                String name = censored ? TextCatalog.get("ui.hall.hiddenName") : a.displayName();
                String desc = censored
                        ? com.bjsp123.rl2.logic.TextCatalog.get("achievement.hidden")
                        : a.description();
                com.badlogic.gdx.graphics.Color nameColor =
                        unlocked ? UIVars.TEXT_BODY : UIVars.TEXT_DIM;
                TextDraw.leftFit(ctx, ctx.fontRegular, nameColor,
                        name, left, yTop, Math.max(40f, right - left - 58f));
                if (unlocked) {
                    TextDraw.right(ctx, ctx.fontRegular, UIVars.ACCENT,
                            com.bjsp123.rl2.logic.TextCatalog.get("achievement.earned"), right, yTop);
                }
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        desc, left, yTop - 16f, right - left);
            }
        });
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        if (tabs.touchDown(vx, vy) >= 0) return true;
        if (!window.contains(vx, vy)) return false;
        return activeBand().touchDown(vx, vy);
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        return activeBand().touchDragged(vy);
    }

    @Override
    protected boolean onScrolled(float amountY) {
        activeBand().scrolled(amountY, ctx.lineH());
        return true;
    }

    private ScrollBand activeBand() {
        return currentTab == Tab.HEROES ? heroesBand : achievementsBand;
    }

    private void configureBands() {
        float top = bandTop();
        float bottom = bandBottom();
        heroesBand.set(window.x, bottom, window.w, top - bottom);
        achievementsBand.set(window.x, bottom, window.w, top - bottom);
    }

    /** Override the input chain by composing the base V2Screen processor
     *  with a tab-release dispatcher. Tab clicks fire on touchUp so a
     *  drag-off cancels (matches V2Screen's button convention). */
    private final com.badlogic.gdx.InputAdapter tabInput = new com.badlogic.gdx.InputAdapter() {
        @Override
        public boolean touchUp(int sx, int sy, int p, int b) {
            float vx = ctx.unprojectX(sx, sy);
            float vy = ctx.unprojectY(sx, sy);
            if (tabs.hasPressed()) {
                int idx = tabs.touchUp(vx, vy);
                if (idx >= 0) {
                    currentTab = Tab.values()[idx];
                    return true;
                }
                return true;
            }
            return false;
        }
    };

    @Override
    public void show() {
        super.show();
        Gdx.input.setInputProcessor(new com.badlogic.gdx.InputMultiplexer(
                tabInput, baseInput()));
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
    }
}
