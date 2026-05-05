package com.bjsp123.rl2.ui.popup;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.model.Buff.BuffType;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.ui.skin.PanelSize;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.GemSprites;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.MobSprites;
import com.bjsp123.rl2.world.render.TileSprites;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-game encyclopaedia: a Large modal popup with one tab per category (Items,
 * Creatures, Buffs, Gems, Perks, Terrain). The active tab shows a scrollable
 * icon-and-name list on the left and a bordered detail panel on the right.
 *
 * <p>Built once at {@link #EncyclopediaRenderer construction} — entries are static
 * (factory-produced reference instances + enum metadata), so the popup never needs
 * to refresh from game state.
 *
 * <p>{@link #openTo} lets external callers drop the user straight onto a specific
 * entry — e.g. clicking a buff icon could open the popup pre-selected to that buff.
 */
public class EncyclopediaRenderer extends Group {

    /** Top-level tabs. The order of declaration matches the visual tab strip. */
    public enum Category {
        ITEMS("Items"), CREATURES("Creatures"), BUFFS("Buffs"),
        GEMS("Gems"), PERKS("Perks"), TERRAIN("Terrain");
        public final String label;
        Category(String label) { this.label = label; }
    }

    /** One row in a tab's list. {@link #id} is the natural key (an enum value etc.)
     *  used by {@link #openTo} to find a specific entry. */
    private static final class Entry {
        final Object id;
        final TextureRegion icon;
        final String name;
        final String detail;
        Entry(Object id, TextureRegion icon, String name, String detail) {
            this.id = id; this.icon = icon; this.name = name; this.detail = detail;
        }
    }

    private final Skin skin;
    private final Container<Table> framed;

    private final Map<Category, TextButton> tabButtons   = new EnumMap<>(Category.class);
    private final Map<Category, Table>      tabContent   = new EnumMap<>(Category.class);
    private final Map<Category, Label>      detailLabels = new EnumMap<>(Category.class);
    private final Map<Category, Image>      detailIcons  = new EnumMap<>(Category.class);
    private final Map<Category, Label>      detailNames  = new EnumMap<>(Category.class);
    private final Map<Category, Table>      detailHeaders = new EnumMap<>(Category.class);
    /** Raw {@link com.badlogic.gdx.scenes.scene2d.ui.Cell} so we can mutate the
     *  detail icon's cell size on selection — sources vary 7×7 (buffs at 2× = 14×14)
     *  through 64×64 (mobs), so a fixed cell would either crop or float. */
    @SuppressWarnings("rawtypes")
    private final Map<Category, com.badlogic.gdx.scenes.scene2d.ui.Cell> detailIconCells
            = new EnumMap<>(Category.class);
    private final Map<Category, List<Entry>> entries     = new EnumMap<>(Category.class);
    private final Map<Category, Entry>       selected    = new EnumMap<>(Category.class);

    private boolean open;
    private Category activeTab = Category.ITEMS;

    public EncyclopediaRenderer(Skin skin) {
        this.skin = skin;

        // Build entries for every category up front — the data is static so there's
        // no benefit to deferred construction, and the list cells need real entries
        // to bind their click listeners against.
        entries.put(Category.ITEMS,     buildItemEntries());
        entries.put(Category.CREATURES, buildCreatureEntries());
        entries.put(Category.BUFFS,     buildBuffEntries());
        entries.put(Category.GEMS,      buildGemEntries());
        entries.put(Category.PERKS,     buildPerkEntries());
        entries.put(Category.TERRAIN,   buildTerrainEntries());

        // Outer panel chrome.
        Table frame = new Table();
        frame.pad(8);
        frame.defaults().pad(2);

        // Tabs row — one TextButton per category, manila-folder style aligned to
        // the left so the active tab visually merges with the body panel below.
        Table tabs = new Table();
        tabs.left();
        tabs.defaults().pad(0);
        for (Category cat : Category.values()) {
            TextButton btn = new TextButton(cat.label, skin, "tab");
            btn.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    setActiveTab(cat);
                }
            });
            tabButtons.put(cat, btn);
            tabs.add(btn).minWidth(72).height(26);
        }

        // Body — one Table per tab, stacked so visibility-toggling switches tabs
        // without reflowing the parent layout.
        com.badlogic.gdx.scenes.scene2d.ui.Stack body =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        for (Category cat : Category.values()) {
            Table content = buildTabContent(cat);
            tabContent.put(cat, content);
            body.add(content);
        }

        // Tab section — body panel chrome below the tab row, tabs overlap only the
        // panel's 2-px top border (manila-folder pattern, same as the character
        // stats and inventory popups).
        final int TAB_H = 26;
        com.badlogic.gdx.scenes.scene2d.ui.Stack tabSection =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();

        Image tabSectionBg = new Image(skin.getDrawable("panel"));
        tabSectionBg.setScaling(com.badlogic.gdx.utils.Scaling.stretch);
        Table bgLayer = new Table();
        bgLayer.top();
        bgLayer.add().height(TAB_H - 2).row();
        bgLayer.add(tabSectionBg).expand().fill();
        tabSection.add(bgLayer);

        Table contentLayer = new Table();
        contentLayer.top();
        contentLayer.add(tabs).left().fillX().height(TAB_H).row();
        contentLayer.add(body).expand().fill().pad(8);
        tabSection.add(contentLayer);

        frame.add(tabSection).expand().fill().row();

        TextButton closeBtn = new TextButton("Close", skin);
        closeBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { close(); }
        });
        frame.add(closeBtn).padTop(6);

        framed = new Container<>(frame);
        framed.setBackground(com.bjsp123.rl2.ui.skin.UiTextures
                .windowBackgroundOr(skin.getDrawable("panel")));
        framed.fill();
        framed.pack();
        addActor(framed);

        setVisible(false);
        setTouchable(Touchable.enabled);
        addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent e, float x, float y, int p, int b) {
                if (!open) return false;
                if (!withinFrame(x, y)) { close(); return true; }
                return false;
            }
            @Override public boolean keyDown(InputEvent e, int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE) { close(); return true; }
                return false;
            }
        });

        setActiveTab(activeTab);
    }

    // ── Public entry points ─────────────────────────────────────────────────

    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else open(); }

    public void open() {
        open = true;
        setVisible(true);
        if (getStage() != null) getStage().setKeyboardFocus(this);
    }

    public void close() { open = false; setVisible(false); }

    /** Open the encyclopaedia pre-selected on a particular entry. {@code id} is the
     *  natural key for that category — item-type string for items, mob-type string
     *  for creatures, {@link BuffType} for buffs, {@link GemSpecies} for gems,
     *  {@link Perk} for perks, {@link Tile} for terrain. Falls back to opening the
     *  category's first entry when {@code id} is null or unmatched. */
    public void openTo(Category cat, Object id) {
        setActiveTab(cat);
        List<Entry> es = entries.get(cat);
        Entry target = null;
        if (id != null) {
            for (Entry e : es) {
                if (id.equals(e.id)) { target = e; break; }
            }
        }
        if (target == null && !es.isEmpty()) target = es.get(0);
        if (target != null) selectEntry(cat, target);
        open();
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    /** Centre the popup against the stage at LARGE size. */
    public void layoutForStage(Stage stage) {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();
        setBounds(0, 0, w, h);
        float panelW = PanelSize.widthFor (PanelSize.Kind.LARGE, w);
        float panelH = PanelSize.heightFor(PanelSize.Kind.LARGE, h);
        framed.setSize(panelW, panelH);
        framed.setPosition((w - panelW) / 2f, (h - panelH) / 2f);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (getStage() != null) layoutForStage(getStage());
    }

    private boolean withinFrame(float x, float y) {
        return x >= framed.getX() && x <= framed.getX() + framed.getWidth()
            && y >= framed.getY() && y <= framed.getY() + framed.getHeight();
    }

    private void setActiveTab(Category cat) {
        activeTab = cat;
        for (Category c : Category.values()) {
            tabButtons.get(c).setChecked(c == cat);
            tabContent.get(c).setVisible(c == cat);
        }
        // Refresh detail in case the selection changed via openTo().
        refreshDetail(cat);
    }

    private void selectEntry(Category cat, Entry e) {
        selected.put(cat, e);
        refreshDetail(cat);
    }

    /** Push the currently-selected entry's name / icon / detail into the detail
     *  panel widgets for {@code cat}. Empty selection clears them. */
    private void refreshDetail(Category cat) {
        Entry sel = selected.get(cat);
        Label nl  = detailNames.get(cat);
        Label dl  = detailLabels.get(cat);
        Image di  = detailIcons.get(cat);
        @SuppressWarnings("rawtypes")
        com.badlogic.gdx.scenes.scene2d.ui.Cell cell = detailIconCells.get(cat);
        Table header = detailHeaders.get(cat);
        if (nl != null) nl.setText(sel == null ? "" : sel.name);
        if (dl != null) dl.setText(sel == null ? "" : sel.detail);
        if (di != null) {
            di.setDrawable(sel != null && sel.icon != null
                    ? new TextureRegionDrawable(sel.icon) : null);
        }
        if (cell != null) {
            int w = 0, h = 0;
            if (sel != null && sel.icon != null) {
                w = sel.icon.getRegionWidth();
                h = sel.icon.getRegionHeight();
                // Buffs ship as 7×7 in the source — too small to read at native size.
                if (cat == Category.BUFFS) { w *= 2; h *= 2; }
            }
            cell.size(w, h);
            if (header != null) header.invalidateHierarchy();
        }
    }

    // ── Per-tab content ─────────────────────────────────────────────────────

    private Table buildTabContent(Category cat) {
        // Left: scrollable list of (icon, name) rows.
        Table list = new Table();
        list.top().left();
        list.defaults().left();
        for (Entry entry : entries.get(cat)) {
            Table row = new Table();
            row.setTouchable(Touchable.enabled);
            row.left();
            Image icon = new Image();
            icon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            int dispW = 16, dispH = 16;
            if (entry.icon != null) {
                icon.setDrawable(new TextureRegionDrawable(entry.icon));
                int srcW = entry.icon.getRegionWidth();
                int srcH = entry.icon.getRegionHeight();
                // Halve the displayed size if the source sprite is taller than 32 px,
                // so a 64-tall mob doesn't dwarf a 16-tall item in the same list.
                if (srcH > 32) { dispW = srcW / 2; dispH = srcH / 2; }
                else           { dispW = srcW;     dispH = srcH; }
            }
            row.add(icon).size(dispW, dispH).padRight(8);
            Label nameLbl = new Label(entry.name, skin, "default");
            row.add(nameLbl).left().expandX().fillX();
            row.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    selectEntry(cat, entry);
                }
            });
            list.add(row).left().fillX().padBottom(2).row();
        }
        // No skin for ScrollPane — StoneUi's skin doesn't register a ScrollPaneStyle,
        // and the no-arg style draws no chrome which is fine for a text list.
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);

        // Right: bordered detail panel — header (icon + name) on top, wrapping
        // description label below. The icon cell is resized on selection so the
        // image renders at native source size (or 2× for buffs whose tiny 7×7
        // sprite would be invisible otherwise).
        Table detail = new Table();
        detail.setBackground(skin.getDrawable("simple-panel"));
        detail.pad(8);
        detail.top().left();

        Image detailIcon = new Image();
        detailIcon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        Label detailNameL = new Label("", skin, "title");
        detailNameL.setWrap(true);
        Table headerRow = new Table();
        @SuppressWarnings({"rawtypes", "unchecked"})
        com.badlogic.gdx.scenes.scene2d.ui.Cell iconCell = headerRow.add(detailIcon).top();
        headerRow.add(detailNameL).left().padLeft(8).top().expandX().fillX().growX();
        detail.add(headerRow).growX().padBottom(8).left().row();

        Label detailL = new Label("", skin, "default");
        detailL.setWrap(true);
        detail.add(detailL).growX().expand().top().left().fillX();

        detailIcons.put(cat, detailIcon);
        detailNames.put(cat, detailNameL);
        detailHeaders.put(cat, headerRow);
        detailIconCells.put(cat, iconCell);
        detailLabels.put(cat, detailL);

        // Outer split — list grabs a fixed-width column, detail expands.
        Table content = new Table();
        content.add(scroll) .width(220).top().expandY().fillY().padRight(8);
        content.add(detail).top().expand().fill();
        return content;
    }

    // ── Entry builders ──────────────────────────────────────────────────────

    private List<Entry> buildItemEntries() {
        List<Entry> out = new ArrayList<>();
        for (String type : com.bjsp123.rl2.logic.ItemRegistry.knownTypes()) {
            Item it = ItemFactory.build(type);
            String name = com.bjsp123.rl2.ui.Names.titleCase(
                    it.name == null ? "—" : it.name);
            out.add(new Entry(it.type, ItemSprites.regionFor(it), name, describeItem(it)));
        }
        return out;
    }

    private static String describeItem(Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.description != null && !it.description.isEmpty()) {
            sb.append(it.description).append('\n').append('\n');
        }
        if (it.slot != null)        sb.append("Equippable as ").append(it.slot.name().toLowerCase()).append('\n');
        if (it.material != null)    sb.append("Made of ").append(it.material.name().toLowerCase()).append('\n');
        if (it.damageMax > 0)       sb.append("Inflicts ").append(it.damageMin).append('-').append(it.damageMax).append(" damage\n");
        if (it.armorMax > 0)        sb.append("Protects from ").append(it.armorMin).append('-').append(it.armorMax).append(" damage\n");
        if (it.lightRadius > 0)     sb.append("Casts light over ").append((int) it.lightRadius).append(" tiles\n");
        if (it.foodValue > 0)       sb.append("Food value of ").append(it.foodValue).append('\n');
        if (it.healAmount > 0)      sb.append("Heals ").append(it.healAmount).append(" HP").append(" damage\n");
        if (it.thrownBehavior != null && it.thrownBehavior != Item.ThrownBehavior.NOTHING) {
            sb.append("When thrown: ").append(it.thrownBehavior.name().toLowerCase()).append('\n');
        }
        return sb.toString().trim();
    }

    private List<Entry> buildCreatureEntries() {
        List<Entry> out = new ArrayList<>();
        Point p = new Point(0, 0);
        for (String t : com.bjsp123.rl2.logic.MobRegistry.knownTypes()) {
            Mob m = MobFactory.spawn(t, p);
            if (m == null) continue;          // PLAYER has no spawn factory.
            String name = com.bjsp123.rl2.ui.Names.titleCase(
                    (m.name == null || m.name.isEmpty()) ? t : m.name);
            out.add(new Entry(t, MobSprites.regionFor(m), name, describeCreature(m)));
        }
        return out;
    }

    private static String describeCreature(Mob m) {
        com.bjsp123.rl2.model.StatBlock s = m.effectiveStats();
        StringBuilder sb = new StringBuilder();
        sb.append(m.description).append('\n').append('\n');
        sb.append("HP: ").append((int) Math.round(s.maxHp)).append('\n');
        sb.append("Attack: ").append(s.accuracy)
          .append("   Defense: ").append(s.evasion).append('\n');
        sb.append("Damage: ").append(s.damage.min()).append('-').append(s.damage.max()).append('\n');
        sb.append("Armor:  ").append(s.armor .min()).append('-').append(s.armor .max()).append('\n');
        if (s.lightRadius > 0) {
            sb.append("Light radius: ").append((int) s.lightRadius).append('\n');
        }
        if (s.terrifying)   sb.append("Terrifying — frightens nearby susceptible mobs.\n");
        if (s.terrifiable)  sb.append("Frightens easily.\n");
        if (s.teleportRate > 0)  sb.append("Can teleport.\n");
        if (s.eatSpawnChance > 0) sb.append("Can eat corpses to spawn new creatures.\n");
        if (s.mushroomEatSpawnChance > 0) sb.append("Can eat mushrooms to spawn new creatures.\n");
        if (s.rangedDistance > 0) sb.append("Can attack from afar.\n");
        if (s.flying) sb.append("Flies over chasms and traps.\n");
        if (s.turnSpawnChance > 0) sb.append("Can bring new creatures into the world.\n");
        if (s.fireImmune) sb.append("Immune to fire.\n");
        if (s.poisonsOnAttack) sb.append("Venomous attacks.\n");
        return sb.toString().trim();
    }

    private List<Entry> buildBuffEntries() {
        List<Entry> out = new ArrayList<>();
        for (BuffType t : BuffType.values()) {
            String name = com.bjsp123.rl2.ui.Names.titleCase(BuffSystem.displayName(t));
            out.add(new Entry(t, BuffIcons.regionFor(t), name, describeBuff(t)));
        }
        return out;
    }

    /** Short prose summary per buff. Mirrors the per-type javadocs on
     *  {@link BuffType} — kept in this presentation file rather than in rlib so the
     *  game logic stays free of UI strings. */
    private static String describeBuff(BuffType t) {
        return BuffSystem.description(t);
    }

    private List<Entry> buildGemEntries() {
        List<Entry> out = new ArrayList<>();
        for (GemSpecies sp : GemSpecies.values()) {
            // Size 5 ("fine") is mid-range — gives a representative-looking sprite
            // for the encyclopaedia row without picking the smallest or largest.
            TextureRegion icon = GemSprites.regionFor(sp, 5);
            out.add(new Entry(sp, icon,
                    com.bjsp123.rl2.ui.Names.titleCase(sp.pretty()),
                    describeGem(sp)));
        }
        return out;
    }

    private static String describeGem(GemSpecies sp) {
        return "Theme: " + sp.theme + "\nTier: " + sp.tier
                + "\n\nGems combine: two gems of the same species and matching size yield "
                + "one gem of the next size up (tiny → small → medium → large → fine → "
                + "impressive → mighty → sublime → exquisite).";
    }

    private List<Entry> buildPerkEntries() {
        List<Entry> out = new ArrayList<>();
        for (Perk p : Perk.values()) {
            out.add(new Entry(p, null,
                    com.bjsp123.rl2.ui.Names.titleCase(p.displayName()),
                    p.description()));
        }
        return out;
    }

    private List<Entry> buildTerrainEntries() {
        List<Entry> out = new ArrayList<>();
        for (Tile t : Tile.values()) {
            // Crystal theme — both themes share the same canonical-cell layout, so
            // the encyclopaedia portrait is theme-agnostic in practice.
            TextureRegion icon = TileSprites.regionFor(t, VisualTheme.CRYSTAL);
            out.add(new Entry(t, icon,
                    com.bjsp123.rl2.ui.Names.titleCase(prettyTileName(t)),
                    describeTile(t)));
        }
        // Surfaces (water / blood / oil / ice) live in the same Terrain tab — they
        // are tile-level features keyed by Level.surface. SurfaceSprites returns
        // null for surfaces that don't ship a static sample (currently ICE).
        for (com.bjsp123.rl2.model.Level.Surface s :
                com.bjsp123.rl2.model.Level.Surface.values()) {
            TextureRegion icon = com.bjsp123.rl2.world.render.SurfaceSprites.regionFor(s);
            out.add(new Entry(s, icon,
                    com.bjsp123.rl2.ui.Names.titleCase(prettySurface(s)),
                    describeSurface(s)));
        }
        // Vegetation likewise — grass / mushrooms / fire / trees. FIRE has no
        // static sprite (animated in the world view) so its icon will be null.
        for (com.bjsp123.rl2.model.Level.Vegetation v :
                com.bjsp123.rl2.model.Level.Vegetation.values()) {
            TextureRegion icon = com.bjsp123.rl2.world.render.SurfaceSprites.regionFor(v);
            out.add(new Entry(v, icon,
                    com.bjsp123.rl2.ui.Names.titleCase(prettyVegetation(v)),
                    describeVegetation(v)));
        }
        return out;
    }

    private static String prettySurface(com.bjsp123.rl2.model.Level.Surface s) {
        return switch (s) {
            case WATER -> "Water";
            case BLOOD -> "Blood";
            case OIL   -> "Oil";
            case ICE   -> "Ice";
        };
    }

    private static String prettyVegetation(com.bjsp123.rl2.model.Level.Vegetation v) {
        return switch (v) {
            case GRASS     -> "Grass";
            case MUSHROOMS -> "Mushrooms";
            case FIRE      -> "Fire";
            case TREES     -> "Trees";
        };
    }

    private static String describeSurface(com.bjsp123.rl2.model.Level.Surface s) {
        return switch (s) {
            case WATER -> "A pool of water. Slows non-flying mobs that step in.";
            case BLOOD -> "Spilt blood. Cosmetic — pools where mobs are wounded.";
            case OIL   -> "Slick oil. Catches fire from any heat source nearby and burns away.";
            case ICE   -> "Frozen surface. Reduces friction; mobs slide as they walk.";
        };
    }

    private static String describeVegetation(com.bjsp123.rl2.model.Level.Vegetation v) {
        return switch (v) {
            case GRASS     -> "Tall grass. Catches fire readily; spreads in damp soil.";
            case MUSHROOMS -> "A patch of mushrooms. Some species spawn from being eaten.";
            case FIRE      -> "Burning vegetation. Damages mobs that step on or beside it.";
            case TREES     -> "Trees. Block sight; flammable.";
        };
    }

    private static String prettyTileName(Tile t) {
        return switch (t) {
            case FLOOR        -> "Floor.";
            case FLOOR_WOOD   -> "Wood floor.";
            case WALL         -> "Wall.";
            case DOOR         -> "Door (closed).";
            case DOOR_OPEN    -> "Door (open).";
            case CHASM        -> "Chasm.";
            case LAMP         -> "Lamp .";
            case STAIRS_UP    -> "Stairs up";
            case STAIRS_DOWN  -> "Stairs down";
            case STATUE_SMALL_L, STATUE_SMALL_R -> "Small statue.";
            case STATUE_LARGE_L, STATUE_LARGE_R -> "Large statue.";
        };
    }

    private static String describeTile(Tile t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.isFloorLike()      ? "Walkable.\n" : "Blocks movement.\n");
        sb.append(t.blocksSight()      ? "Blocks sight.\n" : "See-through.\n");
        return switch (t) {
            case FLOOR       -> sb + "\nGood for standing on..";
            case FLOOR_WOOD  -> sb + "\nGood for standing on..";
            case WALL        -> sb + "\nSolid wall, blocks movement and sight.";
            case DOOR        -> sb + "\nA closed door — bumping it opens it.";
            case DOOR_OPEN   -> sb + "\nAn open door — lets sight and movement through.";
            case CHASM       -> sb + "\nA chasm. Non-flying mobs that step in fall to the next level.";
            case LAMP        -> sb + "\nA lit lamp — emits light.";
            case STAIRS_UP   -> sb + "\nStairs leading up to the previous level.";
            case STAIRS_DOWN -> sb + "\nStairs leading down to the next level.";
            case STATUE_SMALL_L, STATUE_SMALL_R ->
                    sb + "\nA small decorative statue. Blocks movement but you can see past it.";
            case STATUE_LARGE_L, STATUE_LARGE_R ->
                    sb + "\nA tall decorative statue. Blocks movement and sight.";
        };
    }
}
