package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.StatBlock;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.MobSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 character stats popup — modal window with two tabs:
 * <ul>
 *   <li>Character — laid out exactly like a mob encyclopaedia entry: name
 *       header, framed sprite at 2× source size, stat lines + active buffs.</li>
 *   <li>Perks — per-perk row showing name + level + a square INFO button
 *       that opens the encyclopaedia at the perk's page; remaining perk
 *       points appear in the section header.</li>
 * </ul>
 */
public final class V2CharacterStats implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private enum Tab { CHARACTER, PERKS }

    private final UiCtx ctx;
    private boolean open;
    private Mob player;
    private Tab currentTab = Tab.CHARACTER;
    /** Optional jump target for the perks tab's per-row info buttons. */
    private V2Encyclopedia encyclopedia;

    private final Rect window = new Rect();
    private final Rect[] tabRects = new Rect[Tab.values().length];
    private final boolean[] tabPressed = new boolean[Tab.values().length];

    /** Sprite frame for the Character tab — light-warm-grey backdrop +
     *  tri-line border, computed from the player's mob sprite size so the
     *  layout mirrors {@link V2Encyclopedia}'s mob detail page. */
    private final Rect characterFrame = new Rect();

    /** Per-perk info-button rects — rebuilt every frame from the live
     *  perk map. Index aligned with {@link #perksOrdered}. */
    private final List<Rect> perkInfoRects = new ArrayList<>();
    /** Per-perk plus-button rects — rebuilt every frame, index aligned
     *  with {@link #perksOrdered}. The button is hit-tested even when
     *  the player has no perk points; the touchUp handler short-circuits
     *  in that case so the button reads as disabled. */
    private final List<Rect> perkPlusRects = new ArrayList<>();
    private final List<Perk> perksOrdered  = new ArrayList<>();
    /** Index in {@link #perkInfoRects} of the button currently held; -1
     *  when no info button is being pressed. */
    private int perkInfoPressed = -1;
    /** Index of the plus button currently being pressed, or -1. */
    private int perkPlusPressed = -1;
    /** Per-buff-icon hit rects, rebuilt every frame from the live buff
     *  list. Aligned in index with {@link #buffIconTypes}. */
    private final List<Rect> buffIconRects = new ArrayList<>();
    private final List<Buff.BuffType> buffIconTypes = new ArrayList<>();
    private int buffIconPressed = -1;

    public V2CharacterStats(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < tabRects.length; i++) tabRects[i] = new Rect();
    }

    public void setPlayer(Mob p) { this.player = p; }
    public void setEncyclopedia(V2Encyclopedia enc) { this.encyclopedia = enc; }
    public boolean isOpen() { return open; }
    public void toggle() { open = !open; }
    public void close()  {
        open = false;
        perkInfoPressed = -1;
        perkPlusPressed = -1;
        buffIconPressed = -1;
    }

    @Override
    public void renderSelf() {
        if (!open) return;
        layoutRects();
        renderShapesPass();
        renderTextPass();
    }

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(340f, vw - 24f);
        float winH = Math.min(540f, vh - 100f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        // Tab strip across the top of the window's interior. Header sits
        // ABOVE the tabs in a band sized to the live header-font cap height
        // so a 1.5×/2× UiFontScale doesn't overlap the tab strip — same
        // approach the encyclopaedia uses.
        float pad = 12f;
        float tabH = 32f;
        float tabGap = 4f;
        float innerW = winW - 2 * pad;
        float tabW = (innerW - (tabRects.length - 1) * tabGap) / tabRects.length;
        float headerBand = ctx.fontHeader.getCapHeight() + 28f;
        float tabsY = window.top() - pad - tabH - headerBand;
        for (int i = 0; i < tabRects.length; i++) {
            tabRects[i].set(window.x + pad + i * (tabW + tabGap),
                    tabsY, tabW, tabH);
        }

        // Character-tab sprite frame — same shape the encyclopaedia uses
        // for its mob detail page. Frame size = 2× source max with a
        // 64-px floor; centred horizontally just below the tabs.
        if (currentTab == Tab.CHARACTER && player != null) {
            TextureRegion region = MobSprites.regionFor(player);
            int srcMax = 32;
            if (region != null) {
                srcMax = Math.max(region.getRegionWidth(),
                                  region.getRegionHeight());
            }
            float frameSz = Math.max(64f, srcMax * 2f);
            float frameX  = window.cx() - frameSz * 0.5f;
            float frameY  = tabsY - 16f - frameSz;
            characterFrame.set(frameX, frameY, frameSz, frameSz);
        }

        // Perks-tab buttons — every Perk gets a row with a Plus button +
        // an Info button on the right. The Plus button is hit-tested but
        // visually disabled when the player has no remaining perk points.
        perkInfoRects.clear();
        perkPlusRects.clear();
        perksOrdered.clear();
        if (currentTab == Tab.PERKS && player != null) {
            float rowTop = tabsY - 24f - 28f;     // first row Y under "Available" header
            float rowH   = 36f;
            float btnSz  = 28f;
            float btnGap = 6f;
            float infoX  = window.right() - 18f - btnSz;
            float plusX  = infoX - btnGap - btnSz;
            for (Perk p : Perk.values()) {
                if (rowTop < window.y + 16f) break;
                Rect ri = new Rect();
                ri.set(infoX, rowTop - btnSz + 4f, btnSz, btnSz);
                Rect rp = new Rect();
                rp.set(plusX, rowTop - btnSz + 4f, btnSz, btnSz);
                perkInfoRects.add(ri);
                perkPlusRects.add(rp);
                perksOrdered.add(p);
                rowTop -= rowH;
            }
        }
    }

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        // Modal dim.
        s.setColor(0f, 0f, 0f, Pal.DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Tabs.
        for (int i = 0; i < tabRects.length; i++) {
            Rect r = tabRects[i];
            boolean active  = Tab.values()[i] == currentTab;
            boolean pressed = tabPressed[i];
            if (active || pressed) {
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W,
                        UiColors.ACCENT, UiColors.BORDER_MID, UiColors.BORDER_INNER);
            } else {
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
            }
            s.setColor(active ? UiColors.BTN_PRESSED_BG : UiColors.BTN_BG);
            s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                    r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
        }

        // Character-tab sprite frame chrome — light-warm-grey backdrop
        // + tri-line border. Sprite paints aspect-fit in the text pass.
        if (currentTab == Tab.CHARACTER && player != null) {
            Rect f = characterFrame;
            Edges.drawTriLine(s, f.x, f.y, f.w, f.h, Pal.HUD_LINE_W);
            s.setColor(UiColors.ICON_FRAME_BG);
            s.rect(f.x + Pal.HUD_BORDER, f.y + Pal.HUD_BORDER,
                    f.w - 2 * Pal.HUD_BORDER, f.h - 2 * Pal.HUD_BORDER);
        }

        // Perks-tab buttons — same chrome as a regular Btn (tri-line
        // border + warm fill, brighter when pressed). The Plus button
        // dims to the window-bg fill when the player has no perk points
        // so the affordance reads as disabled.
        boolean canSpend = player != null && player.perkPoints > 0;
        for (int i = 0; i < perkInfoRects.size(); i++) {
            Rect r = perkInfoRects.get(i);
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
            s.setColor(i == perkInfoPressed
                    ? UiColors.BTN_PRESSED_BG : UiColors.BTN_BG);
            s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                    r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
        }
        for (int i = 0; i < perkPlusRects.size(); i++) {
            Rect r = perkPlusRects.get(i);
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
            if (!canSpend) {
                s.setColor(UiColors.WIN_BG);
            } else if (i == perkPlusPressed) {
                s.setColor(UiColors.BTN_PRESSED_BG);
            } else {
                s.setColor(UiColors.BTN_BG);
            }
            s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                    r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderTextPass() {
        ctx.batch.begin();
        TextDraw.centre(ctx, ctx.fontHeader, UiColors.ACCENT, "Character",
                window.cx(), window.top() - 22f);

        // Tab labels.
        String[] labels = { "Stats", "Perks" };
        for (int i = 0; i < tabRects.length; i++) {
            boolean active = Tab.values()[i] == currentTab;
            TextDraw.centre(ctx, ctx.fontRegular,
                    active ? UiColors.ACCENT : UiColors.TEXT_BODY,
                    labels[i], tabRects[i].cx(), tabRects[i].cy() + 6f);
        }

        if (player != null) {
            switch (currentTab) {
                case CHARACTER -> drawCharacterTab();
                case PERKS     -> drawPerksTab();
            }
        }

        // Perk-tab info-button icons — drawn in the text pass since the
        // INFO glyph is a sprite. Tinted yellow when pressed, white otherwise.
        for (int i = 0; i < perkInfoRects.size(); i++) {
            Rect r = perkInfoRects.get(i);
            TextureRegion region = com.bjsp123.rl2.world.render.IconSprites
                    .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.INFO);
            if (region == null) continue;
            ctx.batch.setColor(i == perkInfoPressed
                    ? UiColors.ACCENT : UiColors.TEXT_BODY);
            float sz = Math.min(r.w, r.h) * 0.6f;
            ctx.batch.draw(region,
                    r.cx() - sz * 0.5f, r.cy() - sz * 0.5f, sz, sz);
            ctx.batch.setColor(1f, 1f, 1f, 1f);
        }

        // Perk-tab plus-button glyphs — a centred "+" character. Dimmed
        // when the player has no perk points to spend.
        boolean canSpend = player != null && player.perkPoints > 0;
        for (int i = 0; i < perkPlusRects.size(); i++) {
            Rect r = perkPlusRects.get(i);
            com.badlogic.gdx.graphics.Color c = !canSpend
                    ? UiColors.TEXT_DIM
                    : (i == perkPlusPressed ? UiColors.ACCENT : UiColors.TEXT_BODY);
            TextDraw.centre(ctx, ctx.fontHeader, c, "+",
                    r.cx(), r.cy() + 6f);
        }

        ctx.batch.end();
    }

    private void drawCharacterTab() {
        // Player-as-mob detail page: name header above the framed sprite,
        // then stats, then buffs. Mirrors V2Encyclopedia.drawCharacterTab.
        String name = player.name != null ? player.name
                : (player.characterClass != null
                   ? player.characterClass.displayName : "Hero");
        TextDraw.centre(ctx, ctx.fontRegular, UiColors.TEXT_BODY,
                name, window.cx(), characterFrame.top() + 16f);

        // Aspect-fit the player's mob sprite inside the frame, with the
        // same black silhouette outline the encyclopaedia uses.
        TextureRegion region = MobSprites.regionFor(player);
        if (region != null) {
            drawAspectFit(region,
                    characterFrame.x, characterFrame.y,
                    characterFrame.w, characterFrame.h);
        }

        StatBlock st = player.effectiveStats();
        float left = window.x + 18f;
        float top = characterFrame.y - 16f;
        top = row(left, top, "HP",        ((int) Math.round(player.hp))
                + " / " + ((int) Math.round(st.maxHp)));
        top = row(left, top, "Accuracy",  Integer.toString(st.accuracy));
        top = row(left, top, "Evasion",   Integer.toString(st.evasion));
        top = row(left, top, "Attack",    st.damage.min() + "-" + st.damage.max());
        top = row(left, top, "Armor",     st.armor.min() + "-" + st.armor.max());
        top = row(left, top, "Move cost", Integer.toString(st.moveCost));
        top = row(left, top, "Atk cost",  Integer.toString(st.attackCost));
        top = row(left, top, "Light",     Integer.toString((int) st.lightRadius));
        top = row(left, top, "Satiety",   player.satiety
                + " / " + com.bjsp123.rl2.logic.GameBalance.STARTING_SATIETY);

        // Buff list — section heading + per-buff icon + name + duration.
        // Each row is a hit target that opens the encyclopedia at the
        // corresponding buff page.
        buffIconRects.clear();
        buffIconTypes.clear();
        if (player.buffs != null && !player.buffs.isEmpty()) {
            top -= 14f;
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Buffs", left, top);
            top -= 22f;
            int max = Math.min(player.buffs.size(), 8);
            for (int i = 0; i < max; i++) {
                if (top < window.y + 16f) break;
                Buff b = player.buffs.get(i);
                if (b == null || b.type == null) continue;
                var bregion = BuffIcons.regionFor(b.type);
                if (bregion != null) {
                    ctx.batch.draw(bregion, left, top - 16f, 16f, 16f);
                }
                String buffName = BuffSystem.displayName(b.type);
                TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_BODY,
                        buffName + " (lvl " + b.level + ")",
                        left + 22f, top);
                if (b.durationTurns > 0) {
                    TextDraw.right(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                            b.durationTurns + " t",
                            window.right() - 18f, top);
                }
                Rect hit = new Rect();
                hit.set(left, top - 18f, window.right() - 18f - left, 20f);
                buffIconRects.add(hit);
                buffIconTypes.add(b.type);
                top -= 20f;
            }
        }
    }

    private void drawPerksTab() {
        float left = window.x + 18f;
        float top  = tabRects[0].y - 24f;

        // Header: "Available: N" — remaining perk points the player can
        // spend. Drawn even when the perk map is empty so the count is
        // always visible.
        TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                "Available", left, top);
        TextDraw.right(ctx, ctx.fontRegular, UiColors.ACCENT,
                Integer.toString(player.perkPoints),
                window.right() - 18f, top);
        top -= 28f;

        // One row per Perk in the catalog — name on the left, current
        // level (or "--" when 0) just left of the buttons, plus + info
        // buttons rendered in the shape/text passes.
        for (int i = 0; i < perksOrdered.size(); i++) {
            if (top < window.y + 16f) break;
            Perk p = perksOrdered.get(i);
            int lvl = (player.perks != null && player.perks.get(p) != null)
                    ? player.perks.get(p) : 0;
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_BODY,
                    p.displayName(), left, top);
            float lvlRight = perkPlusRects.size() > i
                    ? perkPlusRects.get(i).x - 8f
                    : window.right() - 18f;
            String lvlText = lvl > 0 ? Integer.toString(lvl) : "--";
            TextDraw.right(ctx, ctx.fontRegular,
                    lvl > 0 ? UiColors.ACCENT : UiColors.TEXT_DIM,
                    lvlText, lvlRight, top);
            top -= 36f;
        }
    }

    private float row(float x, float y, String label, String value) {
        TextDraw.left (ctx, ctx.fontRegular, UiColors.TEXT_DIM,   label, x, y);
        TextDraw.right(ctx, ctx.fontRegular, UiColors.TEXT_BODY, value,
                window.right() - 18f, y);
        return y - 22f;
    }

    /** 8-tap unit circle for the silhouette outline — same constants as
     *  the encyclopaedia helper so player and mob entries read with the
     *  same rim. */
    private static final float[] OUTLINE_DX = {
            1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f, 0f, 0.7071f
    };
    private static final float[] OUTLINE_DY = {
            0f, 0.7071f, 1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f
    };
    private static final float OUTLINE_W = 1.5f;

    private void drawAspectFit(TextureRegion region,
                               float x, float y, float w, float h) {
        int srcW = region.getRegionWidth();
        int srcH = region.getRegionHeight();
        if (srcW <= 0 || srcH <= 0) return;
        float scale = Math.min(w / srcW, h / srcH);
        float drawW = srcW * scale;
        float drawH = srcH * scale;
        float drawX = x + (w - drawW) * 0.5f;
        float drawY = y + (h - drawH) * 0.5f;
        float oa = com.bjsp123.rl2.ui.skin.MobOutline.darkness();
        if (oa > 0f) {
            ctx.batch.setColor(0f, 0f, 0f, oa);
            for (int i = 0; i < OUTLINE_DX.length; i++) {
                ctx.batch.draw(region,
                        drawX + OUTLINE_DX[i] * OUTLINE_W,
                        drawY + OUTLINE_DY[i] * OUTLINE_W,
                        drawW, drawH);
            }
            ctx.batch.setColor(1f, 1f, 1f, 1f);
        }
        ctx.batch.draw(region, drawX, drawY, drawW, drawH);
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int p, int b) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                for (int i = 0; i < tabRects.length; i++) {
                    if (tabRects[i].contains(vx, vy)) {
                        tabPressed[i] = true;
                        return true;
                    }
                }
                for (int i = 0; i < perkInfoRects.size(); i++) {
                    if (perkInfoRects.get(i).contains(vx, vy)) {
                        perkInfoPressed = i;
                        return true;
                    }
                }
                for (int i = 0; i < perkPlusRects.size(); i++) {
                    if (perkPlusRects.get(i).contains(vx, vy)) {
                        perkPlusPressed = i;
                        return true;
                    }
                }
                for (int i = 0; i < buffIconRects.size(); i++) {
                    if (buffIconRects.get(i).contains(vx, vy)) {
                        buffIconPressed = i;
                        return true;
                    }
                }
                if (!window.contains(vx, vy)) close();
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int p, int b) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                if (perkInfoPressed >= 0) {
                    int idx = perkInfoPressed;
                    perkInfoPressed = -1;
                    if (idx < perkInfoRects.size()
                            && idx < perksOrdered.size()
                            && perkInfoRects.get(idx).contains(vx, vy)) {
                        // Jump to the encyclopaedia's perk page. Close
                        // ourselves first so the V2 single-popup-at-a-time
                        // rule holds; pass a back-stack callback so when
                        // the encyclopaedia closes we re-open at the
                        // perks tab.
                        Perk perk = perksOrdered.get(idx);
                        close();
                        if (encyclopedia != null && perk != null) {
                            encyclopedia.openTo(perk, () -> {
                                open = true;
                                currentTab = Tab.PERKS;
                            });
                        }
                    }
                    return true;
                }
                if (perkPlusPressed >= 0) {
                    int idx = perkPlusPressed;
                    perkPlusPressed = -1;
                    if (player != null && player.perkPoints > 0
                            && idx < perkPlusRects.size()
                            && idx < perksOrdered.size()
                            && perkPlusRects.get(idx).contains(vx, vy)) {
                        Perk perk = perksOrdered.get(idx);
                        if (player.perks == null) {
                            player.perks = new java.util.EnumMap<>(Perk.class);
                        }
                        int cur = player.perks.getOrDefault(perk, 0);
                        player.perks.put(perk, cur + 1);
                        player.perkPoints--;
                    }
                    return true;
                }
                if (buffIconPressed >= 0) {
                    int idx = buffIconPressed;
                    buffIconPressed = -1;
                    if (idx < buffIconRects.size()
                            && idx < buffIconTypes.size()
                            && buffIconRects.get(idx).contains(vx, vy)) {
                        Buff.BuffType type = buffIconTypes.get(idx);
                        close();
                        if (encyclopedia != null && type != null) {
                            encyclopedia.openTo(type, () -> {
                                open = true;
                                currentTab = Tab.CHARACTER;
                            });
                        }
                    }
                    return true;
                }
                for (int i = 0; i < tabRects.length; i++) {
                    if (tabPressed[i]) {
                        tabPressed[i] = false;
                        if (tabRects[i].contains(vx, vy)) {
                            currentTab = Tab.values()[i];
                        }
                        return true;
                    }
                }
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    close();
                    return true;
                }
                return false;
            }
        };
    }
}
