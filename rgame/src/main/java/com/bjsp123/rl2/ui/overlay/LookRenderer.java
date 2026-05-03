package com.bjsp123.rl2.ui.overlay;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Level.Vegetation;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.ui.skin.UiTheme;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.MobSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * Centred "look" modal — three vertically-stacked sub-panels inside one outer
 * frame, with two centred sub-popups stacked on top:
 *
 * <ol>
 *   <li><b>Look popup</b> (the modal proper): tile / items / mob sub-panels.</li>
 *   <li><b>Mob info popup</b>: opens when the mob sub-panel's "i" button is
 *       tapped. Centred, lists the mob's history + inventory.</li>
 *   <li><b>Buff info popup</b>: opens when a buff icon in the mob sub-panel is
 *       tapped. Centred, shows the buff's icon, name, level, and remaining
 *       duration.</li>
 * </ol>
 *
 * <p>Each level has its own full-stage dim overlay drawn beneath it. The look
 * dim is non-touchable so taps in the world reach {@code LookMode} (which
 * either dismisses look or registers the first pick). The two sub-popup dims
 * are touchable and close their respective popup on click.
 */
public class LookRenderer extends Group {

    private final Skin skin;

    // ── Look popup ──────────────────────────────────────────────────────────
    private final Image lookDim;
    private final Container<Table> framed;
    private final Table outerPanel;     // panel-chromed wrapper for the 3 sub-panels

    private final Table tilePanel;
    private final Image tileSprite;
    private final Image tileSurfaceSprite;
    private final Image tileVegetationSprite;
    private final Label tileHeaderL;
    private final Label tileCoordsL;
    private final Label tileWalkL;
    private final Label tileFeatureL;

    private final Table itemsPanel;
    private final Image itemSprite;
    private final Label itemsHeaderL;
    private final Table itemsList;

    private final Table mobPanel;
    private final Image mobSprite;
    private final Label mobNameL;
    private final Label mobHpL;
    private final Label mobStatsL;
    private final Label mobStateL;
    private final Label mobAttitudeL;
    private final Table mobBuffsRow;

    // ── Mob info popup ──────────────────────────────────────────────────────
    private final Image mobInfoDim;
    private final Container<Table> mobInfoFramed;
    private final Table mobInfoPanel;
    private final Label mobInfoHistoryL;
    private final Label mobInfoInventoryL;

    // ── Buff info popup ─────────────────────────────────────────────────────
    private final Image buffInfoDim;
    private final Container<Table> buffInfoFramed;
    private final Table buffInfoPanel;
    private final Image buffInfoIcon;
    private final Label buffInfoNameL;
    private final Label buffInfoLevelL;
    private final Label buffInfoDurationL;

    /** 1×1 white texture for the dim overlays. Created once, disposed in {@link #dispose}. */
    private final Texture dimTex;

    private Mob   player;
    private Level level;
    private LookMode lookMode;
    private boolean showMobDetail;
    private Buff    showBuffDetail;
    /** Object key for the currently-displayed tile / mob, captured during populate*Panel
     *  so the "?" info buttons can pass the right id to the encyclopaedia at click
     *  time. Items are keyed off their per-row button's closure instead. */
    private Tile   currentTile;
    private Mob    currentMob;
    /** Encyclopaedia popup the "?" info buttons open. Set externally via
     *  {@link #setEncyclopedia}; null disables the buttons (they still render but
     *  click handlers no-op). */
    private com.bjsp123.rl2.ui.popup.EncyclopediaRenderer encyclopedia;

