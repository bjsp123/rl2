package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.StatBlock;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.world.render.BuffIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 look popup - modal info panel that appears alongside {@link LookMode}
 * and shows the contents of the tile under the cursor (terrain + items + mob).
 * Anchored to the top-centre of the viewport so the world view + bottom HUD
 * stay visible behind it.
 *
 * <p>Content scrolls vertically when it exceeds the window height. A thin
 * scroll bar on the right edge indicates position.
 *
 * <p>Each section gets a "?" button when an encyclopaedia is wired -
 * tapping it opens the encyclopaedia pre-selected to that tile / mob entry,
 * deactivating look mode in the process so the V2 single-popup-at-a-time
 * rule is preserved.
 */
public final class V2Look implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    // -- Constants -------------------------------------------------------------
    /** Pixels from window top reserved for the "Look" header (title + gap). */
    private static final float HEADER_H        = 56f;
    /** Max bag items listed before a "..." truncation line. */
    private static final int   MAX_BAG_DISPLAY = 8;

    // -- Dependencies ---------------------------------------------------------
    private final UiCtx ctx;
    private LookMode lookMode;
    private Level level;
    private V2Encyclopedia encyclopedia;
    private V2BuffInfo buffInfo;

    // -- Layout ----------------------------------------------------------------
    private final Rect window      = new Rect();
    private final Rect tileInfoBtn = new Rect();
    private final Rect mobInfoBtn  = new Rect();
    private boolean tileInfoPressed, mobInfoPressed;

    /** Flavor lines for the mob description, wrapped to window width. */
    private final List<String> mobFlavorLines = new ArrayList<>();

    /** Screen Y of the tile/mob section divider; NaN when no mob is present. */
    private float shapeTileDivY = Float.NaN;
    /** Screen Y of the rule between mob flavor text and live stats; NaN when
     *  there is no flavor text to separate. */
    private float shapeMobDivY  = Float.NaN;

    /** Total content height computed during {@link #doContentLayout}, used to
     *  drive the scroll bar ratio and max-scroll clamping. */
    private float totalContentH;

    /** Content-space Y positions (0 = top, increasing downward) of the two
     *  significant section boundaries - set by {@link #doContentLayout}. */
    private float tileDivContentY;
    private float mobDivContentY;   // -1 when no mob
    private float mobHeaderContentY; // -1 when no mob

    // -- Scroll state ----------------------------------------------------------
    private final Scroller scroller = new Scroller();
    /** Last mob we computed layout for - used to detect cursor movement so
     *  the scroll position resets when the player looks at a new mob. */
    private Mob prevLookedMob;

    // -- Looked-at capture -----------------------------------------------------
    private Tile lookedTile;
    private Mob  lookedMob;

    // -- Buff icon hit targets -------------------------------------------------
    private final List<Rect>    buffIconRects = new ArrayList<>();
    private final List<Buff>    buffIconList  = new ArrayList<>();
    private final List<float[]> pendingDots   = new ArrayList<>();
    private int buffIconPressed = -1;

    public V2Look(UiCtx ctx) { this.ctx = ctx; }

    public void setLookMode(LookMode lm)            { this.lookMode = lm; }
    public void setLevel(Level lvl)                 { this.level = lvl; }
    public void setEncyclopedia(V2Encyclopedia enc) { this.encyclopedia = enc; }
    public void setBuffInfo(V2BuffInfo bi)          { this.buffInfo = bi; }

    public boolean isOpen() {
        return lookMode != null && lookMode.isPanelVisible();
    }

    @Override
    public void renderSelf() {
        if (!isOpen()) return;
        captureLookedAt();
        layoutRects();
        renderShapesPass();
        renderTextPass();
        renderDotColumns();
    }

    // -- Capture ---------------------------------------------------------------

    private void captureLookedAt() {
        lookedTile = null;
        lookedMob  = null;
        Point cursor = lookMode != null ? lookMode.cursor() : null;
        if (cursor == null || level == null) return;
        int cx = (int) Math.floor(cursor.x());
        int cy = (int) Math.floor(cursor.y());
        if (cx >= 0 && cx < level.width && cy >= 0 && cy < level.height) {
            lookedTile = level.tiles[cx][cy];
        }
        if (level.mobs != null) {
            for (Mob m : level.mobs) {
                if (m == null || m.position == null) continue;
                if ((int) Math.floor(m.position.x()) == cx
                        && (int) Math.floor(m.position.y()) == cy) {
                    lookedMob = m;
                    break;
                }
            }
        }
    }

    // -- Layout ----------------------------------------------------------------

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(320f, vw - 32f);

        // Wrap mob description into flavor lines - must happen before
        // doContentLayout so it can count them.
        mobFlavorLines.clear();
        if (lookedMob != null && lookedMob.description != null
                && !lookedMob.description.isEmpty()) {
            TextDraw.wrap(ctx.fontRegular, lookedMob.description,
                    winW - 28f, 4, mobFlavorLines);
        }

        // Measure total content height (dry-run mirrors renderTextPass).
        totalContentH = doContentLayout(winW);

        // Window - top anchored at vh-80, height capped to leave 10px at
        // the bottom of the screen.
        float maxWinH = vh - 90f;
        float winH    = Math.min(maxWinH, HEADER_H + totalContentH);
        window.set((vw - winW) * 0.5f, vh - 80f - winH, winW, winH);

        // Scroll clamping.
        float visH = winH - HEADER_H;
        scroller.setMaxScroll(Math.max(0f, totalContentH - visH));

        // Reset scroll when looking at a new mob (cursor moved to a different
        // tile that has a mob).
        if (prevLookedMob != lookedMob) {
            prevLookedMob = lookedMob;
            scroller.resetTop();
        }

        // Convert stored content-Y positions to screen Y for the shapes pass.
        // screenY = contentAreaTop + scrollY - contentY
        float caTop   = window.top() - HEADER_H;
        float scrollOff = scroller.scrollY();

        shapeTileDivY = (lookedMob != null)
                ? caTop + scrollOff - tileDivContentY
                : Float.NaN;
        shapeMobDivY  = (mobDivContentY >= 0f && !mobFlavorLines.isEmpty())
                ? caTop + scrollOff - mobDivContentY
                : Float.NaN;

        // "?" buttons - scroll with their respective section headings.
        float infoSz   = 22f;
        float topPad   = ctx.lineH() * 0.5f;
        tileInfoBtn.set(window.right() - 14f - infoSz,
                caTop + scrollOff - topPad - infoSz, // beside tile name header
                infoSz, infoSz);
        if (mobHeaderContentY >= 0f) {
            mobInfoBtn.set(window.right() - 14f - infoSz,
                    caTop + scrollOff - mobHeaderContentY - infoSz + 6f,
                    infoSz, infoSz);
        }
    }

    /**
     * Dry-run of the content layout - mirrors {@link #renderTextPass} but only
     * accumulates heights. Sets {@link #tileDivContentY}, {@link #mobDivContentY},
     * and {@link #mobHeaderContentY} as side effects. Returns total content height.
     */
    private float doContentLayout(float winW) {
        tileDivContentY   = 0f;
        mobDivContentY    = -1f;
        mobHeaderContentY = -1f;

        float lh  = ctx.lineH();
        float hlh = ctx.headerLineH();
        float textW = winW - 28f;

        float h = lh * 0.5f; // top padding - breathing room below the separator
        Point cursor = lookMode != null ? lookMode.cursor() : null;
        if (cursor == null || level == null) return h;
        int cx = (int) Math.floor(cursor.x());
        int cy = (int) Math.floor(cursor.y());
        boolean inBounds = cx >= 0 && cy >= 0 && cx < level.width && cy < level.height;

        // Tile section - name is the section header.
        if (lookedTile != null) h += hlh;
        if (inBounds && level.surface != null && level.surface[cx][cy] != null) h += lh;
        if (inBounds && level.vegetation != null && level.vegetation[cx][cy] != null) h += lh;
        if (inBounds && level.cloud != null && level.cloud[cx][cy] != 0
                && com.bjsp123.rl2.logic.CloudSystem.type(level.cloud[cx][cy]) != null) h += lh;
        if (lookedTile != null) h += lh * 0.5f; // section gap

        // Floor items - each item name is its own header.
        if (level.items != null) {
            int count = 0;
            for (Item it : level.items) {
                if (it == null || it.location == null) continue;
                if ((int) Math.floor(it.location.x()) == cx
                        && (int) Math.floor(it.location.y()) == cy) {
                    String name = com.bjsp123.rl2.logic.ItemNames.displayName(it, null);
                    h += TextDraw.block(ctx.fontHeader, name, textW, 2, hlh).height();
                    count++;
                    if (count >= 3) break;
                }
            }
        }

        // Record where the tile/mob divider goes.
        tileDivContentY = h;
        if (lookedMob != null) h += lh; // divider gap

        if (lookedMob != null) {
            // Flavor text block.
            h += mobFlavorLines.size() * lh;
            mobDivContentY = h;
            if (!mobFlavorLines.isEmpty()) h += lh * 0.6f; // rule gap

            // Mob live-detail block - name is the section header.
            mobHeaderContentY = h;
            h += mobHeaderBlock(textW).height();
            h += lh;  // HP
            h += lh;  // Att / Def
            h += lh;  // Dmg / Arm
            StatBlock s = lookedMob.effectiveStats();
            if (!s.rangedDamage.isZero()) h += lh;
            Mob viewer = com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
            if (viewer != null && lookedMob != viewer
                    && attitudeLabel(viewer, lookedMob) != null) h += lh;
            if (stateOfMindLabel(lookedMob) != null) h += lh;
            if (lookedMob.owner != null) h += lh;
            if (lookedMob.buffs != null && !lookedMob.buffs.isEmpty()) h += lh;

            // Equipped items.
            if (lookedMob.inventory != null) {
                List<Item> eq = lookedMob.inventory.allEquipped();
                if (!eq.isEmpty()) {
                    h += lh;               // "Equipped:" header
                    for (Item it : eq) {
                        if (it == null) continue;
                        String name = "  " + com.bjsp123.rl2.logic.ItemNames.displayName(it, null);
                        h += TextDraw.block(ctx.fontRegular, name, textW, 2, lh).height();
                    }
                }
                // Bag items.
                int bagCount = 0;
                for (Item it : lookedMob.inventory.bag) if (it != null) bagCount++;
                if (bagCount > 0) {
                    h += lh;               // "Bag:" header
                    int shown = Math.min(bagCount, MAX_BAG_DISPLAY);
                    int seen = 0;
                    for (Item it : lookedMob.inventory.bag) {
                        if (it == null) continue;
                        if (seen >= shown) break;
                        String iname = com.bjsp123.rl2.logic.ItemNames.displayName(it, null);
                        if (it.count > 1) iname += " x" + it.count;
                        h += TextDraw.block(ctx.fontRegular, "  " + iname, textW, 2, lh).height();
                        seen++;
                    }
                    if (bagCount > MAX_BAG_DISPLAY) h += lh; // "..." line
                }
            }
        }

        return h;
    }

    // -- Shapes pass -----------------------------------------------------------

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Thin separator between the fixed header and the scrollable body -
        // masks any ascender bleed from the top content line.
        float caTop = window.top() - HEADER_H;
        s.setColor(UIVars.BORDER_MID);
        s.rect(window.x + 4f, caTop, window.w - 8f, 1f);

        float caBot = window.y;

        // "?" buttons - only draw when scrolled into view.
        if (encyclopedia != null && lookedTile != null
                && tileInfoBtn.top() <= caTop + 2f && tileInfoBtn.y >= caBot) {
            drawInfoBtn(s, tileInfoBtn, tileInfoPressed);
        }
        if (encyclopedia != null && lookedMob != null
                && mobInfoBtn.top() <= caTop + 2f && mobInfoBtn.y >= caBot) {
            drawInfoBtn(s, mobInfoBtn, mobInfoPressed);
        }

        // Mob flavor-text / live-stats rule.
        if (!Float.isNaN(shapeMobDivY)
                && shapeMobDivY >= caBot && shapeMobDivY <= caTop) {
            s.setColor(UIVars.BORDER_MID);
            s.rect(window.x + 14f, shapeMobDivY, window.w - 28f, 1f);
        }

        // Tile / mob section chrome divider.
        if (!Float.isNaN(shapeTileDivY)
                && shapeTileDivY >= caBot && shapeTileDivY <= caTop) {
            Edges.drawTriLine(s, window.x + 8f, shapeTileDivY,
                    window.w - 16f, 4f, 1f);
        }

        // Scroll bar - shown when content is taller than the visible area.
        float visH = window.h - HEADER_H;
        if (totalContentH > visH + 1f) {
            float barW  = 3f;
            float ratio = visH / totalContentH;
            float barH  = Math.max(12f, visH * ratio);
            float scrollFrac = scroller.scrollY() / (totalContentH - visH);
            // scrollFrac 0 = bar at top of content, 1 = bar at bottom.
            float barY = caBot + (visH - barH) * (1f - scrollFrac);
            s.setColor(UIVars.BORDER_MID);
            s.rect(window.right() - 4f - barW, barY, barW, barH);
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawInfoBtn(ShapeRenderer s, Rect r, boolean pressed) {
        ButtonChrome.shape(ctx, r, pressed, false, false, UIVars.BTN_BG);
    }

    // -- Text pass -------------------------------------------------------------

    private void renderTextPass() {
        ctx.batch.begin();

        // Fixed header - always visible, never scrolls.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.look.title"),
                window.cx(), window.top() - ctx.headerLineH());

        Point cursor = lookMode != null ? lookMode.cursor() : null;
        if (cursor == null || level == null) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.get("ui.look.noTile"), window.cx(), window.top() - 56f);
            ctx.batch.end();
            return;
        }

        int cx = (int) Math.floor(cursor.x());
        int cy = (int) Math.floor(cursor.y());
        float left   = window.x + 14f;
        float textW  = window.w - 28f;
        float caTop  = window.top() - HEADER_H;  // content area ceiling
        float caBot  = window.y + 2f;             // content area floor
        float drawY  = caTop + scroller.scrollY(); // current line baseline

        boolean inBounds = cx >= 0 && cy >= 0 && cx < level.width && cy < level.height;

        float lh  = ctx.lineH();
        float hlh = ctx.headerLineH();

        drawY -= lh * 0.5f; // top padding

        // --- TILE SECTION --- name is the section header
        if (lookedTile != null) {
            if (inView(drawY, caTop, caBot)) {
                TextDraw.leftFit(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                        lookedTile.name().toLowerCase(), left, drawY, textW);
                if (encyclopedia != null
                        && tileInfoBtn.top() <= caTop + 2f && tileInfoBtn.y >= caBot) {
                    ButtonChrome.icon(ctx, tileInfoBtn,
                            com.bjsp123.rl2.world.render.IconSprites.regionFor(
                                    com.bjsp123.rl2.world.render.IconSprites.Icon.INFO),
                            tileInfoPressed, false);
                }
            }
            drawY -= hlh;
        }

        if (inBounds && level.surface != null && level.surface[cx][cy] != null) {
            if (inView(drawY, caTop, caBot)) {
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        "  " + describeSurface(level.surface[cx][cy]), left, drawY, textW);
            }
            drawY -= lh;
        }

        if (inBounds && level.vegetation != null && level.vegetation[cx][cy] != null) {
            if (inView(drawY, caTop, caBot)) {
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        "  " + describeVegetation(level.vegetation[cx][cy]), left, drawY, textW);
            }
            drawY -= lh;
        }

        if (inBounds && level.cloud != null && level.cloud[cx][cy] != 0) {
            com.bjsp123.rl2.model.Level.Cloud type =
                    com.bjsp123.rl2.logic.CloudSystem.type(level.cloud[cx][cy]);
            if (type != null) {
                if (inView(drawY, caTop, caBot)) {
                    TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            "  " + type.name().toLowerCase().replace('_', ' '),
                            left, drawY, textW);
                }
                drawY -= lh;
            }
        }

        if (lookedTile != null) drawY -= lh * 0.5f; // section gap

        // Floor items - each name is its own header.
        if (level.items != null) {
            int count = 0;
            for (Item it : level.items) {
                if (it == null || it.location == null) continue;
                int ix = (int) Math.floor(it.location.x());
                int iy = (int) Math.floor(it.location.y());
                if (ix == cx && iy == cy) {
                    TextDraw.TextBlock itemName = TextDraw.block(ctx.fontHeader,
                            com.bjsp123.rl2.logic.ItemNames.displayName(it, null),
                            textW, 2, hlh);
                    if (inView(drawY, caTop, caBot)) {
                        TextDraw.wrapped(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                                itemName, left, drawY);
                    }
                    drawY -= itemName.height();
                    count++;
                    if (count >= 3) break;
                }
            }
        }

        // Consume the tile/mob divider gap (divider itself is shapes pass).
        if (lookedMob != null) drawY -= lh;

        // --- MOB SECTION ---
        if (lookedMob != null) {
            // Flavor text - bright, above the rule.
            for (String line : mobFlavorLines) {
                if (inView(drawY, caTop, caBot)) {
                    TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                            line, left, drawY);
                }
                drawY -= lh;
            }
            if (!mobFlavorLines.isEmpty()) drawY -= lh * 0.6f; // rule gap

            // Live-stats block.
            drawY = renderMobBlockAt(left, drawY, caTop, caBot, textW);

            // Mob "?" label - drawn after renderMobBlockAt so it appears on top
            // of any overlapping buff icons drawn to the batch.
            if (encyclopedia != null
                    && mobInfoBtn.top() <= caTop + 2f && mobInfoBtn.y >= caBot) {
                ButtonChrome.icon(ctx, mobInfoBtn,
                        com.bjsp123.rl2.world.render.IconSprites.regionFor(
                                com.bjsp123.rl2.world.render.IconSprites.Icon.INFO),
                        mobInfoPressed, false);
            }
        }

        ctx.batch.end();
    }

    /** Returns {@code true} when a line with its baseline at {@code y} is
     *  within the scrollable content area and should be drawn. */
    private static boolean inView(float y, float caTop, float caBot) {
        return y <= caTop && y >= caBot;
    }

    /**
     * Render the mob live-stats block top-down starting at {@code drawY}.
     * Returns the Y cursor after the last rendered element.
     * Buff icon hit-rects are rebuilt here (cleared before this call).
     */
    private float renderMobBlockAt(float left, float drawY, float caTop, float caBot,
                                   float textW) {
        Mob m = lookedMob;
        StatBlock s = m.effectiveStats();

        // Name - section header.
        TextDraw.TextBlock header = mobHeaderBlock(textW);
        if (inView(drawY, caTop, caBot)) {
            TextDraw.wrapped(ctx, ctx.fontHeader, UIVars.TEXT_BODY, header, left, drawY);
        }
        float lh = ctx.lineH();
        drawY -= header.height();

        // HP.
        if (inView(drawY, caTop, caBot)) {
            int hp    = (int) Math.round(m.hp);
            int maxHp = (int) Math.round(s.maxHp);
            TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.format("ui.look.hp",
                            TextCatalog.vars("hp", hp, "maxHp", maxHp)),
                    left, drawY, textW);
        }
        drawY -= lh;

        // Att / Def.
        if (inView(drawY, caTop, caBot)) {
            TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.format("ui.look.attackDefense",
                            TextCatalog.vars("attack", s.accuracy, "defense", s.evasion)),
                    left, drawY, textW);
        }
        drawY -= lh;

        // Dmg / Arm.
        if (inView(drawY, caTop, caBot)) {
            TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    TextCatalog.format("ui.look.damageArmor",
                            TextCatalog.vars("damage", range(s.damage), "armor", range(s.armor))),
                    left, drawY, textW);
        }
        drawY -= lh;

        // Ranged (optional).
        if (!s.rangedDamage.isZero()) {
            if (inView(drawY, caTop, caBot)) {
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        TextCatalog.format("ui.look.ranged",
                                TextCatalog.vars("damage", range(s.rangedDamage),
                                        "distance", s.rangedDistance)),
                        left, drawY, textW);
            }
            drawY -= lh;
        }

        // Attitude toward the player.
        Mob viewer = com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        if (viewer != null && m != viewer) {
            String al = attitudeLabel(viewer, m);
            if (al != null) {
                if (inView(drawY, caTop, caBot)) {
                    TextDraw.leftFit(ctx, ctx.fontRegular, attitudeColor(viewer, m),
                            al, left, drawY, textW);
                }
                drawY -= lh;
            }
        }

        // State of mind + intent.
        String state = stateOfMindLabel(m);
        if (state != null) {
            if (inView(drawY, caTop, caBot)) {
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM, state, left, drawY, textW);
            }
            drawY -= lh;
        }

        // Loyalty.
        if (m.owner != null) {
            String ownerLabel = m.owner.behavior == Mob.Behavior.PLAYER
                    ? TextCatalog.get("ui.look.owner.you")
                    : (m.owner.mobType != null ? m.owner.mobType.toLowerCase()
                            : TextCatalog.get("ui.look.owner.unknown"));
            if (inView(drawY, caTop, caBot)) {
                TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        TextCatalog.format("ui.look.loyalTo",
                                TextCatalog.vars("owner", ownerLabel)),
                        left, drawY, textW);
            }
            drawY -= lh;
        }

        // Buff icons row - always rebuild hit rects regardless of visibility
        // so touch targets stay accurate.
        buffIconRects.clear();
        buffIconList.clear();
        pendingDots.clear();
        if (m.buffs != null && !m.buffs.isEmpty()) {
            float iconSz = 16f, iconGap = 2f;
            int max = Math.min(m.buffs.size(), 8);
            for (int i = 0; i < max; i++) {
                Buff b = m.buffs.get(i);
                if (b == null || b.type == null) continue;
                TextureRegion region = BuffIcons.regionFor(b.type);
                if (region == null) continue;
                float ix = left + i * (iconSz + iconGap);
                float iy = drawY - iconSz;
                if (iy + iconSz >= window.y && iy <= caTop) {
                    ctx.batch.draw(region, ix, iy, iconSz, iconSz);
                }
                Rect r = new Rect();
                r.set(ix, iy, iconSz, iconSz);
                buffIconRects.add(r);
                buffIconList.add(b);
                if (b.durationTurns > 0) {
                    pendingDots.add(new float[]{ ix + iconSz + 1f, iy, b.durationTurns });
                }
            }
            drawY -= lh;
        }

        // Equipped items.
        if (m.inventory != null) {
            List<Item> eq = m.inventory.allEquipped();
            if (!eq.isEmpty()) {
                if (inView(drawY, caTop, caBot)) {
                    TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            TextCatalog.get("ui.look.equipped"), left, drawY);
                }
                drawY -= lh;
                for (Item it : eq) {
                    if (it == null) continue;
                    TextDraw.TextBlock itemName = TextDraw.block(ctx.fontRegular,
                            "  " + com.bjsp123.rl2.logic.ItemNames.displayName(it, null),
                            textW, 2, lh);
                    if (inView(drawY, caTop, caBot)) {
                        TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                                itemName, left, drawY);
                    }
                    drawY -= itemName.height();
                }
            }

            // Bag items.
            int bagNonNull = 0;
            for (Item it : m.inventory.bag) if (it != null) bagNonNull++;
            if (bagNonNull > 0) {
                if (inView(drawY, caTop, caBot)) {
                    TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            TextCatalog.get("ui.look.bag"), left, drawY);
                }
                drawY -= lh;
                int shown = 0;
                for (Item it : m.inventory.bag) {
                    if (it == null) continue;
                    if (shown >= MAX_BAG_DISPLAY) {
                        if (inView(drawY, caTop, caBot)) {
                            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                                    "  ...", left, drawY);
                        }
                        drawY -= lh;
                        break;
                    }
                    String iname = com.bjsp123.rl2.logic.ItemNames.displayName(it, null);
                    if (it.count > 1) iname += " x" + it.count;
                    TextDraw.TextBlock itemName = TextDraw.block(ctx.fontRegular,
                            "  " + iname, textW, 2, lh);
                    if (inView(drawY, caTop, caBot)) {
                        TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                                itemName, left, drawY);
                    }
                    drawY -= itemName.height();
                    shown++;
                }
            }
        }

        return drawY;
    }

    private TextDraw.TextBlock mobHeaderBlock(float textW) {
        if (lookedMob == null) {
            return TextDraw.block(ctx.fontHeader, "", textW, 0, ctx.headerLineH());
        }
        String mname = lookedMob.name != null ? lookedMob.name : TextCatalog.get("ui.look.mobFallback");
        String header = mname;
        if (lookedMob.characterLevel > 1) {
            header = TextCatalog.format("ui.look.mobLevel",
                    TextCatalog.vars("name", mname, "level", lookedMob.characterLevel));
        }
        return TextDraw.block(ctx.fontHeader, header, textW, 2, ctx.headerLineH());
    }

    private void renderDotColumns() {
        if (pendingDots.isEmpty()) return;
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(UIVars.TEXT_DIM);
        for (float[] d : pendingDots) {
            int dots = Math.min(8, (int) d[2]);
            for (int i = 0; i < dots; i++) {
                s.rect(d[0], d[1] + i * 2f, 1f, 1f);
            }
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // -- Static helpers --------------------------------------------------------

    private static String describeSurface(com.bjsp123.rl2.model.Level.Surface s) {
        return switch (s) {
            case WATER -> TextCatalog.get("ui.look.surface.water");
            case BLOOD -> TextCatalog.get("ui.look.surface.blood");
            case OIL   -> TextCatalog.get("ui.look.surface.oil");
            case ICE   -> TextCatalog.get("ui.look.surface.ice");
        };
    }

    private static String describeVegetation(com.bjsp123.rl2.model.Level.Vegetation v) {
        return switch (v) {
            case GRASS     -> TextCatalog.get("ui.look.terrain.grass");
            case MUSHROOMS -> TextCatalog.get("ui.look.terrain.mushrooms");
            case FIRE      -> TextCatalog.get("ui.look.terrain.fire");
            case TREES     -> TextCatalog.get("ui.look.terrain.trees");
        };
    }

    private static String attitudeLabel(Mob viewer, Mob target) {
        com.bjsp123.rl2.logic.MobSystem.Attitude a =
                com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(target, viewer);
        if (a == null) return null;
        return switch (a) {
            case ATTACK  -> TextCatalog.get("ui.look.attitude.attack");
            case FLEE    -> TextCatalog.get("ui.look.attitude.flee");
            case NOTHING -> TextCatalog.get("ui.look.attitude.nothing");
            case ALLY    -> TextCatalog.get("ui.look.attitude.ally");
        };
    }

    private static final com.badlogic.gdx.graphics.Color ATTITUDE_FRIENDLY =
            new com.badlogic.gdx.graphics.Color(0.4f, 0.85f, 0.4f, 1f);

    private static com.badlogic.gdx.graphics.Color attitudeColor(Mob viewer, Mob target) {
        com.bjsp123.rl2.logic.MobSystem.Attitude a =
                com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(target, viewer);
        if (a == null) return UIVars.TEXT_DIM;
        return switch (a) {
            case ATTACK  -> UIVars.TEXT_WARN;
            case FLEE    -> UIVars.ACCENT;
            case NOTHING -> UIVars.TEXT_DIM;
            case ALLY    -> ATTITUDE_FRIENDLY;
        };
    }

    private static String stateOfMindLabel(Mob m) {
        if (m.behavior == Mob.Behavior.PLAYER) return TextCatalog.get("ui.look.state.player");
        if (m.stateOfMind == null) return null;
        String prefix = switch (m.stateOfMind) {
            case ASLEEP         -> TextCatalog.get("ui.look.state.asleep");
            case AWAKE          -> TextCatalog.get("ui.look.state.awake");
            case SEEKING_HIDING -> TextCatalog.get("ui.look.state.seekingHiding");
            case HIDING         -> TextCatalog.get("ui.look.state.hiding");
            case FOLLOWING      -> TextCatalog.get("ui.look.state.following");
        };
        String suffix = m.intent == null ? "" : switch (m.intent) {
            case IDLE               -> "";
            case WANDERING          -> TextCatalog.get("ui.look.intent.wandering");
            case PURSUING           -> TextCatalog.get("ui.look.intent.pursuing");
            case CHASING_LAST_KNOWN -> TextCatalog.get("ui.look.intent.chasingLastKnown");
            case SHOOTING           -> TextCatalog.get("ui.look.intent.shooting");
            case KITING             -> TextCatalog.get("ui.look.intent.kiting");
            case FLEEING            -> TextCatalog.get("ui.look.intent.fleeing");
            case FOLLOWING_LEADER   -> TextCatalog.get("ui.look.intent.followingLeader");
        };
        return prefix + suffix;
    }

    private static String range(MinMax mm) {
        if (mm == null || (mm.min() == 0 && mm.max() == 0)) return "0";
        return mm.min() == mm.max() ? Integer.toString(mm.min())
                                    : mm.min() + "-" + mm.max();
    }

    // -- Input -----------------------------------------------------------------

    public InputProcessor input() {
        return new InputAdapter() {

            private boolean dragging;

            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                dragging = false;

                // "?" buttons - only hittable when scrolled into view.
                float caTop = window.top() - HEADER_H;
                if (encyclopedia != null && lookedTile != null
                        && tileInfoBtn.top() <= caTop + 2f && tileInfoBtn.y >= window.y
                        && tileInfoBtn.contains(vx, vy)) {
                    tileInfoPressed = true;
                    scroller.onTouchDown(vy);
                    return true;
                }
                if (encyclopedia != null && lookedMob != null
                        && mobInfoBtn.top() <= caTop + 2f && mobInfoBtn.y >= window.y
                        && mobInfoBtn.contains(vx, vy)) {
                    mobInfoPressed = true;
                    scroller.onTouchDown(vy);
                    return true;
                }
                for (int i = 0; i < buffIconRects.size(); i++) {
                    if (buffIconRects.get(i).contains(vx, vy)) {
                        buffIconPressed = i;
                        scroller.onTouchDown(vy);
                        return true;
                    }
                }
                if (!window.contains(vx, vy)) {
                    if (lookMode != null) lookMode.toggle();
                    return true;
                }
                scroller.onTouchDown(vy);
                return true;
            }

            @Override
            public boolean touchDragged(int sx, int sy, int pointer) {
                if (!isOpen()) return false;
                float vy = ctx.unprojectY(sx, sy);
                if (scroller.onTouchDragged(vy)) {
                    dragging = true;
                    tileInfoPressed = false;
                    mobInfoPressed  = false;
                    buffIconPressed = -1;
                }
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                scroller.onTouchUp();
                boolean wasDragging = dragging;
                dragging = false;

                if (tileInfoPressed) {
                    tileInfoPressed = false;
                    if (!wasDragging && encyclopedia != null
                            && tileInfoBtn.contains(vx, vy) && lookedTile != null) {
                        Tile t = lookedTile;
                        if (lookMode != null) lookMode.toggle();
                        encyclopedia.openTo(t);
                    }
                    return true;
                }
                if (mobInfoPressed) {
                    mobInfoPressed = false;
                    if (!wasDragging && encyclopedia != null
                            && mobInfoBtn.contains(vx, vy) && lookedMob != null) {
                        String mobType = lookedMob.mobType;
                        if (lookMode != null) lookMode.toggle();
                        if (mobType != null) encyclopedia.openTo(mobType);
                    }
                    return true;
                }
                if (buffIconPressed >= 0) {
                    int idx = buffIconPressed;
                    buffIconPressed = -1;
                    if (!wasDragging && idx < buffIconRects.size()
                            && idx < buffIconList.size()
                            && buffIconRects.get(idx).contains(vx, vy)) {
                        Buff b = buffIconList.get(idx);
                        if (buffInfo != null && b != null) buffInfo.open(b);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (!isOpen()) return false;
                scroller.onScrolled(amountY, 16f);
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!isOpen()) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    if (lookMode != null) lookMode.toggle();
                    return true;
                }
                return false;
            }
        };
    }
}
