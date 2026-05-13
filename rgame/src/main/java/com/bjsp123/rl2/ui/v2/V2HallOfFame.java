package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.save.Achievement;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

import java.util.ArrayList;
import java.util.List;

/** V2 hall-of-fame screen — two tabs:
 *  <ul>
 *    <li><b>Heroes</b> (character icon) — scrolling list of past runs.</li>
 *    <li><b>Achievements</b> (map icon) — list of every achievement,
 *        greyed out when not yet earned.</li>
 *  </ul>
 *  Each tab owns its own {@link Scroller} so switching tabs preserves
 *  per-tab scroll position. */
public final class V2HallOfFame extends V2Screen {

    private enum Tab { HEROES, ACHIEVEMENTS }

    private final Rl2Game game;
    private final Rect window = new Rect();
    private final Rect[] tabRects = { new Rect(), new Rect() };
    private final boolean[] tabPressed = new boolean[Tab.values().length];

    private Tab currentTab = Tab.HEROES;
    private final Scroller heroesScroller       = new Scroller();
    private final Scroller achievementsScroller = new Scroller();

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

        // Tab strip — square-ish icon buttons just below the header band.
        // Sized off the live header font so a UiFontScale change doesn't
        // collide with the header text.
        float pad = 12f;
        float tabH = 36f;
        float tabGap = 6f;
        float headerBand = headerBandH();
        float tabsY = window.top() - pad - tabH - headerBand;
        float innerW = winW - 2 * pad;
        float tabW = (innerW - (tabRects.length - 1) * tabGap) / tabRects.length;
        for (int i = 0; i < tabRects.length; i++) {
            tabRects[i].set(window.x + pad + i * (tabW + tabGap),
                    tabsY, tabW, tabH);
        }

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Tabs — same chrome as the V2CharacterStats tabs.
        ShapeRenderer s = ctx.shapes;
        for (int i = 0; i < tabRects.length; i++) {
            Rect r = tabRects[i];
            boolean active  = Tab.values()[i] == currentTab;
            boolean pressed = tabPressed[i];
            if (active || pressed) {
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W,
                        UIVars.ACCENT, UIVars.BORDER_MID, UIVars.BORDER_INNER);
            } else {
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
            }
            s.setColor(active ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Hall of Fame",
                window.cx(), window.top() - ctx.headerLineH());

        // Tab icons — drawn in the text pass since icons are sprites.
        IconSprites.Icon[] icons = {
                IconSprites.Icon.CHARACTER, IconSprites.Icon.MAP
        };
        for (int i = 0; i < tabRects.length; i++) {
            Rect r = tabRects[i];
            TextureRegion region = IconSprites.regionFor(icons[i]);
            if (region == null) continue;
            boolean active = Tab.values()[i] == currentTab;
            ctx.batch.setColor(active ? UIVars.ACCENT : UIVars.TEXT_BODY);
            float sz = Math.min(r.w, r.h) * 0.6f;
            ctx.batch.draw(region,
                    r.cx() - sz * 0.5f, r.cy() - sz * 0.5f, sz, sz);
            ctx.batch.setColor(1f, 1f, 1f, 1f);
        }

