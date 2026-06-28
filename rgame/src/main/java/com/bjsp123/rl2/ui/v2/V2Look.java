package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.logic.ItemNames;
import com.bjsp123.rl2.logic.ItemStats;
import com.bjsp123.rl2.logic.MobStats;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.StatBlock;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.TileSprites;
import com.bjsp123.rl2.world.render.SurfaceSprites;
import com.bjsp123.rl2.model.Buff;
import com.badlogic.gdx.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/** Compact look popup: terrain, floor item, mob, plus direct info buttons. */
public final class V2Look implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    /** Top padding inside each look window. No title is drawn (the popup's
     *  content makes its subject obvious), so this is just breathing room
     *  above the first content row. */
    private static final float HEADER_H = 14f;
    private static final float PAD = 12f;
    /** Info-button size. Chunky (a clear tappable square, not a tiny glyph)
     *  so the "open the encyclopedia entry" affordance is obvious. */
    private static final float INFO = 40f;
    private static final float SECTION_GAP = 8f;
    private static final float DETAIL_INDENT = 12f;
    /** Vertical breathing room after the title and between detail lines. With a
     *  single subject on screen there's room to space things out for legibility. */
    private static final float TITLE_GAP = 8f;
    private static final float DETAIL_GAP = 6f;
    private static final float BUFF_ICON_SZ = 14f;
    private static final float BUFF_ICON_GAP = 2f;
    /** Height of the mob HP bar drawn in the look popup - chunky so the
     *  current/max numeric reads at a glance. */
    private static final float HP_BAR_H   = 18f;
    private static final float HP_BAR_GAP = 4f;
    private static final int INFO_TERRAIN = 1;
    private static final int INFO_ITEM = 2;
    private static final int INFO_MOB = 3;

    private final UiCtx ctx;
    private LookMode lookMode;
    private Level level;
    private V2Encyclopedia encyclopedia;
    @SuppressWarnings("unused")
    private V2BuffInfo buffInfo;

    private final Rect window = new Rect();
    private final Rect terrainInfoBtn = new Rect();
    private final Rect itemInfoBtn = new Rect();
    private final Rect mobInfoBtn = new Rect();
    private int pressedInfo;

    private final List<Rect> buffHitRects = new ArrayList<>();
    private final List<Buff.BuffType> buffHitTypes = new ArrayList<>();
    private int pressedBuff = -1;

    private Tile lookedTile;
    private Item lookedItem;
    private Mob lookedMob;
    private int cx, cy;

    /** Terrain mode (no mob / item on the tile): the popup becomes a list of
     *  Terrain / Material / Surface / Clouds rows instead of a section block. */
    private boolean terrainMode;
    private final List<TRow> terrainRows = new ArrayList<>();
    private int pressedTerrainRow = -1;
    /** Image cell + row geometry for the terrain list. */
    private static final float ROW_IMG = 34f;
    private static final float ROW_H   = 46f;

    /** Item mode (floor item, no mob): a picture + name + flavour + level-aware
     *  encyclopedia-style detail block, mirroring the inventory detail popup. */
    private boolean itemMode;
    private static final float ITEM_ICON = 64f;
    private TextureRegion itemIcon;
    private TextDraw.TextBlock itemNameBlock, itemFlavorBlock, itemDetailBlock;
    private float itemIconY, itemNameTop, itemFlavorY, itemDetailY, itemSepY, itemUseNowY;

    /** Mob mode: name+level, picture, description, tip, a "if you fought this
     *  now" combat readout, special abilities, and a scrollable carried list. */
    private boolean mobMode;
    private static final float MOB_IMG_H = 64f;
    private TextureRegion mobImage;
    private float mobImgX, mobImgY, mobImgW;
    private TextDraw.TextBlock mobNameBlock, mobDescBlock, mobTipBlock, mobCombatBlock, mobSpecialsBlock;
    private float mobDescY, mobTipY, mobFightHdrY, mobCombatY, mobSpecialsY, mobCarryLabelY, mobSepY;

    private final List<Section> sections = new ArrayList<>();

    /** Looked-at mob's carried (bag) items, shown in their own scrollable frame
     *  so a long inventory isn't truncated into one detail line. */
    private final List<Item> carried = new ArrayList<>();
    private final ScrollBand carriedBand = new ScrollBand();
    private float carriedLabelY = Float.NaN;   // set in layoutRects when carried is non-empty
    /** Max carried rows shown before the frame scrolls. */
    private static final int MAX_CARRIED_ROWS = 5;

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
        buildSections();
        if (terrainMode) {
            layoutTerrain();
            renderTerrainShapes();
            renderTerrainText();
        } else if (itemMode) {
            layoutItem();
            renderItemShapes();
            renderItemText();
        } else if (mobMode) {
            layoutMob();
            renderMobShapes();
            renderMobText();
        } else {
            layoutRects();
            renderShapesPass();
            renderTextPass();
        }
    }

    private void captureLookedAt() {
        lookedTile = null;
        lookedItem = null;
        lookedMob = null;
        cx = cy = -1;

        Point cursor = lookMode != null ? lookMode.cursor() : null;
        if (cursor == null || level == null) return;
        cx = (int) Math.floor(cursor.x());
        cy = (int) Math.floor(cursor.y());
        if (!inBounds()) return;

        lookedTile = level.tiles[cx][cy];
        if (level.items != null) {
            for (Item it : level.items) {
                if (it == null || it.location == null) continue;
                if ((int) Math.floor(it.location.x()) == cx
                        && (int) Math.floor(it.location.y()) == cy) {
                    lookedItem = it;
                    break;
                }
            }
        }
        if (level.mobs != null) {
            for (Mob m : level.mobs) {
                if (m == null || m.position == null) continue;
                if (m.position.tileX() == cx && m.position.tileY() == cy) {
                    lookedMob = m;
                    break;
                }
            }
        }
    }

    private boolean inBounds() {
        return level != null && cx >= 0 && cy >= 0
                && cx < level.width && cy < level.height;
    }

    private void buildSections() {
        sections.clear();
        carried.clear();
        terrainMode = false;
        itemMode = false;
        mobMode = false;
        if (!inBounds()) {
            sections.add(new Section(TextCatalog.get("ui.look.noTile"), INFO_TERRAIN));
            return;
        }
        // Priority display: a mob on the tile takes the whole window; failing
        // that a floor item (picture + encyclopedia-style detail); failing that
        // the terrain (Terrain / Material / Surface / Clouds list).
        if (lookedMob != null) {
            mobMode = true;           // rendered by layoutMob / renderMob*
            if (lookedMob.inventory != null && lookedMob.inventory.bag != null) {
                carried.addAll(lookedMob.inventory.bag);
            }
        } else if (lookedItem != null) {
            itemMode = true;          // rendered by layoutItem / renderItem*
        } else {
            buildTerrainRows();
            terrainMode = true;
        }
    }

    /** Terrain mode: the four fixed rows. Present things carry an image, a name
     *  and an encyclopedia info button; absent ones read "none". */
    private void buildTerrainRows() {
        terrainRows.clear();
        // Terrain - always present.
        terrainRows.add(new TRow(TextCatalog.get("ui.look.row.terrain"),
                terrainName(),
                TileSprites.regionFor(lookedTile, level.theme), null, lookedTile));
        // Material (vegetation).
        Level.Vegetation veg = level.vegetation != null ? level.vegetation[cx][cy] : null;
        terrainRows.add(veg == null
                ? TRow.none(TextCatalog.get("ui.look.row.material"))
                : new TRow(TextCatalog.get("ui.look.row.material"),
                        describeVegetation(veg), SurfaceSprites.regionFor(veg), null, veg));
        // Surface.
        Level.Surface surf = level.surface != null ? level.surface[cx][cy] : null;
        terrainRows.add(surf == null
                ? TRow.none(TextCatalog.get("ui.look.row.surface"))
                : new TRow(TextCatalog.get("ui.look.row.surface"),
                        describeSurface(surf), SurfaceSprites.regionFor(surf), null, surf));
        // Clouds - procedural, so a colour swatch stands in for the image.
        Level.Cloud cloud = (level.cloud != null && level.cloud[cx][cy] != 0)
                ? com.bjsp123.rl2.logic.CloudSystem.type(level.cloud[cx][cy]) : null;
        terrainRows.add(cloud == null
                ? TRow.none(TextCatalog.get("ui.look.row.clouds"))
                : new TRow(TextCatalog.get("ui.look.row.clouds"),
                        TextCatalog.getOrDefault("ui.look.cloud." + cloud.name(), pretty(cloud.name())),
                        null, cloudColor(cloud), cloud));
    }

    /** Cloud tint matching the in-world FxRenderer puffs (no sprite exists). */
    private static Color cloudColor(Level.Cloud c) {
        return switch (c) {
            case SMOKE  -> new Color(0.35f, 0.35f, 0.38f, 1f);
            case STEAM  -> new Color(0.85f, 0.88f, 0.92f, 1f);
            case POISON -> new Color(0.30f, 0.70f, 0.28f, 1f);
            case SPORE  -> new Color(0.74f, 0.66f, 0.30f, 1f);
        };
    }

    // -- Terrain-list layout / render ----------------------------------------

    private void layoutTerrain() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(440f, vw - 28f);
        float winH = Math.min(vh - 92f, HEADER_H + PAD * 2f + terrainRows.size() * ROW_H);
        window.set((vw - winW) * 0.5f, vh - 78f - winH, winW, winH);

        float y = window.top() - HEADER_H - PAD;   // top edge of the first row
        for (TRow r : terrainRows) {
            r.rowTop = y;
            r.infoBtn.set(0, 0, 0, 0);
            if (r.hasInfo()) {
                r.infoBtn.set(window.right() - PAD - INFO,
                        y - ROW_H + (ROW_H - INFO) * 0.5f, INFO, INFO);
            }
            y -= ROW_H;
        }
    }

    private void renderTerrainShapes() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);

        float imgX = window.x + PAD;
        for (int i = 0; i < terrainRows.size(); i++) {
            TRow r = terrainRows.get(i);
            if (r.present()) {
                float imgY = r.rowTop - ROW_H + (ROW_H - ROW_IMG) * 0.5f;
                Edges.drawTriLine(s, imgX, imgY, ROW_IMG, ROW_IMG, 1f);
                if (r.swatch != null) {           // procedural cloud - filled swatch
                    s.setColor(r.swatch);
                    s.rect(imgX + 3, imgY + 3, ROW_IMG - 6, ROW_IMG - 6);
                }
                if (r.hasInfo()) {
                    ButtonChrome.shape(ctx, r.infoBtn, pressedTerrainRow == i,
                            false, false, UIVars.BTN_BG);
                }
            }
            if (i < terrainRows.size() - 1) {
                Window.drawInfoSeparator(ctx, window.x + PAD, r.rowTop - ROW_H,
                        window.w - PAD * 2f);
            }
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderTerrainText() {
        ctx.batch.begin();
        TextureRegion infoIcon = IconSprites.regionFor(IconSprites.Icon.INFO);
        float imgX  = window.x + PAD;
        float textX = imgX + ROW_IMG + 10f;
        for (TRow r : terrainRows) {
            float baseTop = r.rowTop - ROW_H * 0.5f + ctx.lineH() * 0.5f;
            float tx = r.present() ? textX : imgX;
            if (r.icon != null) {
                float imgY = r.rowTop - ROW_H + (ROW_H - ROW_IMG) * 0.5f;
                ctx.batch.draw(r.icon, imgX, imgY, ROW_IMG, ROW_IMG);
            }
            String lbl = r.label + ":";
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM, lbl, tx, baseTop);
            ctx.layout.setText(ctx.fontRegular, lbl + "  ");
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY, r.name,
                    tx + ctx.layout.width, baseTop);
            if (r.hasInfo()) {
                ButtonChrome.icon(ctx, r.infoBtn, infoIcon, false, false);
            }
        }
        ctx.batch.end();
    }

    // -- Item view (picture + encyclopedia-style, level-aware detail) --------

    private void layoutItem() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(480f, vw - 28f);
        itemIcon = com.bjsp123.rl2.world.render.ItemSprites.regionFor(lookedItem);
        Mob player = TurnSystem.findPlayer(level);
        String name    = ItemNames.displayName(lookedItem, null);
        String flavor  = com.bjsp123.rl2.ui.ItemLore.describeFlavor(lookedItem);
        String details = com.bjsp123.rl2.ui.ItemLore.describeDetails(lookedItem, player);

        float bodyW = winW - PAD * 2f;
        float nameW = Math.max(40f, bodyW - ITEM_ICON - 10f - INFO);
        itemNameBlock   = TextDraw.block(ctx.fontHeader,  name,    nameW, 2, ctx.headerLineH());
        itemFlavorBlock = TextDraw.block(ctx.fontRegular, flavor,  bodyW, 4, ctx.lineH());
        // Briefer than the full encyclopedia entry: cap the stat block.
        itemDetailBlock = TextDraw.block(ctx.fontRegular, details, bodyW - DETAIL_INDENT, 12, ctx.lineH());

        float useNowH = itemDetailBlock.height() > 0 ? ctx.lineH() + 2f : 0f;
        float headerRowH = Math.max(ITEM_ICON, itemNameBlock.height());
        float contentH = headerRowH;
        if (itemFlavorBlock.height() > 0) contentH += TITLE_GAP + itemFlavorBlock.height();
        if (itemDetailBlock.height() > 0) contentH += SECTION_GAP + useNowH + itemDetailBlock.height();
        float winH = Math.min(vh - 92f, HEADER_H + PAD * 2f + contentH);
        window.set((vw - winW) * 0.5f, vh - 78f - winH, winW, winH);

        float topRow = window.top() - HEADER_H - PAD;
        itemIconY  = topRow - ITEM_ICON;
        itemNameTop = topRow - (headerRowH - itemNameBlock.height()) * 0.5f;
        float y = topRow - headerRowH;
        itemFlavorY = Float.NaN;
        if (itemFlavorBlock.height() > 0) { y -= TITLE_GAP; itemFlavorY = y; y -= itemFlavorBlock.height(); }
        itemSepY = Float.NaN; itemDetailY = Float.NaN; itemUseNowY = Float.NaN;
        if (itemDetailBlock.height() > 0) {
            itemSepY = y - SECTION_GAP * 0.5f;
            y -= SECTION_GAP;
            itemUseNowY = y;          // "If you used this item right now:" header
            y -= useNowH;
            itemDetailY = y;
        }
        itemInfoBtn.set(window.right() - PAD - INFO, topRow - INFO + 5f, INFO, INFO);
    }

    private void renderItemShapes() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);
        // Picture cell.
        Edges.drawTriLine(s, window.x + PAD, itemIconY, ITEM_ICON, ITEM_ICON, 1f);
        if (encyclopedia != null && lookedItem != null) {
            ButtonChrome.shape(ctx, itemInfoBtn, pressedInfo == INFO_ITEM, false, false, UIVars.BTN_BG);
        }
        if (!Float.isNaN(itemSepY)) {
            Window.drawInfoSeparator(ctx, window.x + PAD, itemSepY, window.w - PAD * 2f);
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderItemText() {
        ctx.batch.begin();
        float ix = window.x + PAD;
        if (itemIcon != null) ctx.batch.draw(itemIcon, ix, itemIconY, ITEM_ICON, ITEM_ICON);
        TextDraw.wrapped(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                itemNameBlock, ix + ITEM_ICON + 10f, itemNameTop);
        if (!Float.isNaN(itemFlavorY)) {
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                    itemFlavorBlock, ix, itemFlavorY);
        }
        if (!Float.isNaN(itemUseNowY)) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.ACCENT,
                    TextCatalog.get("ui.look.itemUseNow"), ix, itemUseNowY);
        }
        if (!Float.isNaN(itemDetailY)) {
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    itemDetailBlock, ix + DETAIL_INDENT, itemDetailY);
        }
        if (encyclopedia != null && lookedItem != null) {
            ButtonChrome.icon(ctx, itemInfoBtn,
                    IconSprites.regionFor(IconSprites.Icon.INFO), pressedInfo == INFO_ITEM, false);
        }
        ctx.batch.end();
    }

    private static final class TRow {
        final String label;
        final String name;
        final TextureRegion icon;   // nullable (clouds have no sprite)
        final Color swatch;         // nullable; set for clouds (drawn as a swatch)
        final Object encId;         // encyclopedia id; null when this row is "none"
        final Rect infoBtn = new Rect();
        float rowTop;

        TRow(String label, String name, TextureRegion icon, Color swatch, Object encId) {
            this.label = label; this.name = name;
            this.icon = icon; this.swatch = swatch; this.encId = encId;
        }

        static TRow none(String label) {
            return new TRow(label, TextCatalog.get("ui.look.none"), null, null, null);
        }

        boolean present() { return encId != null; }
        boolean hasInfo() { return encId != null; }
    }

    // -- Mob view (name+level, picture, description, tip, combat readout,
    //    special abilities, scrollable carried list) --------------------------

    private void layoutMob() {
        float vw = ctx.worldW(), vh = ctx.worldH();
        float winW = Math.min(480f, vw - 28f);
        float bodyW = winW - PAD * 2f;
        Mob m = lookedMob;
        Mob player = TurnSystem.findPlayer(level);

        String name = mobName(m);
        String desc = m.mobType != null ? TextCatalog.mobDescription(m.mobType, "") : "";
        if ((desc == null || desc.isEmpty()) && m.description != null) desc = m.description;
        String tip = m.mobType != null ? TextCatalog.getOrDefault("mob." + m.mobType + ".tip", "") : "";
        String combat = buildMobCombat(m, player);
        String specials = com.bjsp123.rl2.ui.MobLore.describeSpecials(m);
        mobImage = com.bjsp123.rl2.world.render.MobSprites.regionFor(m);

        mobNameBlock     = TextDraw.block(ctx.fontHeader,  name,     bodyW - INFO - 8f, 2, ctx.headerLineH());
        mobDescBlock     = TextDraw.block(ctx.fontRegular, desc,     bodyW, 4, ctx.lineH());
        mobTipBlock      = TextDraw.block(ctx.fontRegular, tip,      bodyW, 3, ctx.lineH());
        mobCombatBlock   = TextDraw.block(ctx.fontRegular, combat,   bodyW - DETAIL_INDENT, 4, ctx.lineH());
        mobSpecialsBlock = TextDraw.block(ctx.fontRegular, specials, bodyW - DETAIL_INDENT, 8, ctx.lineH());

        float imgH = MOB_IMG_H;
        mobImgW = (mobImage != null && mobImage.getRegionHeight() > 0)
                ? MOB_IMG_H * mobImage.getRegionWidth() / (float) mobImage.getRegionHeight()
                : MOB_IMG_H;

        float rowH = ctx.lineH();
        boolean hasCarried = !carried.isEmpty();
        float carriedFrameH = 0f, carriedBlockH = 0f;
        if (hasCarried) {
            int visRows = Math.min(carried.size(), MAX_CARRIED_ROWS);
            carriedFrameH = visRows * rowH;
            carriedBlockH = SECTION_GAP + rowH + carriedFrameH;
        }

        float gap = 6f;
        float contentH = mobNameBlock.height()
                + gap + imgH
                + (mobDescBlock.height()  > 0 ? gap + mobDescBlock.height()  : 0)
                + (mobTipBlock.height()   > 0 ? gap + mobTipBlock.height()   : 0)
                + SECTION_GAP + rowH
                + gap + mobCombatBlock.height()
                + (mobSpecialsBlock.height() > 0 ? SECTION_GAP + mobSpecialsBlock.height() : 0)
                + carriedBlockH;
        float winH = Math.min(vh - 40f, HEADER_H + PAD * 2f + contentH);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        float ix = window.x + PAD;
        float y = window.top() - HEADER_H - PAD;
        mobInfoBtn.set(window.right() - PAD - INFO, y - INFO + 5f, INFO, INFO);
        y -= mobNameBlock.height();
        y -= gap;
        mobImgX = window.cx() - mobImgW * 0.5f;
        mobImgY = y - imgH;
        y -= imgH;
        mobDescY = Float.NaN;
        if (mobDescBlock.height() > 0) { y -= gap; mobDescY = y; y -= mobDescBlock.height(); }
        mobTipY = Float.NaN;
        if (mobTipBlock.height() > 0) { y -= gap; mobTipY = y; y -= mobTipBlock.height(); }
        y -= SECTION_GAP;
        mobSepY = y + SECTION_GAP * 0.5f;
        mobFightHdrY = y;
        y -= rowH;
        y -= gap;
        mobCombatY = y;
        y -= mobCombatBlock.height();
        mobSpecialsY = Float.NaN;
        if (mobSpecialsBlock.height() > 0) { y -= SECTION_GAP; mobSpecialsY = y; y -= mobSpecialsBlock.height(); }
        mobCarryLabelY = Float.NaN;
        if (hasCarried) {
            y -= SECTION_GAP;
            mobCarryLabelY = y;
            float frameX = ix + DETAIL_INDENT;
            float frameW = window.right() - PAD - frameX;
            float frameTop = y - rowH - 2f;
            carriedBand.set(frameX, frameTop - carriedFrameH, frameW, carriedFrameH);
            carriedBand.update(carried.size() * rowH);
        }
    }

    private String buildMobCombat(Mob m, Mob player) {
        StatBlock s = m.effectiveStats();
        StringBuilder sb = new StringBuilder();
        if (player != null && player != m) {
            sb.append(TextCatalog.format("ui.look.fight.toHit",
                    TextCatalog.vars("pct", percent(MobStats.hitChance(player, m))))).append('\n');
            sb.append(TextCatalog.format("ui.look.fight.toBeHit",
                    TextCatalog.vars("pct", percent(MobStats.hitChance(m, player))))).append('\n');
        }
        if (!s.damage.isZero()) {
            sb.append(TextCatalog.format("ui.look.fight.damage",
                    TextCatalog.vars("damage", range(s.damage)))).append('\n');
        }
        if (!s.rangedDamage.isZero()) {
            sb.append(TextCatalog.format("ui.look.fight.ranged",
                    TextCatalog.vars("damage", range(s.rangedDamage), "range", s.rangedDistance))).append('\n');
        }
        return sb.toString().trim();
    }

    private void renderMobShapes() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);
        if (encyclopedia != null) {
            ButtonChrome.shape(ctx, mobInfoBtn, pressedInfo == INFO_MOB, false, false, UIVars.BTN_BG);
        }
        Window.drawInfoSeparator(ctx, window.x + PAD, mobSepY, window.w - PAD * 2f);
        if (!carried.isEmpty() && carriedBand.height() > 0f) {
            Edges.drawTriLine(s, carriedBand.rect.x, carriedBand.rect.y,
                    carriedBand.rect.w, carriedBand.rect.h, 1f);
            carriedBand.drawScrollbar(s, carried.size() * ctx.lineH());
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderMobText() {
        ctx.batch.begin();
        float ix = window.x + PAD;
        TextDraw.wrapped(ctx, ctx.fontHeader, UIVars.TEXT_BODY,
                mobNameBlock, ix, window.top() - HEADER_H - PAD);
        if (encyclopedia != null) {
            ButtonChrome.icon(ctx, mobInfoBtn,
                    IconSprites.regionFor(IconSprites.Icon.INFO), pressedInfo == INFO_MOB, false);
        }
        if (mobImage != null) ctx.batch.draw(mobImage, mobImgX, mobImgY, mobImgW, MOB_IMG_H);
        if (!Float.isNaN(mobDescY)) {
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY, mobDescBlock, ix, mobDescY);
        }
        if (!Float.isNaN(mobTipY)) {
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM, mobTipBlock, ix, mobTipY);
        }
        TextDraw.left(ctx, ctx.fontRegular, UIVars.ACCENT,
                TextCatalog.get("ui.look.fightHeader"), ix, mobFightHdrY);
        TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY, mobCombatBlock, ix + DETAIL_INDENT, mobCombatY);
        if (!Float.isNaN(mobSpecialsY)) {
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY, mobSpecialsBlock, ix + DETAIL_INDENT, mobSpecialsY);
        }
        if (!carried.isEmpty() && !Float.isNaN(mobCarryLabelY)) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    TextCatalog.get("ui.look.carrying"), ix, mobCarryLabelY);
            float rowH = ctx.lineH();
            carriedBand.clip(ctx, () -> {
                for (int i = 0; i < carried.size(); i++) {
                    float rowTop = carriedBand.rowTop(i, rowH);
                    if (!carriedBand.rowVisible(rowTop, rowH)) continue;
                    TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            ItemNames.displayName(carried.get(i), null),
                            carriedBand.rect.x + 2f, rowTop - rowH + 4f,
                            carriedBand.rect.w - 6f);
                }
            });
        }
        ctx.batch.end();
    }

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(500f, vw - 28f);
        float textW = Math.max(20f, winW - PAD * 2f - INFO - 8f);
        float detailW = Math.max(20f, winW - PAD * 2f - DETAIL_INDENT);
        float sectionsH = measureSections(textW, detailW);
        float rowH = ctx.lineH();
        // Reserve a fixed-height scrollable frame for carried items (label +
        // up to MAX_CARRIED_ROWS visible rows; the rest scroll).
        boolean hasCarried = !carried.isEmpty();
        float carriedFrameH = 0f, carriedBlockH = 0f;
        if (hasCarried) {
            int visRows = Math.min(carried.size(), MAX_CARRIED_ROWS);
            carriedFrameH = visRows * rowH;
            carriedBlockH = SECTION_GAP + rowH /*"Carried:" label*/ + carriedFrameH;
        }
        float contentH = sectionsH + carriedBlockH + PAD * 2f;
        float winH = Math.min(vh - 92f, HEADER_H + contentH);
        window.set((vw - winW) * 0.5f, vh - 78f - winH, winW, winH);

        terrainInfoBtn.set(0, 0, 0, 0);
        itemInfoBtn.set(0, 0, 0, 0);
        mobInfoBtn.set(0, 0, 0, 0);

        float y = window.top() - HEADER_H - PAD;
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            if (sec.infoKind != 0) {
                infoRect(sec.infoKind).set(window.right() - PAD - INFO,
                        y - INFO + 5f, INFO, INFO);
            }
            y -= sec.height();
            if (i < sections.size() - 1) y -= SECTION_GAP;
        }

        carriedLabelY = Float.NaN;
        if (hasCarried) {
            y -= SECTION_GAP;
            carriedLabelY = y;                       // "Carried:" label baseline
            float frameX = window.x + PAD + DETAIL_INDENT;
            float frameW = window.right() - PAD - frameX;
            float frameTop = y - rowH - 2f;          // just below the label
            carriedBand.set(frameX, frameTop - carriedFrameH, frameW, carriedFrameH);
            carriedBand.update(carried.size() * rowH);
        }
    }

    private float measureSections(float titleW, float detailW) {
        float lineH = ctx.lineH();
        float total = 0f;
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            sec.titleBlock = TextDraw.block(ctx.fontRegular, sec.title,
                    titleW, 2, lineH);
            sec.detailBlocks.clear();
            for (String detail : sec.details) {
                sec.detailBlocks.add(TextDraw.block(ctx.fontRegular, detail,
                        detailW, 3, lineH));
            }
            total += sec.height();
            if (i < sections.size() - 1) total += SECTION_GAP;
        }
        return total;
    }

    private Rect infoRect(int kind) {
        return switch (kind) {
            case INFO_TERRAIN -> terrainInfoBtn;
            case INFO_ITEM -> itemInfoBtn;
            case INFO_MOB -> mobInfoBtn;
            default -> terrainInfoBtn;
        };
    }

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // V2Look is *info-only* (read the tile, never interact with it), so it
        // uses the lighter info-window fill. The user-facing signal is the
        // contrast against interactive windows like inventory or settings.
        Window.drawInfoShape(ctx, window.x, window.y, window.w, window.h);

        if (encyclopedia != null && lookedTile != null) {
            ButtonChrome.shape(ctx, terrainInfoBtn, pressedInfo == INFO_TERRAIN,
                    false, false, UIVars.BTN_BG);
        }
        if (encyclopedia != null && lookedItem != null) {
            ButtonChrome.shape(ctx, itemInfoBtn, pressedInfo == INFO_ITEM,
                    false, false, UIVars.BTN_BG);
        }
        if (encyclopedia != null && lookedMob != null) {
            ButtonChrome.shape(ctx, mobInfoBtn, pressedInfo == INFO_MOB,
                    false, false, UIVars.BTN_BG);
        }

        // HP bars - walk sections in the same order as the text pass so the
        // bar lands between the title and the first detail line.
        float y = window.top() - HEADER_H - PAD;
        float textLeft  = window.x + PAD;
        float textRight = window.right() - PAD - INFO - 4f;
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            if (sec.titleBlock != null) y -= sec.titleBlock.height();
            if (sec.hasBelowTitle()) y -= TITLE_GAP;
            if (sec.hasHpBar) {
                float barX = textLeft + DETAIL_INDENT;
                float barW = Math.max(20f, textRight - barX);
                float barY = y - HP_BAR_H;
                Edges.drawTriLine(s, barX, barY, barW, HP_BAR_H, 1f);
                s.setColor(UIVars.HUD_BG);
                s.rect(barX + 3, barY + 3, barW - 6, HP_BAR_H - 6);
                float frac = Math.max(0f, Math.min(1f, sec.hpFrac));
                if (frac > 0f) {
                    s.setColor(UIVars.TEXT_WARN);
                    s.rect(barX + 3, barY + 3, (barW - 6) * frac, HP_BAR_H - 6);
                }
                y -= HP_BAR_H + HP_BAR_GAP;
            }
            for (TextDraw.TextBlock detail : sec.detailBlocks) y -= detail.height() + DETAIL_GAP;
            y -= sec.iconRowH();
            // Consume any reserved padding (info-button minimum height) so the next
            // section lines up with its info button.
            y -= Math.max(0f, sec.height() - sec.contentHeight());
            if (i < sections.size() - 1) {
                // Inked ruled line halfway through the gap so it visually
                // separates this section from the next without crowding
                // either one. Page-style.
                float ruleY = y - SECTION_GAP * 0.5f;
                Window.drawInfoSeparator(ctx, textLeft, ruleY,
                        window.right() - PAD - textLeft);
                y -= SECTION_GAP;
            }
        }

        // Carried-items frame: 1-px border + the shared scrollbar.
        if (!carried.isEmpty() && carriedBand.height() > 0f) {
            Edges.drawTriLine(s, carriedBand.rect.x, carriedBand.rect.y,
                    carriedBand.rect.w, carriedBand.rect.h, 1f);
            carriedBand.drawScrollbar(s, carried.size() * ctx.lineH());
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderTextPass() {
        buffHitRects.clear();
        buffHitTypes.clear();
        ctx.batch.begin();

        float y = window.top() - HEADER_H - PAD;
        float textLeft = window.x + PAD;
        float textRight = window.right() - PAD - INFO - 4f;
        TextureRegion infoIcon = IconSprites.regionFor(IconSprites.Icon.INFO);
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    sec.titleBlock, textLeft, y);
            if (sec.infoKind != 0) {
                ButtonChrome.icon(ctx, infoRect(sec.infoKind), infoIcon,
                        pressedInfo == sec.infoKind, false);
            }
            y -= sec.titleBlock.height();
            if (sec.hasBelowTitle()) y -= TITLE_GAP;
            float detailX = textLeft + DETAIL_INDENT;
            if (sec.hasHpBar) {
                float barX = detailX;
                float barW = Math.max(20f, textRight - barX);
                float barY = y - HP_BAR_H;
                TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        sec.hpText,
                        barX + barW * 0.5f,
                        barY + (HP_BAR_H + ctx.lineH() * 0.6f) * 0.5f);
                y -= HP_BAR_H + HP_BAR_GAP;
            }
            for (TextDraw.TextBlock detail : sec.detailBlocks) {
                TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        detail, detailX, y);
                y -= detail.height() + DETAIL_GAP;
            }
            if (!sec.icons.isEmpty()) {
                float iconY = y - BUFF_ICON_SZ;
                float iconX = detailX;
                for (int bi = 0; bi < sec.icons.size(); bi++) {
                    ctx.batch.draw(sec.icons.get(bi), iconX, iconY, BUFF_ICON_SZ, BUFF_ICON_SZ);
                    if (bi < sec.iconTypes.size()) {
                        Rect hr = new Rect();
                        hr.set(iconX, iconY, BUFF_ICON_SZ, BUFF_ICON_SZ);
                        buffHitRects.add(hr);
                        buffHitTypes.add(sec.iconTypes.get(bi));
                    }
                    iconX += BUFF_ICON_SZ + BUFF_ICON_GAP;
                }
                y -= sec.iconRowH();
            }
            // Consume reserved padding so successive sections stay aligned with their
            // info buttons (matches the shape pass + layoutRects).
            y -= Math.max(0f, sec.height() - sec.contentHeight());
            if (i < sections.size() - 1) y -= SECTION_GAP;
        }

        // Carried-items frame: label above, then the scrollable item list
        // clipped to the band (one item per row).
        if (!carried.isEmpty() && !Float.isNaN(carriedLabelY)) {
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    TextCatalog.get("ui.look.carried"), window.x + PAD, carriedLabelY);
            float rowH = ctx.lineH();
            carriedBand.clip(ctx, () -> {
                for (int i = 0; i < carried.size(); i++) {
                    float rowTop = carriedBand.rowTop(i, rowH);
                    if (!carriedBand.rowVisible(rowTop, rowH)) continue;
                    TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                            ItemNames.displayName(carried.get(i), null),
                            carriedBand.rect.x + 2f, rowTop - rowH + 4f,
                            carriedBand.rect.w - 6f);
                }
            });
        }
        ctx.batch.end();
    }

    /** Theme + tile name, e.g. "Crystal floor". Surface / vegetation / cloud are
     *  listed as their own lines by {@link #buildTerrainRows}. */
    private String terrainName() {
        return TextCatalog.getOrDefault("level.theme." + level.theme.name(),
                pretty(level.theme.name())) + " "
                + TextCatalog.getOrDefault("tile." + lookedTile.name(), pretty(lookedTile.name()));
    }


    private String mobName(Mob m) {
        String name = m.name != null ? m.name : TextCatalog.get("ui.look.mobFallback");
        return m.characterLevel > 1
                ? TextCatalog.format("ui.look.mobLevelPlain",
                        TextCatalog.vars("name", name, "level", m.characterLevel))
                : name;
    }

    private static String describeSurface(Level.Surface s) {
        return TextCatalog.get("ui.look.surface." + s.name().toLowerCase());
    }

    private static String describeVegetation(Level.Vegetation v) {
        return TextCatalog.get("ui.look.terrain." + v.name().toLowerCase());
    }

    private static int percent(double p) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, p)) * 100.0);
    }

    private static String range(MinMax mm) {
        if (mm == null || (mm.min() == 0 && mm.max() == 0)) return "0";
        return mm.min() == mm.max() ? Integer.toString(mm.min())
                : mm.min() + "-" + mm.max();
    }

    private static String pretty(String raw) {
        return raw == null ? "" : raw.toLowerCase().replace('_', ' ');
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                pressedInfo = 0;
                pressedBuff = -1;
                pressedTerrainRow = -1;
                carriedBand.touchDown(vx, vy);   // prime carried-list drag
                // Terrain mode: a tap on a present row's info button.
                if (terrainMode && encyclopedia != null) {
                    for (int i = 0; i < terrainRows.size(); i++) {
                        TRow r = terrainRows.get(i);
                        if (r.hasInfo() && r.infoBtn.contains(vx, vy)) {
                            pressedTerrainRow = i;
                            return true;
                        }
                    }
                }
                if (encyclopedia != null && lookedTile != null && terrainInfoBtn.contains(vx, vy)) {
                    pressedInfo = INFO_TERRAIN;
                    return true;
                }
                if (encyclopedia != null && lookedItem != null && itemInfoBtn.contains(vx, vy)) {
                    pressedInfo = INFO_ITEM;
                    return true;
                }
                if (encyclopedia != null && lookedMob != null && mobInfoBtn.contains(vx, vy)) {
                    pressedInfo = INFO_MOB;
                    return true;
                }
                if (encyclopedia != null) {
                    for (int bi = 0; bi < buffHitRects.size(); bi++) {
                        if (buffHitRects.get(bi).contains(vx, vy)) {
                            pressedBuff = bi;
                            return true;
                        }
                    }
                }
                if (!window.contains(vx, vy)) {
                    if (lookMode != null) lookMode.toggle();
                    return true;
                }
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);
                int info = pressedInfo;
                int buff = pressedBuff;
                int trow = pressedTerrainRow;
                pressedInfo = 0;
                pressedBuff = -1;
                pressedTerrainRow = -1;
                if (terrainMode && trow >= 0 && trow < terrainRows.size()) {
                    TRow r = terrainRows.get(trow);
                    if (r.hasInfo() && r.infoBtn.contains(vx, vy)) {
                        Object id = r.encId;
                        if (lookMode != null) lookMode.toggle();
                        encyclopedia.openTo(id);
                        return true;
                    }
                }
                if (info == INFO_TERRAIN && terrainInfoBtn.contains(vx, vy) && lookedTile != null) {
                    Tile t = lookedTile;
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(t);
                    return true;
                }
                if (info == INFO_ITEM && itemInfoBtn.contains(vx, vy) && lookedItem != null) {
                    String type = lookedItem.type;
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(type);
                    return true;
                }
                if (info == INFO_MOB && mobInfoBtn.contains(vx, vy) && lookedMob != null) {
                    String type = lookedMob.mobType;
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(type);
                    return true;
                }
                if (buff >= 0 && buff < buffHitRects.size()
                        && buffHitRects.get(buff).contains(vx, vy)
                        && buff < buffHitTypes.size()) {
                    Buff.BuffType bt = buffHitTypes.get(buff);
                    if (lookMode != null) lookMode.toggle();
                    encyclopedia.openTo(bt);
                    return true;
                }
                return true;
            }

            @Override
            public boolean touchDragged(int sx, int sy, int pointer) {
                if (!isOpen()) return false;
                return carriedBand.touchDragged(ctx.unprojectY(sx, sy));
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (!isOpen()) return false;
                carriedBand.scrolled(amountY);
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

    private static final class Section {
        final String title;
        final int infoKind;
        final List<String> details = new ArrayList<>();
        final List<TextDraw.TextBlock> detailBlocks = new ArrayList<>();
        final List<TextureRegion> icons = new ArrayList<>();
        final List<Buff.BuffType> iconTypes = new ArrayList<>();
        TextDraw.TextBlock titleBlock;
        /** Optional HP bar painted between the title and the details. Set
         *  for mob sections so the player can read the looked mob's HP at
         *  a glance instead of parsing a stat line. */
        boolean hasHpBar;
        float   hpFrac;
        String  hpText;

        private Section(String title, int infoKind) {
            this.title = title == null ? "" : title;
            this.infoKind = infoKind;
        }

        float iconRowH() {
            return icons.isEmpty() ? 0f : BUFF_ICON_SZ + BUFF_ICON_GAP;
        }

        float hpBarRowH() {
            return hasHpBar ? HP_BAR_H + HP_BAR_GAP : 0f;
        }

        /** True when anything is drawn below the title (so a gap is warranted). */
        boolean hasBelowTitle() {
            return hasHpBar || !detailBlocks.isEmpty() || !icons.isEmpty();
        }

        /** Height of the section's actual content (title + hp bar + details + icons),
         *  including the title gap and per-detail spacing. */
        float contentHeight() {
            float h = titleBlock == null ? 0f : titleBlock.height();
            if (hasBelowTitle()) h += TITLE_GAP;
            h += hpBarRowH();
            for (TextDraw.TextBlock block : detailBlocks) h += block.height() + DETAIL_GAP;
            h += iconRowH();
            return h;
        }

        /** Laid-out height. Sections with a 40px info button reserve at least that much
         *  vertical space so a short section (e.g. terrain) can't let its info button
         *  overlap the next section's button. */
        float height() {
            float h = contentHeight();
            if (infoKind != 0) h = Math.max(h, INFO);
            return h;
        }
    }
}