    public LookRenderer(Skin skin) {
        this.skin = skin;
        this.dimTex = makeWhitePixel();

        // ── Look popup: outer panel + 3 sub-panels ────────────────────────
        outerPanel = new Table();
        outerPanel.setBackground(skin.getDrawable("panel"));
        outerPanel.pad(UiTheme.OUTER_PAD);
        outerPanel.defaults().fillX();

        tilePanel = makeSubPanel();
        tileSprite = new Image();
        tileSprite.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        // Surface and vegetation overlay layers — drawn ON TOP of the terrain in a
        // Stack, so a flooded grass cell shows the floor sprite, the water tile,
        // and the grass clump composited together. populateTilePanel toggles each
        // layer's drawable on/off based on level.surface[][] / level.vegetation[][].
        tileSurfaceSprite = new Image();
        tileSurfaceSprite.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        tileVegetationSprite = new Image();
        tileVegetationSprite.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        tileHeaderL  = new Label("", skin, "title");
        tileCoordsL  = new Label("", skin, "dim");
        tileWalkL    = new Label("", skin, "default");
        tileFeatureL = new Label("", skin, "default");
        // "?" info button below the tile portrait — opens the encyclopaedia turned
        // to the current Tile. currentTile is populated on each populateTilePanel.
        TextButton tileEncBtn = new TextButton("?", skin);
        tileEncBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                if (encyclopedia != null && currentTile != null) {
                    encyclopedia.openTo(
                            com.bjsp123.rl2.ui.popup.EncyclopediaRenderer.Category.TERRAIN,
                            currentTile);
                }
            }
        });
        // Composite portrait — terrain at the base, surface overlay above it,
        // vegetation overlay on top. Stack draws children in add-order, so this
        // gives the right back-to-front ordering for a single-tile preview.
        com.badlogic.gdx.scenes.scene2d.ui.Stack tileSpriteStack =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        tileSpriteStack.add(tileSprite);
        tileSpriteStack.add(tileSurfaceSprite);
        tileSpriteStack.add(tileVegetationSprite);
        Table tileLeft = new Table();
        tileLeft.add(tileSpriteStack).size(32, 32).row();
        tileLeft.add(tileEncBtn).size(32, 22).padTop(4);
        // Tile portrait on the LEFT, terrain description column on the RIGHT.
        Table tileText = new Table();
        tileText.defaults().left();
        tileText.add(tileHeaderL).left().padBottom(2).row();
        tileText.add(tileCoordsL).left().row();
        tileText.add(tileWalkL).left().row();
        tileText.add(tileFeatureL).left();
        tilePanel.add(tileLeft).top().padRight(8);
        tilePanel.add(tileText).top().expandX().fillX();

        itemsPanel = makeSubPanel();
        itemsHeaderL = new Label("Items", skin, "title");
        itemSprite = new Image();
        itemSprite.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        itemsList = new Table();
        itemsList.defaults().left();
        // Same shape as the tile / mob sub-panels: image on the LEFT, header +
        // descriptions on the RIGHT. Sprite shows the first item on the tile.
        Table itemsRight = new Table();
        itemsRight.defaults().left();
        itemsRight.add(itemsHeaderL).left().padBottom(2).row();
        itemsRight.add(itemsList).left().fillX();
        itemsPanel.add(itemSprite).size(32, 32).top().padRight(8);
        itemsPanel.add(itemsRight).top().expandX().fillX();

        mobPanel = makeSubPanel();
        mobSprite = new Image();
        mobSprite.setScaling(com.badlogic.gdx.utils.Scaling.fit);

        mobNameL     = new Label("", skin, "title");
        mobHpL       = new Label("", skin, "default");
        mobStatsL    = new Label("", skin, "dim");
        mobStateL    = new Label("", skin, "default");
        mobAttitudeL = new Label("", skin, "default");
        mobBuffsRow  = new Table();
        mobBuffsRow.defaults().pad(1);

        // "?" info button below the mob portrait — opens the encyclopaedia turned
        // to the current Mob's species. Sits beside the existing "info" button
        // (which shows this individual mob's history + inventory, separate concern).
        TextButton mobEncBtn = new TextButton("?", skin);
        mobEncBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                if (encyclopedia != null && currentMob != null && currentMob.mobType != null) {
                    encyclopedia.openTo(
                            com.bjsp123.rl2.ui.popup.EncyclopediaRenderer.Category.CREATURES,
                            currentMob.mobType);
                }
            }
        });

        Table mobLeft = new Table();
        mobLeft.add(mobSprite).size(48, 48).row();
        mobLeft.add(mobEncBtn).width(48).height(22).padTop(4);

        Table mobRight = new Table();
        mobRight.defaults().left();
        mobRight.add(mobNameL).left().padBottom(2).row();
        mobRight.add(mobHpL).left().row();
        mobRight.add(mobStatsL).left().row();
        mobRight.add(mobStateL).left().row();
        mobRight.add(mobAttitudeL).left().padBottom(4).row();
        mobRight.add(mobBuffsRow).left();

        mobPanel.add(mobLeft).top().padRight(8);
        mobPanel.add(mobRight).top().expandX().fillX();

        framed = new Container<>(outerPanel);
        framed.fill();

        // Dim overlay drawn behind the look popup.
        lookDim = makeDim();
        // NON-touchable: clicks on the dim area pass through to LookMode in the
        // input multiplexer, which handles state-B dismissal ("tap world to close").
        lookDim.setTouchable(Touchable.disabled);

        // ── Mob info popup: history + inventory ──────────────────────────
        mobInfoPanel = new Table();
        mobInfoPanel.setBackground(skin.getDrawable("panel"));
        mobInfoPanel.pad(UiTheme.OUTER_PAD).defaults().left();
        Label mobInfoTitle = new Label("Mob info", skin, "title");
        Label histHdr = new Label("History", skin, "title");
        mobInfoHistoryL = new Label("", skin, "dim");
        mobInfoHistoryL.setWrap(true);
        Label invHdr = new Label("Inventory", skin, "title");
        mobInfoInventoryL = new Label("", skin, "dim");
        mobInfoInventoryL.setWrap(true);
        mobInfoPanel.add(mobInfoTitle).left().padBottom(8).row();
        mobInfoPanel.add(histHdr).left().padBottom(2).row();
        mobInfoPanel.add(mobInfoHistoryL).left().expandX().fillX().padBottom(8).row();
        mobInfoPanel.add(invHdr).left().padBottom(2).row();
        mobInfoPanel.add(mobInfoInventoryL).left().expandX().fillX();

        mobInfoFramed = new Container<>(mobInfoPanel);
        mobInfoFramed.fill();

        mobInfoDim = makeDim();
        mobInfoDim.setTouchable(Touchable.enabled);
        mobInfoDim.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                showMobDetail = false;
            }
        });

        // ── Buff info popup ──────────────────────────────────────────────
        buffInfoPanel = new Table();
        buffInfoPanel.setBackground(skin.getDrawable("panel"));
        buffInfoPanel.pad(UiTheme.OUTER_PAD).defaults().left();
        buffInfoIcon = new Image();
        buffInfoIcon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        buffInfoNameL     = new Label("", skin, "title");
        buffInfoLevelL    = new Label("", skin, "default");
        buffInfoDurationL = new Label("", skin, "dim");

        Table buffHeaderRow = new Table();
        buffHeaderRow.add(buffInfoIcon).size(32, 32).padRight(8);
        buffHeaderRow.add(buffInfoNameL).left();
        buffInfoPanel.add(buffHeaderRow).left().padBottom(8).row();
        buffInfoPanel.add(buffInfoLevelL).left().padBottom(2).row();
        buffInfoPanel.add(buffInfoDurationL).left();

        buffInfoFramed = new Container<>(buffInfoPanel);
        buffInfoFramed.fill();

        buffInfoDim = makeDim();
        buffInfoDim.setTouchable(Touchable.enabled);
        buffInfoDim.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                showBuffDetail = null;
            }
        });

        // ── Z-order: dim overlays sit BEHIND their popup framed ──────────
        addActor(lookDim);
        addActor(framed);
        addActor(mobInfoDim);
        addActor(mobInfoFramed);
        addActor(buffInfoDim);
        addActor(buffInfoFramed);

        // childrenOnly so the look dim's transparent-to-clicks behaviour works —
        // see lookDim.setTouchable(disabled) above. The Group itself doesn't
        // catch clicks, only its children do.
        setTouchable(Touchable.childrenOnly);
        setVisible(false);
    }

    private Table makeSubPanel() {
        Table t = new Table();
        t.setBackground(skin.getDrawable("simple-panel"));
        t.pad(UiTheme.SUB_PANEL_PAD);
        t.defaults().padTop(1);
        return t;
    }

    private Image makeDim() {
        Image im = new Image(new TextureRegionDrawable(
                new com.badlogic.gdx.graphics.g2d.TextureRegion(dimTex)));
        im.setColor(0, 0, 0, UiTheme.DIM_ALPHA);
        im.setFillParent(true);
        return im;
    }

    private static Texture makeWhitePixel() {
        Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        p.setColor(1, 1, 1, 1);
        p.fill();
        Texture t = new Texture(p);
        p.dispose();
        return t;
    }

    public void setPlayer(Mob p)        { this.player = p; }
    public void setLevel(Level l)       { this.level  = l; }
    public void setLookMode(LookMode m) { this.lookMode = m; }
    public void setEncyclopedia(com.bjsp123.rl2.ui.popup.EncyclopediaRenderer e) {
        this.encyclopedia = e;
    }

    public boolean isOpen() { return lookMode != null && lookMode.isActive(); }

    /** Refresh from {@link LookMode} state — called each frame. */
    public void update() {
        boolean lookOn = lookMode != null && lookMode.isPanelVisible();
        if (!lookOn) {
            // Reset all popup state when look closes so it doesn't pop back next time.
            showMobDetail = false;
            showBuffDetail = null;
            setVisible(false);
            return;
        }
        setVisible(true);

        Point c = lookMode.cursor();
        if (c == null || level == null) return;

        // Rebuild the look popup's content. Conditional sub-panels (items / mob)
        // are added to outerPanel only when relevant, so the popup is compact when
        // the cursor is on bare terrain and grows when there's a mob with buffs.
        outerPanel.clear();
        outerPanel.pad(UiTheme.OUTER_PAD);
        outerPanel.defaults().fillX().padBottom(UiTheme.SECTION_GAP);

        populateTilePanel(c);
        outerPanel.add(tilePanel).fillX().row();

        List<Item> items = itemsAt(c);
        if (!items.isEmpty()) {
            populateItemsPanel(items);
            outerPanel.add(itemsPanel).fillX().row();
        }

        Mob t = lookMode.mobAtCursor();
        if (t != null) {
            populateMobPanel(t);
            outerPanel.add(mobPanel).fillX().row();
        } else {
            // No mob → drop any related popup state.
            showMobDetail = false;
            showBuffDetail = null;
        }

        // Mob info popup content (if open).
        boolean mobOn = showMobDetail && t != null;
        if (mobOn) populateMobInfoPanel(t);

        // Buff info popup content (if open).
        boolean buffOn = showBuffDetail != null && t != null;
        if (buffOn) populateBuffInfoPanel(showBuffDetail);

        // Visibility per layer.
        lookDim.setVisible(true);   // always while look is on
        framed.setVisible(true);
        mobInfoDim.setVisible(mobOn);
        mobInfoFramed.setVisible(mobOn);
        buffInfoDim.setVisible(buffOn);
        buffInfoFramed.setVisible(buffOn);
    }

    private List<Item> itemsAt(Point c) {
        List<Item> result = new ArrayList<>();
        if (level == null || level.items == null) return result;
        int cx = c.tileX(), cy = c.tileY();
        for (Item it : level.items) {
            if (it.location == null) continue;
            if (it.location.tileX() == cx && it.location.tileY() == cy) result.add(it);
        }
        return result;
    }

    // ── Sub-panel populators ────────────────────────────────────────────────

    private void populateTilePanel(Point c) {
        int x = c.tileX(), y = c.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) {
            currentTile = null;
            tileSprite.setDrawable(null);
            tileSurfaceSprite.setDrawable(null);
            tileVegetationSprite.setDrawable(null);
            tileHeaderL.setText("(out of bounds)");
            tileCoordsL.setText("");
            tileWalkL.setText("");
            tileFeatureL.setText("");
            return;
        }
        if (!level.explored[x][y]) {
            currentTile = null;
            tileSprite.setDrawable(null);
            tileSurfaceSprite.setDrawable(null);
            tileVegetationSprite.setDrawable(null);
            tileHeaderL.setText("Unexplored");
            tileCoordsL.setText(com.bjsp123.rl2.util.Fmt.of("Tile (%d, %d)", x, y));
            tileWalkL.setText("");
            tileFeatureL.setText("");
            return;
        }
        Tile t = level.tiles[x][y];
        currentTile = t;
        boolean visible = level.visible[x][y];
        com.badlogic.gdx.graphics.g2d.TextureRegion tileIcon =
                com.bjsp123.rl2.world.render.TileSprites.regionFor(t, level.theme);
        if (tileIcon != null) {
            tileSprite.setDrawable(new TextureRegionDrawable(tileIcon));
        } else {
            tileSprite.setDrawable(null);
        }
        tileHeaderL.setText(prettyTileName(t)
                + (visible ? "  (in sight)" : "  (remembered)"));
        tileCoordsL.setText(com.bjsp123.rl2.util.Fmt.of("Tile (%d, %d)", x, y));
        tileWalkL.setText(t.isFloorLike() ? "Walkable" : "Blocked");

        StringBuilder feat = new StringBuilder();
        Vegetation v = level.vegetation != null ? level.vegetation[x][y] : null;
        if (v != null) feat.append(prettyVegetation(v));
        Surface s = level.surface != null ? level.surface[x][y] : null;
        if (s != null) {
            if (feat.length() > 0) feat.append(", ");
            feat.append(prettySurface(s));
        }
        tileFeatureL.setText(feat.toString());

        // Surface + vegetation overlays — flat single-tile samples from
        // SurfaceSprites; null surfaces / vegetation clear the overlay so the bare
        // terrain shows through. Returns null for tiles SurfaceSprites doesn't
        // have a static sample of (e.g. ICE, FIRE) — also handled as a clear.
        com.badlogic.gdx.graphics.g2d.TextureRegion surfReg = s == null
                ? null
                : com.bjsp123.rl2.world.render.SurfaceSprites.regionFor(s);
        tileSurfaceSprite.setDrawable(surfReg == null
                ? null : new TextureRegionDrawable(surfReg));
        com.badlogic.gdx.graphics.g2d.TextureRegion vegReg = v == null
                ? null
                : com.bjsp123.rl2.world.render.SurfaceSprites.regionFor(v);
        tileVegetationSprite.setDrawable(vegReg == null
                ? null : new TextureRegionDrawable(vegReg));
    }

    private void populateItemsPanel(List<Item> items) {
        // Show the first item on the tile in the panel's left-hand sprite slot.
        // Multiple items just stack in the right-hand list; a single representative
        // sprite is enough to communicate "there is something on this tile".
        com.badlogic.gdx.graphics.g2d.TextureRegion firstIcon = items.isEmpty()
                ? null
                : com.bjsp123.rl2.world.render.ItemSprites.regionFor(items.get(0));
        itemSprite.setDrawable(firstIcon == null
                ? null : new TextureRegionDrawable(firstIcon));
        itemsList.clear();
        itemsList.defaults().left();
        for (final Item it : items) {
            // Title-case just the name prefix; the suffix (level, damage, armor,
            // light) Item.describe() appends keeps its original casing.
            String line = it.describe();
            if (it.name != null && line.startsWith(it.name)) {
                line = com.bjsp123.rl2.ui.Names.titleCase(it.name)
                        + line.substring(it.name.length());
            }
            if (it.count > 1) line = line + " ×" + it.count;
            // Each item row: description label, then a "?" button that opens the
            // encyclopaedia turned to that ItemType. The button captures `it` via
            // closure so the click handler doesn't need a separate selection field.
            Table row = new Table();
            row.add(new Label(line, skin, "default")).left().expandX().fillX();
            TextButton itemEncBtn = new TextButton("?", skin);
            itemEncBtn.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    if (encyclopedia != null && it.type != null) {
                        encyclopedia.openTo(
                                com.bjsp123.rl2.ui.popup.EncyclopediaRenderer.Category.ITEMS,
                                it.type);
                    }
                }
            });
            row.add(itemEncBtn).size(22, 22).padLeft(8);
            itemsList.add(row).left().fillX().row();
        }
    }

    private void populateMobPanel(Mob t) {
        currentMob = t;
        com.badlogic.gdx.graphics.g2d.TextureRegion region = MobSprites.regionFor(t);
        if (region != null) {
            mobSprite.setDrawable(new TextureRegionDrawable(region));
        } else {
            mobSprite.setDrawable(null);
        }

        com.bjsp123.rl2.model.StatBlock ts = t.effectiveStats();
        String name = com.bjsp123.rl2.ui.Names.titleCase(
                (t.name == null || t.name.isEmpty()) ? "?" : t.name);
        mobNameL.setText(com.bjsp123.rl2.util.Fmt.of("%s   Lvl %d",
                name, Math.max(1, t.characterLevel)));
        mobHpL.setText(com.bjsp123.rl2.util.Fmt.of("HP %d/%d",
                (int) Math.round(t.hp), (int) Math.round(ts.maxHp)));
        mobStatsL.setText(com.bjsp123.rl2.util.Fmt.of(
                "Acc %d  Eva %d  Dmg %d-%d  Arm %d-%d",
                ts.accuracy, ts.evasion,
                ts.damage.min(), ts.damage.max(),
                ts.armor.min(),  ts.armor.max()));
        mobStateL.setText("Action: " + prettyState(t));
        mobAttitudeL.setText("Attitude: " + prettyAttitudeToPlayer(t));

        // Buff icon row — each icon is a clickable Image that pops up the
        // dedicated buff info modal.
        mobBuffsRow.clear();
        if (t.buffs != null) {
            for (final Buff b : t.buffs) {
                com.badlogic.gdx.graphics.g2d.TextureRegion icon = BuffIcons.regionFor(b.type);
                if (icon == null) continue;
                Image img = new Image(icon);
                img.setScaling(com.badlogic.gdx.utils.Scaling.fit);
                img.setTouchable(Touchable.enabled);
                img.addListener(new ClickListener() {
                    @Override public void clicked(InputEvent e, float x, float y) {
                        showBuffDetail = b;
                    }
                });
                mobBuffsRow.add(img).size(10, 10);
            }
        }
    }

    private void populateMobInfoPanel(Mob t) {
        StringBuilder hist = new StringBuilder();
        if (t.history == null || t.history.isEmpty()) {
            hist.append("(no history yet)");
        } else {
            int start = Math.max(0, t.history.size() - HISTORY_LINES);
            for (int i = t.history.size() - 1; i >= start; i--) {
                if (hist.length() > 0) hist.append('\n');
                hist.append(t.history.get(i).describe());
            }
        }
        mobInfoHistoryL.setText(hist.toString());

        StringBuilder inv = new StringBuilder();
        if (t.inventory != null) {
            for (Item.ItemSlot slot : Item.ItemSlot.values()) {
                Item eq = t.inventory.equipped(slot);
                if (eq != null) {
                    if (inv.length() > 0) inv.append('\n');
                    inv.append(slot.name().toLowerCase()).append(": ").append(eq.describe());
                }
            }
            if (t.inventory.bag != null) {
                for (Item bagIt : t.inventory.bag) {
                    if (bagIt == null) continue;
                    if (inv.length() > 0) inv.append('\n');
                    inv.append(bagIt.describe());
                    if (bagIt.count > 1) inv.append(" ×").append(bagIt.count);
                }
            }
        }
        if (inv.length() == 0) inv.append("(nothing)");
        mobInfoInventoryL.setText(inv.toString());
    }

    private void populateBuffInfoPanel(Buff b) {
        com.badlogic.gdx.graphics.g2d.TextureRegion icon = BuffIcons.regionFor(b.type);
        if (icon != null) {
            buffInfoIcon.setDrawable(new TextureRegionDrawable(icon));
        } else {
            buffInfoIcon.setDrawable(null);
        }
        buffInfoNameL.setText(com.bjsp123.rl2.logic.BuffSystem.displayName(b.type));
        buffInfoLevelL.setText("Level " + b.level);
        // Duration — count of standard turns remaining, or "Permanent" for unbounded.
        if (b.durationTurns >= 999_000) {
            buffInfoDurationL.setText("Permanent");
        } else if (b.durationTurns <= 0) {
            buffInfoDurationL.setText("(expired)");
        } else {
            buffInfoDurationL.setText("Duration: " + b.durationTurns + " turn"
                    + (b.durationTurns == 1 ? "" : "s"));
        }
    }

    // ── Prettification helpers ──────────────────────────────────────────────

    private String prettyAttitudeToPlayer(Mob t) {
        if (player == null) return "—";
        MobSystem.Attitude a = MobSystem.getAttitudeToMob(t, player);
        return switch (a) {
            case ATTACK  -> "Hostile";
            case FLEE    -> "Fleeing";
            case ALLY    -> "Friendly";
            case NOTHING -> "Neutral";
        };
    }

    private static String prettyState(Mob t) {
        StringBuilder sb = new StringBuilder(prettyStateOfMind(t.stateOfMind));
        if (t.targetPosition != null) {
            sb.append(", heading to (")
              .append(t.targetPosition.tileX()).append(", ")
              .append(t.targetPosition.tileY()).append(")");
        }
        return sb.toString();
    }

    private static String prettyStateOfMind(com.bjsp123.rl2.model.Mob.StateOfMind s) {
        if (s == null) return "—";
        return switch (s) {
            case ASLEEP         -> "Asleep";
            case AWAKE          -> "Awake";
            case SEEKING_HIDING -> "Fleeing";
            case HIDING         -> "Hiding";
            case FOLLOWING      -> "Following";
        };
    }

    private static final int HISTORY_LINES = 5;

    private static String prettyTileName(Tile t) {
        return switch (t) {
            case FLOOR        -> "Floor";
            case WALL         -> "Wall";
            case DOOR         -> "Door (closed)";
            case DOOR_OPEN    -> "Door (open)";
            case CHASM        -> "Chasm";
            case STATUE_SMALL_L, STATUE_SMALL_R -> "Small statue";
            case STATUE_LARGE_L, STATUE_LARGE_R -> "Large statue";
            case STAIRS_UP    -> "Stairs up";
            case STAIRS_DOWN  -> "Stairs down";
            default           -> t.name();
        };
    }

    private static String prettyVegetation(Vegetation v) {
        return switch (v) {
            case GRASS     -> "Grass";
            case MUSHROOMS -> "Mushrooms";
            case FIRE      -> "Fire";
            case TREES     -> "Trees";
        };
    }

    private static String prettySurface(Surface s) {
        return switch (s) {
            case WATER -> "Water";
            case BLOOD -> "Blood";
            case OIL   -> "Oil";
            case ICE   -> "Ice";
        };
    }

    /** Centre every popup level on the stage. The look popup grows to fit its
     *  content (capped at {@link UiTheme#LOOK_PANEL_MAX_H}); the mob-info and
     *  buff-info popups use fixed sizes from {@link UiTheme}. */
    public void layoutForStage(Stage stage) {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();
        setBounds(0, 0, w, h);

        // Look popup — width fixed, height grows with content.
        outerPanel.pack();
        float lw = Math.min(UiTheme.LOOK_PANEL_W, w - 32);
        float lh = Math.min(outerPanel.getPrefHeight(),
                Math.min(UiTheme.LOOK_PANEL_MAX_H, h - 32));
        framed.setSize(lw, lh);
        framed.setPosition((w - lw) / 2f, (h - lh) / 2f);

        // Mob info popup — fixed width, height grows with content (capped).
        mobInfoPanel.pack();
        float mw = Math.min(UiTheme.LOOK_MOB_INFO_W, w - 32);
        float mh = Math.min(mobInfoPanel.getPrefHeight(),
                Math.min(UiTheme.LOOK_MOB_INFO_MAX_H, h - 32));
        mobInfoFramed.setSize(mw, mh);
        mobInfoFramed.setPosition((w - mw) / 2f, (h - mh) / 2f);

        // Buff info popup — fixed size.
        float bw = Math.min(UiTheme.LOOK_BUFF_INFO_W, w - 32);
        float bh = Math.min(UiTheme.LOOK_BUFF_INFO_H, h - 32);
        buffInfoFramed.setSize(bw, bh);
        buffInfoFramed.setPosition((w - bw) / 2f, (h - bh) / 2f);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        update();
        if (getStage() != null) layoutForStage(getStage());
    }

    /** Source-compat shims with the old API. */
    public void create()  { }
    public void resize(int w, int h) { }
    public void render()  { /* drawn by stage */ }
    public void dispose() {
        if (dimTex != null) dimTex.dispose();
    }
}
