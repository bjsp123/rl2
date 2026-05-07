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
    /** Overlay back button anchored at the bottom-right corner of {@link #framed}.
     *  Mirrors the {@code framedWithBack} treatment {@link com.bjsp123.rl2.screen.MenuScreen}
     *  applies to fullscreen menus — every modal window has the back glyph at the
     *  same screen-relative offset. */
    private final com.badlogic.gdx.scenes.scene2d.ui.Button backOverlay;
    private static final float BACK_OVERLAY_INSET = 12f;
    private static final float BACK_OVERLAY_SIZE  = 56f;

    private final Map<Category, com.badlogic.gdx.scenes.scene2d.ui.Button> tabButtons
            = new EnumMap<>(Category.class);
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
    /** Per-category info panels (header + scrollable description + back). One
     *  is visible at a time when the user has tapped a list entry; otherwise
     *  the list view (tabs + grid) is visible. Stacked into {@link #infoStack}. */
    private final Map<Category, Table>       infoPanels  = new EnumMap<>(Category.class);

    /** Toggleable view layer — either the list view (tabs + entries) is
     *  visible or the info panel for the picked entry. Per the UI rules a
     *  window is one or the other, never both side-by-side. */
    private com.badlogic.gdx.scenes.scene2d.ui.Stack listView;
    private com.badlogic.gdx.scenes.scene2d.ui.Stack infoStack;

    private boolean open;
    /** True when an entry has been picked from the list and the info panel is
     *  showing; false when the list view is showing. */
    private boolean showingInfo;
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

        // Tabs row — one icon Button per category, manila-folder style aligned
        // to the left so the active tab visually merges with the body panel
        // below. Each tab shows the category's icon (from {@link IconSprites})
        // instead of a text label so the strip stays compact across themes.
        Table tabs = new Table();
        tabs.left();
        tabs.defaults().pad(0);
        for (Category cat : Category.values()) {
            com.badlogic.gdx.scenes.scene2d.ui.Button btn =
                    new com.badlogic.gdx.scenes.scene2d.ui.Button(skin, "tab-icon");
            com.badlogic.gdx.graphics.g2d.TextureRegion iconRegion =
                    com.bjsp123.rl2.world.render.IconSprites.regionFor(iconForCategory(cat));
            if (iconRegion != null) {
                Image icon = new Image(iconRegion);
                icon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
                btn.add(icon).size(20, 20).pad(2);
            } else {
                btn.add(new Label(cat.label, skin, "default")).pad(2);
            }
            btn.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    setActiveTab(cat);
                }
            });
            tabButtons.put(cat, btn);
            tabs.add(btn).minWidth(40).height(26);
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

        // List view = the tab strip + entry grid; info view = the info panel
        // for the picked entry. The two are siblings inside an outer Stack so
        // we can flip visibility without rebuilding either side.
        listView = new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        listView.add(tabSection);

        infoStack = new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        for (Category cat : Category.values()) {
            Table panel = infoPanels.get(cat);
            if (panel != null) infoStack.add(panel);
        }
        infoStack.setVisible(false);

        com.badlogic.gdx.scenes.scene2d.ui.Stack viewToggle =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        viewToggle.add(listView);
        viewToggle.add(infoStack);

        frame.add(viewToggle).expand().fill().row();

        framed = new Container<>(frame);
        framed.setBackground(com.bjsp123.rl2.ui.skin.UiTextures
                .windowBackgroundOr(skin.getDrawable("panel")));
        framed.fill();
        framed.pack();
        addActor(framed);

        // Overlay back button — added as a sibling of {@code framed} inside this
        // Group and repositioned each frame in {@link #layoutForStage} so it sits
        // {@code BACK_OVERLAY_INSET} px from the bottom-right corner of the panel
        // regardless of inner-panel layout. Tap unwinds one level (info → list → close).
        backOverlay = new com.badlogic.gdx.scenes.scene2d.ui.Button(skin, "action-icon");
        com.badlogic.gdx.graphics.g2d.TextureRegion backRegion =
                com.bjsp123.rl2.world.render.IconSprites.regionFor(
                        com.bjsp123.rl2.world.render.IconSprites.Icon.BACK);
        if (backRegion != null) {
            Image backIcon = new Image(backRegion);
            backIcon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            backOverlay.add(backIcon).size(32, 32).pad(8);
        } else {
            backOverlay.add(new Label("Back", skin, "default")).pad(4);
        }
        backOverlay.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { back(); }
        });
        backOverlay.setSize(BACK_OVERLAY_SIZE, BACK_OVERLAY_SIZE);
        addActor(backOverlay);

        setVisible(false);
        setTouchable(Touchable.enabled);
        addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent e, float x, float y, int p, int b) {
                if (!open) return false;
                if (!withinFrame(x, y)) { back(); return true; }
                return false;
            }
            @Override public boolean keyDown(InputEvent e, int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE) { back(); return true; }
                return false;
            }
        });

        setActiveTab(activeTab);
    }

    /** Map each tab category to a glyph in the shared UI icon sheet. Categories
     *  without a dedicated icon (BUFFS, GEMS) fall through to the generic
     *  {@code OTHER} cell so every tab still shows artwork instead of text. */
    private static com.bjsp123.rl2.world.render.IconSprites.Icon iconForCategory(Category cat) {
        return switch (cat) {
            case ITEMS     -> com.bjsp123.rl2.world.render.IconSprites.Icon.ITEMS;
            case CREATURES -> com.bjsp123.rl2.world.render.IconSprites.Icon.MOBS;
            case PERKS     -> com.bjsp123.rl2.world.render.IconSprites.Icon.PERKS;
            case TERRAIN   -> com.bjsp123.rl2.world.render.IconSprites.Icon.TERRAIN;
            case BUFFS, GEMS -> com.bjsp123.rl2.world.render.IconSprites.Icon.OTHER;
        };
    }

    // ── Public entry points ─────────────────────────────────────────────────

    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else open(); }

    public void open() {
        open = true;
        setVisible(true);
        if (getStage() != null) getStage().setKeyboardFocus(this);
    }

    public void close() {
        open = false;
        setVisible(false);
        // Reset to the list view so the next open() lands on the picker
        // instead of whatever info window the user last viewed.
        showListView();
    }

    /** Unified one-level back: from the info view, return to the list; from
     *  the list, close the popup entirely. Bound to the Back button, the
     *  ESC key, and the tap-outside-the-frame handler so all three feel
     *  identical. */
    private void back() {
        if (showingInfo) showListView();
        else             close();
    }

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
        if (backOverlay != null) {
            backOverlay.setPosition(
                    framed.getX() + panelW - BACK_OVERLAY_SIZE - BACK_OVERLAY_INSET,
                    framed.getY() + BACK_OVERLAY_INSET);
        }
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
        showInfoView(cat);
    }

    /** Swap to the info view for {@code cat} — only the picked category's
     *  info panel is visible inside {@link #infoStack}. */
    private void showInfoView(Category cat) {
        showingInfo = true;
        if (listView != null)  listView.setVisible(false);
        if (infoStack != null) {
            infoStack.setVisible(true);
            for (Category c : Category.values()) {
                Table panel = infoPanels.get(c);
                if (panel != null) panel.setVisible(c == cat);
            }
        }
    }

    /** Return to the list view (tabs + grid). Called by the info panel's Back
     *  button and after {@link #close()} to reset state for the next open. */
    private void showListView() {
        showingInfo = false;
        if (infoStack != null) infoStack.setVisible(false);
        if (listView != null)  listView.setVisible(true);
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
                // Buffs ship as 7×7 and showcase categories (items / creatures)
                // double the source size so the header sprite reads at glance.
                if (cat == Category.BUFFS || isShowcaseCategory(cat)) {
                    w *= 2; h *= 2;
                }
            }
            // Showcase categories wrap the icon in a Container with a light
            // slot background; pad the cell to leave a chrome border around
            // the sprite so it matches the in-list presentation.
            if (isShowcaseCategory(cat) && w > 0) {
                int pad = 4;
                cell.size(w + pad * 2, h + pad * 2);
            } else {
                cell.size(w, h);
            }
            if (header != null) header.invalidateHierarchy();
        }
    }

    // ── Per-tab content ─────────────────────────────────────────────────────

    /** Items and creatures get the showcase treatment in the detail panel:
     *  2× source size, opaque light slot fill behind the sprite, and a 1-px
     *  black silhouette outline. Other categories (buffs / gems / perks /
     *  terrain) keep their plainer presentation. List rows are uniform across
     *  all categories — see {@link #buildTabContent}. */
    private static boolean isShowcaseCategory(Category cat) {
        return cat == Category.ITEMS || cat == Category.CREATURES;
    }

    /** Produce a single-line summary of a longer detail block for the list row.
     *  Takes the first non-empty line, trims to about 80 characters, and adds
     *  an ellipsis if the result is shorter than the source. Null- and empty-
     *  tolerant so categories without detail bodies don't print "null". */
    private static String shortSummary(String detail) {
        if (detail == null) return "";
        String firstLine = detail;
        int nl = detail.indexOf('\n');
        if (nl >= 0) firstLine = detail.substring(0, nl);
        firstLine = firstLine.trim();
        final int MAX = 80;
        if (firstLine.length() > MAX) firstLine = firstLine.substring(0, MAX - 1) + "…";
        return firstLine;
    }

    private Table buildTabContent(Category cat) {
        boolean showcase = isShowcaseCategory(cat);
        // Left: scrollable list of (icon, name) rows.
        Table list = new Table();
        list.top().left();
        list.defaults().left();
        for (Entry entry : entries.get(cat)) {
            Table row = new Table();
            row.setTouchable(Touchable.enabled);
            row.left().top();

            // Every category uses the same 32×32 framed icon cell so list rows
            // line up regardless of source sprite size — small buff icons (7×7
            // in source) get scaled up into the 32×32 box via Scaling.fit, big
            // mob sprites get scaled down. The OutlinedImage adds a 1-px black
            // silhouette so light-fill cells don't bleed the contour.
            int boxSize = 32;
            Image icon = new com.bjsp123.rl2.ui.skin.OutlinedImage();
            icon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            if (entry.icon != null) {
                icon.setDrawable(new TextureRegionDrawable(entry.icon));
            }
            Table bgCell = new Table();
            bgCell.setBackground(skin.getDrawable("item-slot"));
            bgCell.add(icon).size(boxSize, boxSize).pad(4);
            row.add(bgCell).top().padRight(8);

            // Right column: entry name on top, short description below — gives
            // the user enough context to pick without opening the info view.
            // prefWidth(0) on each wrapped Label cell forces libGDX to size
            // the cell from the row width rather than the label's unwrapped
            // text length, otherwise long descriptions blow the row's width
            // out and the icon ends up squeezed to the left.
            Table textCol = new Table();
            textCol.left().top();
            Label nameLbl = new Label(entry.name, skin, "default");
            nameLbl.setWrap(true);
            textCol.add(nameLbl).left().growX().prefWidth(0).row();
            String summary = shortSummary(entry.detail);
            if (summary != null && !summary.isEmpty()) {
                Label descLbl = new Label(summary, skin, "dim");
                descLbl.setWrap(true);
                textCol.add(descLbl).left().growX().prefWidth(0);
            }
            row.add(textCol).left().top().expandX().fillX().growX();

            row.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    selectEntry(cat, entry);
                }
            });
            // .growX() (= expandX + fillX) so the cell takes the full width
            // of the list and the row's text column stretches across the rest
            // of the panel instead of shrinking to its preferred width.
            list.add(row).left().growX().padBottom(2).row();
        }
        // No skin for ScrollPane — StoneUi's skin doesn't register a ScrollPaneStyle,
        // and the no-arg style draws no chrome which is fine for a text list.
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);

        // Right: bordered detail panel — header (icon + name) on top, wrapping
        // description label below. The icon cell is resized on selection so the
        // image renders at native source size (or 2× for buffs whose tiny 7×7
        // sprite would be invisible otherwise). Showcase categories (items,
        // creatures) wrap the icon in a light slot cell with an outline so the
        // header sprite matches the in-list presentation.
        Table detail = new Table();
        detail.setBackground(skin.getDrawable("simple-panel"));
        detail.pad(8);
        detail.top().left();

        Image detailIcon = showcase
                ? new com.bjsp123.rl2.ui.skin.OutlinedImage()
                : new Image();
        detailIcon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        Label detailNameL = new Label("", skin, "title");
        detailNameL.setWrap(true);
        Table headerRow = new Table();
        @SuppressWarnings({"rawtypes", "unchecked"})
        com.badlogic.gdx.scenes.scene2d.ui.Cell iconCell;
        if (showcase) {
            // Container fills its child to its own size minus padding so the icon
            // scales (via Scaling.fit) to the inner area while the slot drawable
            // paints the bezel around it. refreshDetail resizes the outer Cell on
            // selection, which propagates through to the icon.
            com.badlogic.gdx.scenes.scene2d.ui.Container<Image> bgCell =
                    new com.badlogic.gdx.scenes.scene2d.ui.Container<>(detailIcon);
            bgCell.setBackground(skin.getDrawable("item-slot"));
            bgCell.fill().pad(4);
            iconCell = headerRow.add(bgCell).top();
        } else {
            iconCell = headerRow.add(detailIcon).top();
        }
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

        // The detail panel is no longer rendered next to the list — per the
        // UI rules a window is a list OR an info view, never both. The widgets
        // built above (detailIcon, detailNameL, detailL, headerRow) are kept
        // and registered into the maps so {@link #buildInfoPanel} can wrap
        // them in a dedicated info-window panel that replaces the list view
        // when an entry is tapped.
        Table content = new Table();
        content.add(new com.bjsp123.rl2.ui.skin.ScrollHinted(scroll, skin))
                .top().expand().fill();
        infoPanels.put(cat, buildInfoPanel(cat, detail));
        return content;
    }

    /** Wrap the per-category {@code detail} table built by {@link #buildTabContent}
     *  in an info-window panel: scrollable body + a Back button at the bottom that
     *  returns to the list view. The detail Table is the same object whose
     *  {@code detailNameL} / {@code detailL} / icon cell are mutated by
     *  {@link #refreshDetail}, so refreshing on selection still works without
     *  any extra plumbing. */
    private Table buildInfoPanel(Category cat, Table detail) {
        Table panel = new Table();
        panel.top();
        panel.defaults().left();

        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setFadeScrollBars(false);
        detailScroll.setScrollingDisabled(true, false);
        panel.add(new com.bjsp123.rl2.ui.skin.ScrollHinted(detailScroll, skin))
                .expand().fill().top().left().row();
        return panel;
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
        if (it.inventoryCategory != null && it.inventoryCategory.isEquipment())
            sb.append("Equippable as ").append(it.inventoryCategory.name().toLowerCase()).append('\n');
        if (it.material != null)    sb.append("Made of ").append(it.material.name().toLowerCase()).append('\n');
        // Damage / armor reflect the item's plusses via ItemSystem so a +N
        // entry in the encyclopaedia shows the level-scaled numbers, not the
        // bare items.csv baseline.
        if (it.damage.max() > 0) {
            com.bjsp123.rl2.model.MinMax r =
                    com.bjsp123.rl2.logic.ItemSystem.effectiveDamageRange(it);
            sb.append("Inflicts ").append(r.min()).append('-').append(r.max()).append(" damage\n");
        }
        if (it.armor.max() > 0) {
            com.bjsp123.rl2.model.MinMax r =
                    com.bjsp123.rl2.logic.ItemSystem.effectiveArmorRange(it);
            sb.append("Protects from ").append(r.min()).append('-').append(r.max()).append(" damage\n");
        }
        if (it.lightRadius > 0)     sb.append("Casts light over ").append((int) it.lightRadius).append(" tiles\n");
        if (it.foodValue > 0)       sb.append("Food value of ").append(it.foodValue).append('\n');
        if (it.throwEffect != null) {
            sb.append("When thrown: ").append(it.throwEffect.name().toLowerCase()).append('\n');
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
        return com.bjsp123.rl2.ui.MobLore.describe(m);
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
            case FLOOR         -> "Floor.";
            case FLOOR_WOOD    -> "Wood floor.";
            case FLOOR_SPECIAL -> "Special floor.";
            case WALL          -> "Wall.";
            case DOOR          -> "Door (closed).";
            case DOOR_OPEN     -> "Door (open).";
            case CHASM         -> "Chasm.";
            case LAMP          -> "Lamp .";
            case STAIRS_UP     -> "Stairs up";
            case STAIRS_DOWN   -> "Stairs down";
            case STATUE_SMALL_L, STATUE_SMALL_R -> "Small statue.";
            case STATUE_LARGE_L, STATUE_LARGE_R -> "Large statue.";
            case ALTAR         -> "Altar.";
            case THRONE_L, THRONE_R -> "Throne.";
        };
    }

    private static String describeTile(Tile t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.isFloorLike()      ? "Walkable.\n" : "Blocks movement.\n");
        sb.append(t.blocksSight()      ? "Blocks sight.\n" : "See-through.\n");
        return switch (t) {
            case FLOOR         -> sb + "\nGood for standing on..";
            case FLOOR_WOOD    -> sb + "\nGood for standing on..";
            case FLOOR_SPECIAL -> sb + "\nA decoratively-tiled patch of floor.";
            case WALL          -> sb + "\nSolid wall, blocks movement and sight.";
            case DOOR          -> sb + "\nA closed door — bumping it opens it.";
            case DOOR_OPEN     -> sb + "\nAn open door — lets sight and movement through.";
            case CHASM         -> sb + "\nA chasm. Non-flying mobs that step in fall to the next level.";
            case LAMP          -> sb + "\nA lit lamp — emits light.";
            case STAIRS_UP     -> sb + "\nStairs leading up to the previous level.";
            case STAIRS_DOWN   -> sb + "\nStairs leading down to the next level.";
            case STATUE_SMALL_L, STATUE_SMALL_R ->
                    sb + "\nA small decorative statue. Blocks movement but you can see past it.";
            case STATUE_LARGE_L, STATUE_LARGE_R ->
                    sb + "\nA tall decorative statue. Blocks movement and sight.";
            case ALTAR         -> sb + "\nA stone altar.";
            case THRONE_L, THRONE_R -> sb + "\nA carved throne. Sized for a single occupant.";
        };
    }
}