        switch (currentTab) {
            case HEROES       -> drawHeroesTab(ctx);
            case ACHIEVEMENTS -> drawAchievementsTab(ctx);
        }
    }

    private float bandTop() { return tabRects[0].y - 16f; }
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

        if (entries.isEmpty()) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    "No entries yet.",
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
        heroesScroller.setMaxScroll(Math.max(0f, totalH - visibleH));

        clipBand(ctx, visibleTop, visibleBottom, () -> {
            float scroll = heroesScroller.scrollY();
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
                TextDraw.left (ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        (i + 1) + "  " + e.charClass, textLeft, upperY);
                TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        "Level:" + e.level + " Depth: " + e.depth, bodyRight, upperY);

                // Stat chips — turns / beasts tamed / favourite perk.
                float nextY = upperY - lh;
                if (hasStatChips(e)) {
                    TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            statChipLine(e), textLeft, nextY);
                    nextY -= lh;
                }

                // Death message — word-wrapped, max 2 lines.
                String dm = e.deathMessage;
                if (dm == null || dm.isEmpty()) dm = "Died of unknown causes.";
                List<String> dmLines = new ArrayList<>();
                TextDraw.wrap(ctx.fontRegular, dm, maxDeathW, 2, dmLines);
                float deathY = nextY - lh * 0.5f;
                for (String line : dmLines) {
                    TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, line, textLeft, deathY);
                    deathY -= lh;
                }

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
            sb.append("T:").append(e.totalTurns);
        if (e.beastsTamed > 0) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(e.beastsTamed).append(" tamed");
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
            if (c.displayName.equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name))
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
        achievementsScroller.setMaxScroll(all.length * rowH - visibleH);

        clipBand(ctx, visibleTop, visibleBottom, () -> {
            for (int i = 0; i < all.length; i++) {
                float yTop = visibleTop - i * rowH + achievementsScroller.scrollY();
                if (yTop <= visibleBottom) break;
                if (yTop > visibleTop)     continue;
                Achievement a = all[i];
                boolean unlocked = game.achievements != null
                        && game.achievements.isUnlocked(a);
                // Hidden + locked entries render as a censored row so the
                // player only sees the surprise once they've earned it.
                boolean censored = a.hidden && !unlocked;
                String name = censored ? "???" : a.displayName;
                String desc = censored
                        ? "Hidden achievement — keep playing."
                        : a.description;
                com.badlogic.gdx.graphics.Color nameColor =
                        unlocked ? UIVars.TEXT_BODY : UIVars.TEXT_DIM;
                TextDraw.left(ctx, ctx.fontRegular, nameColor,
                        name, left, yTop);
                if (unlocked) {
                    TextDraw.right(ctx, ctx.fontRegular, UIVars.ACCENT,
                            "earned", right, yTop);
                }
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        desc, left, yTop - 16f);
            }
        });
    }

    /** Run {@code body} under a glScissor that clips to the band between
     *  {@code yTop} and {@code yBottom}. The active SpriteBatch is flushed
     *  before/after so the clip applies cleanly to its draw calls. */
    private void clipBand(UiCtx ctx, float yTop, float yBottom, Runnable body) {
        ctx.batch.flush();
        com.badlogic.gdx.math.Rectangle worldRect = new com.badlogic.gdx.math.Rectangle(
                window.x, yBottom, window.w, yTop - yBottom);
        com.badlogic.gdx.math.Rectangle scissor = new com.badlogic.gdx.math.Rectangle();
        com.badlogic.gdx.utils.viewport.Viewport vp = ctx.viewport;
        com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.calculateScissors(vp.getCamera(),
                vp.getScreenX(), vp.getScreenY(), vp.getScreenWidth(), vp.getScreenHeight(),
                ctx.batch.getTransformMatrix(), worldRect, scissor);
        if (com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.pushScissors(scissor)) {
            try {
                body.run();
                ctx.batch.flush();
            } finally {
                com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
            }
        } else {
            // Scissor was empty — nothing to draw.
            body.run();
        }
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        for (int i = 0; i < tabRects.length; i++) {
            if (tabRects[i].contains(vx, vy)) {
                tabPressed[i] = true;
                return true;
            }
        }
        if (!window.contains(vx, vy)) return false;
        activeScroller().onTouchDown(vy);
        return true;
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        return activeScroller().onTouchDragged(vy);
    }

    @Override
    protected boolean onScrolled(float amountY) {
        activeScroller().onScrolled(amountY, ctx.lineH());
        return true;
    }

    private Scroller activeScroller() {
        return currentTab == Tab.HEROES ? heroesScroller : achievementsScroller;
    }

    /** Override the input chain by composing the base V2Screen processor
     *  with a tab-release dispatcher. Tab clicks fire on touchUp so a
     *  drag-off cancels (matches V2Screen's button convention). */
    private final com.badlogic.gdx.InputAdapter tabInput = new com.badlogic.gdx.InputAdapter() {
        @Override
        public boolean touchUp(int sx, int sy, int p, int b) {
            float vx = ctx.unprojectX(sx, sy);
            float vy = ctx.unprojectY(sx, sy);
            for (int i = 0; i < tabRects.length; i++) {
                if (tabPressed[i]) {
                    tabPressed[i] = false;
                    if (tabRects[i].contains(vx, vy)) {
                        currentTab = Tab.values()[i];
                        return true;
                    }
                    return true;
                }
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
