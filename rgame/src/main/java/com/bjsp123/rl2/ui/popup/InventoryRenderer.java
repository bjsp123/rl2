package com.bjsp123.rl2.ui.popup;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemSlot;
import com.bjsp123.rl2.model.Mob;

import java.util.function.BiConsumer;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.GemSprites;
import com.bjsp123.rl2.ui.overlay.LookRenderer;
import com.bjsp123.rl2.ui.skin.UiTheme;

/**
 * In-game inventory: a centred 6-slot equipment row above a 6×6 bag grid, plus a detail popup
 * that opens when an item is tapped. Built as a scene2d {@link Group} so it lives inside the
 * shared in-game {@link Stage} and benefits from automatic resize handling and standard hit
 * routing.
 */
public class InventoryRenderer extends Group {

    /** Slots in slot-index order. The first {@link #GEAR_COUNT} are rendered in the
     *  upper equipment strip; the trailing three are gem slots, rendered in a smaller
     *  row below. Indices 0..{@code SLOTS.length-1} are equipment cells; indices
     *  {@code SLOTS.length}..{@code SLOTS.length + BAG_SIZE - 1} are bag cells. */
    private static final ItemSlot[] SLOTS = {
            ItemSlot.WEAPON, ItemSlot.OFFHAND, ItemSlot.ARMOR,
            ItemSlot.RING1,  ItemSlot.RING2,   ItemSlot.AMULET,
            ItemSlot.GEM1,   ItemSlot.GEM2,    ItemSlot.GEM3
    };
    /** Number of leading gear slots (the rest are gem slots). */
    private static final int GEAR_COUNT = 6;
    private static final int BAG_COLS = 6;
    private static final int BAG_ROWS = 6;
    private static final float CELL = 32f;

    private final Skin skin;

    private final Container<Table> framed;
    private final Stack[] equipCells = new Stack[SLOTS.length];
    private final Stack[] bagCells   = new Stack[BAG_COLS * BAG_ROWS];

    private final Container<Table> popupFramed;
    private final Label    popupName;
    private final Label    popupLevel;
    private final Image    popupIcon;
    private final Label    popupDescription;
    private final Label    popupStats;
    private final TextButton popupEquip, popupThrow, popupUse, popupCombine;
    private final TextButton[] popupQuickslot;
    private int popupSlot = -1;
    /** Keyboard focus index inside the popup: 0=Equip, 1=Throw, 2=Use. */
    private int popupFocus = 0;

    /** Legacy 32×32-cell composite sheet (sword/shield/armor/amulet etc.) — still used
     *  for the few items that don't yet live on the per-row sai/items.png grid. */
    /** Procedurally-generated silhouette for the empty ring slots — the only texture
     *  this renderer owns directly; everything else (sword/shield/armor/amulet/staff,
     *  the sai/items.png grid) is loaded once by {@link ItemSprites} and shared.
     *  See {@link #buildRingTexture}. */
    private final Texture ringSilhouetteTex;
    /** Per-item-type sheet at {@code sprites/items.png} — 32×32 tiles, three rows of
     *  themed art:
     *  <ul>
     *    <li>row 0 — wands: magic missile, oil, flame, dog, water, vegetation, banishment</li>
     *    <li>row 1 — food:  pear, delicious fish, scrumptious pear, silvery pear, conference pear</li>
     *    <li>row 2 — potions: sorcery, ghostliness, healing, invisibility, poison</li>
     *  </ul> */
    // Sprite regions deleted — looked up on demand through {@link ItemSprites}.

    private Mob player;
    private com.bjsp123.rl2.ui.hud.ActionBar actionBar;
    private boolean open;
    private LookRenderer lookRenderer;
    /** Encyclopaedia popup the item-detail "?" info button opens. Set externally; null
     *  disables the button (it still renders but its click handler no-ops). */
    private com.bjsp123.rl2.ui.popup.EncyclopediaRenderer encyclopedia;
    private BiConsumer<Mob, Item> onThrow;
    private BiConsumer<Mob, Item> onUse;
    /** Callback fired by the popup's "Combine" button. The receiver opens the crafting
     *  screen with the item pre-loaded into the first empty crafting slot. */
    private BiConsumer<Mob, Item> onCombine;
    /** When >= 0, the next item the user taps is bound to player.actionSlots[bindingSlot]
     *  instead of opening the detail popup. Set by {@link #beginBinding}; cleared on close
     *  or once a binding is captured. */
    private int bindingSlot = -1;

    /** Bag-tab categories. Items are grouped by {@link Category} via
     *  {@link #categorize(Item.ItemType)}, sorted by ItemType ordinal then by name, and
     *  displayed in a single shared 6×6 grid. The {@link #currentCategory} field tracks
     *  which tab is currently visible. */
    private enum Category { GEAR, FOOD, ITEMS, GEMS }
    private Category currentCategory = Category.GEAR;
    private final TextButton[] tabButtons = new TextButton[Category.values().length];
    /** {@code currentTabBagIndices[i]} = the index into {@code player.inventory.bag} of the
     *  item displayed in {@code bagCells[i]} for the current tab. {@code -1} means the cell
     *  is empty. Rebuilt on every {@link #refresh()}. */
    private final int[] currentTabBagIndices = new int[BAG_COLS * BAG_ROWS];

    public InventoryRenderer(Skin skin) {
        this.skin = skin;

        // Item textures (legacy spd strip + sai/items.png grid + staff) are loaded once
        // by ItemSprites and shared with the world-floor and HUD action-bar renderers.
        // The only sprite this class still owns is the procedural ring silhouette for
        // empty ring slots.
        ringSilhouetteTex = buildRingTexture();

        // ── Layout: outer modal panel containing three labeled sub-panels ─────
        // The whole inventory screen sits inside one big panel (the outer chrome).
        // Inside, three labeled sub-panels stack vertically — each full-width within
        // the outer panel's content area:
        //   1. Equipped Gems  — no panel chrome, slots centred. Visually a group
        //                       under the label, distinguished by being border-less.
        //   2. Equipment      — bordered panel, slots centred.
        //   3. Backpack       — bordered panel with NO TOP BORDER (panel-open-top).
        //                       Above it, a tab strip whose inactive tabs' bottom
        //                       borders form the panel's visual top edge, and whose
        //                       active tab has an open bottom + matching panel fill,
        //                       so the panel's interior flows seamlessly into it.

        // 1. Equipped Gems — bare row, centred, no panel chrome.
        Table gemRow = new Table();
        gemRow.defaults().pad(2);
        for (int i = GEAR_COUNT; i < SLOTS.length; i++) {
            equipCells[i] = makeCell(i);
            gemRow.add(equipCells[i]).size(CELL);
        }
        Table gemsPanel = new Table();   // no setBackground — borderless
        gemsPanel.add(gemRow);           // default cell alignment is centre

        // 2. Equipment — bordered panel with the six gear slots centred.
        Table equipRow = new Table();
        equipRow.defaults().pad(2);
        for (int i = 0; i < GEAR_COUNT; i++) {
            equipCells[i] = makeCell(i);
            equipRow.add(equipCells[i]).size(CELL);
        }
        Table equipPanel = new Table();
        equipPanel.setBackground(skin.getDrawable("panel"));
        equipPanel.pad(6);
        equipPanel.add(equipRow);        // centred

        // 3. Backpack — tab strip above an open-top panel.
        Table tabsRow = new Table();
        tabsRow.defaults().pad(0);
        Category[] cats = Category.values();
        String[] tabLabels = {"Gear", "Food", "Items", "Gems"};
        for (int i = 0; i < cats.length; i++) {
            final Category cat = cats[i];
            tabButtons[i] = new TextButton(tabLabels[i], skin, "tab");
            tabButtons[i].addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    currentCategory = cat;
                    update();
                }
            });
            tabsRow.add(tabButtons[i]).width(60).height(26);
        }

        Table bagGrid = new Table();
        bagGrid.defaults().pad(2);
        for (int r = 0; r < BAG_ROWS; r++) {
            for (int c = 0; c < BAG_COLS; c++) {
                int idx = SLOTS.length + r * BAG_COLS + c;
                bagCells[r * BAG_COLS + c] = makeCell(idx);
                bagGrid.add(bagCells[r * BAG_COLS + c]).size(CELL);
            }
            bagGrid.row();
        }
        // Backpack section — a Stack arranged so the header (label + tabs) sits
        // ABOVE the panel and overlaps ONLY its 2-px top border:
        //
        //   Layer 0 (drawn first, behind):    Layer 1 (drawn second, on top):
        //   ┌─empty (TAB_H - 2 px)──┐         ┌─header (TAB_H px)─────┐
        //   │                       │         │ Backpack    [T1][T2]  │
        //   ├──── panel top ────────┤    ←────┘   tabs overlap here   ┕────────
        //   ║                       ║         │ bag grid              │
        //   ║  panel interior       ║         │                       │
        //   ║                       ║         │                       │
        //   ╚═══════════════════════╝         └───────────────────────┘
        //
        // The tabs cover the panel's top border in their column span (active fill =
        // panel fill, so the active tab visually flows into the panel below); the
        // border stays visible behind the "Backpack" label area where the label's
        // mostly-transparent text doesn't obscure it.
        final int TAB_H = 26;
        com.badlogic.gdx.scenes.scene2d.ui.Stack backpackSection =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();

        Image backpackBg = new Image(skin.getDrawable("panel"));
        backpackBg.setScaling(com.badlogic.gdx.utils.Scaling.stretch);
        Table backpackBgLayer = new Table();
        backpackBgLayer.top();
        // Empty cell above the panel — the panel chrome starts (TAB_H - 2) below
        // the top of the Stack so the tab row above only meets its top border.
        backpackBgLayer.add().height(TAB_H - 2).row();
        backpackBgLayer.add(backpackBg).expand().fill();
        backpackSection.add(backpackBgLayer);

        Table backpackContent = new Table();
        backpackContent.top();
        Table backpackHeader = new Table();
        // padRight(12) on the label cell guarantees a visible gap before the tab
        // strip even on narrow viewports where expandX shrinks the cell to its
        // preferred width.
        backpackHeader.add(new Label("Backpack", skin, "title"))
                .left().expandX().padLeft(4).padRight(12);
        backpackHeader.add(tabsRow).right();
        backpackContent.add(backpackHeader).fillX().height(TAB_H).row();
        // Bag grid sits inside the panel's interior — pad(8) keeps it well clear of
        // the panel's borders. Cell expand+fill so the grid uses the leftover height.
        backpackContent.add(bagGrid).pad(8).expand().fill();
        backpackSection.add(backpackContent);

        // ── Outer frame: one big panel wrapping everything ─────────────────────
        // The outer panel's chrome reads as the inventory screen's frame. The three
        // sub-panel rows all use {@code fillX()} so each panel stretches to the full
        // width of the outer container. The tabs row sits flush above the backpack
        // panel ({@code padBottom(0)}); the active tab's interior visually flows
        // into the backpack panel since panel-open-top has no top border.
        // Vertical rhythm: every group-box LABEL has a 4-px gap to its panel below,
        // and every PANEL has a 10-px gap to the next section. These two gaps give a
        // consistent breathing pattern down the modal — same below "Equipped Gems",
        // below "Equipment", and below the equipment panel before the backpack
        // section starts.
        Table outerFrame = new Table();
        outerFrame.setBackground(skin.getDrawable("panel"));
        outerFrame.pad(10);
        outerFrame.defaults().fillX();
        outerFrame.add(new Label("Equipped Gems", skin, "title"))
                .left().padBottom(4).row();
        outerFrame.add(gemsPanel).fillX().padBottom(10).row();
        outerFrame.add(new Label("Equipment", skin, "title"))
                .left().padBottom(4).row();
        outerFrame.add(equipPanel).fillX().padBottom(10).row();
        // Backpack section — single Stack containing the panel chrome + an overlay
        // with the header (label + tabs) and the bag grid. The header is drawn ON
        // TOP of the panel, so the tabs cover the panel's top border in their span
        // while the border stays visible behind the label area (label text is
        // mostly transparent).
        outerFrame.add(backpackSection).fillX().expand().fill();

        framed = new Container<>(outerFrame);
        framed.fill();
        addActor(framed);

        // ── popup ───────────────────────────────────────────────────────
        // Layout (top → bottom):
        //   Header row  — name (left, expands) + icon (top-right)
        //   Level line  — "+N" badge / dim label, hidden when level == 0
        //   Description — wraps to popup width
        //   Stats       — single-line numerical summary (damage, armor, light, …)
        //   Action row  — Equip / Throw / Use / Combine, equal-width via uniform()
        //   Quickslot   — six numbered buttons bound to action-bar slots
        // Free-text fields (name, description) call setWrap or setEllipsis so they
        // never spill outside the popup; widths come from the table cells.
        Table popup = new Table();
        popup.pad(UiTheme.OUTER_PAD).defaults().pad(2).left();
        popup.setBackground(skin.getDrawable("simple-panel"));
        popup.top();

        Table headerRow = new Table();
        popupName = new Label("", skin, "title");
        popupName.setWrap(true);
        popupIcon = new Image();
        popupIcon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        // "?" info button under the icon — opens the encyclopaedia turned to the
        // popup item's ItemType. Reads the current item via itemAtSlot(popupSlot)
        // at click time so it always matches whatever the popup is showing.
        TextButton popupEncBtn = new TextButton("?", skin);
        popupEncBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                Item it = itemAtSlot(popupSlot);
                if (encyclopedia != null && it != null && it.type != null) {
                    encyclopedia.openTo(
                            com.bjsp123.rl2.ui.popup.EncyclopediaRenderer.Category.ITEMS,
                            it.type);
                }
            }
        });
        Table iconCol = new Table();
        iconCol.add(popupIcon).size(UiTheme.INV_ITEM_ICON_SIZE).row();
        iconCol.add(popupEncBtn).size(UiTheme.INV_ITEM_ICON_SIZE, 22).padTop(4);
        // Name on the left, growing into available width; icon column parked top-right.
        headerRow.add(popupName).left().top().growX();
        headerRow.add(iconCol).top().right().padLeft(8);
        popup.add(headerRow).growX().padBottom(4).row();

        popupLevel = new Label("", skin, "dim");
        popup.add(popupLevel).left().padBottom(UiTheme.SECTION_GAP).row();

        popupDescription = new Label("", skin, "default");
        popupDescription.setWrap(true);
        // growX with prefWidth(0) lets the cell drive the wrap width — the label
        // wraps to whatever horizontal space the popup gives it.
        popup.add(popupDescription).growX().prefWidth(0).padBottom(UiTheme.SECTION_GAP).row();

        popupStats = new Label("", skin, "default");
        popupStats.setWrap(true);
        popup.add(popupStats).growX().prefWidth(0).padBottom(UiTheme.SECTION_GAP + 4).row();

        Table btnRow = new Table();
        popupEquip   = new TextButton("Equip",   skin);
        popupThrow   = new TextButton("Throw",   skin);
        popupUse     = new TextButton("Use",     skin);
        popupCombine = new TextButton("Combine", skin);
        popupEquip.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { tryEquip(); }
        });
        popupThrow.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { tryThrow(); }
        });
        popupUse.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { tryUse();   }
        });
        popupCombine.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { tryCombine(); }
        });
        // uniform() + growX() makes every enabled action button render at exactly the
        // same width — no visual focus highlight, no "primary" choice. Disabled
        // buttons are still greyed out by the skin's `disabled` style.
        btnRow.defaults().pad(3).height(32).uniform().growX();
        btnRow.add(popupEquip);
        btnRow.add(popupThrow);
        btnRow.add(popupUse);
        btnRow.add(popupCombine);
        popup.add(btnRow).growX().padBottom(UiTheme.SECTION_GAP).row();

        // Quickslot binding row — six small numbered buttons that bind the popup's item to
        // player.actionSlots[i] and close the inventory. Lets the user assign a fire bomb
        // or wand to a hotbar slot in one tap from the item detail.
        Label qsLabel = new Label("Quickslot:", skin, "dim");
        popup.add(qsLabel).left().padTop(4).padBottom(2).row();
        Table qsRow = new Table();
        qsRow.defaults().pad(2).height(28).uniform().growX();
        popupQuickslot = new TextButton[6];
        for (int i = 0; i < popupQuickslot.length; i++) {
            final int slotIndex = i;
            TextButton btn = new TextButton(String.valueOf(i + 1), skin);
            btn.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    bindPopupItemToActionSlot(slotIndex);
                }
            });
            popupQuickslot[i] = btn;
            qsRow.add(btn);
        }
        popup.add(qsRow).growX().row();

        popupFramed = new Container<>(popup);
        popupFramed.fill();
        popupFramed.setVisible(false);
        addActor(popupFramed);

        setTouchable(Touchable.enabled);
        setVisible(false);

        // Outside-click dismissal: clicks not on a cell or the popup come to the group.
        addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                Actor target = event.getTarget();
                if (popupSlot >= 0) {
                    if (!isDescendant(target, popupFramed)) {
                        popupSlot = -1;
                        popupFramed.setVisible(false);
                        return true;
                    }
                } else if (target == InventoryRenderer.this) {
                    close();
                    return true;
                }
                return false;
            }

            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return InventoryRenderer.this.handleKey(keycode);
            }
        });
    }

    private static boolean isDescendant(Actor a, Group g) {
        for (Actor cur = a; cur != null; cur = cur.getParent()) {
            if (cur == g) return true;
        }
        return false;
    }

    private Stack makeCell(int index) {
        // Equipment cells (index < SLOTS.length, i.e. weapon / armor / amulet / gems)
        // get the {@code equip-slot} drawable so the worn-gear row reads as visually
        // distinct from the carried-bag grid below it. Bag cells (index ≥ SLOTS.length)
        // use the plain {@code item-slot} drawable.
        String bgKey = (index < SLOTS.length) ? "equip-slot" : "item-slot";
        Image bg = new Image(skin.getDrawable(bgKey));
        Image content = new Image();
        content.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        // Count badge for stacked items — bottom-left, blank when stack size is 1.
        Label countL = new Label("", skin, "default");
        countL.setColor(Color.WHITE);
        Container<Label> countWrap = new Container<>(countL);
        countWrap.bottom().left().pad(1);
        Stack stack = new Stack();
        stack.add(bg);
        stack.add(content);
        stack.add(countWrap);
        stack.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                if (player == null) return;
                Item it = itemAtSlot(index);
                if (it == null) return;
                // Long-press from an action button puts us in "binding mode" — the next
                // item tap goes straight into the action slot instead of opening the popup.
                if (bindingSlot >= 0) {
                    if (actionBar != null) actionBar.set(bindingSlot, it);
                    bindingSlot = -1;
                    close();
                    return;
                }
                openDetail(index);
            }
        });
        // Sentinel: the second child holds the icon, which we mutate in update().
        return stack;
    }

    public void setPlayer(Mob player) { this.player = player; }
    public void setActionBar(com.bjsp123.rl2.ui.hud.ActionBar bar) { this.actionBar = bar; }
    public void setLookRenderer(LookRenderer look) { this.lookRenderer = look; }
    public void setEncyclopedia(com.bjsp123.rl2.ui.popup.EncyclopediaRenderer e) {
        this.encyclopedia = e;
    }
    public void setOnThrow(BiConsumer<Mob, Item> onThrow) { this.onThrow = onThrow; }
    public void setOnUse(BiConsumer<Mob, Item> onUse)     { this.onUse   = onUse; }
    public void setOnCombine(BiConsumer<Mob, Item> onCombine) { this.onCombine = onCombine; }

    public boolean isOpen() { return open; }

    public void toggle() {
        if (lookRenderer != null && lookRenderer.isOpen()) return;
        if (open) close();
        else open();
    }

    public void open() {
        open = true;
        popupSlot = -1;
        popupFramed.setVisible(false);
        setVisible(true);
        if (getStage() != null) getStage().setKeyboardFocus(this);
    }

    public void close() {
        open = false;
        popupSlot = -1;
        bindingSlot = -1;
        popupFramed.setVisible(false);
        setVisible(false);
    }

    /** Open the inventory in "bind to action slot N" mode. The next item the user clicks is
     *  written into {@code player.actionSlots[slot]} (replacing whatever was there) and the
     *  inventory closes. Tapping outside or pressing Esc cancels — the binding only commits
     *  on a successful item click. */
    public void beginBinding(int slot) {
        bindingSlot = slot;
        if (!open) open();
    }

    public void update() {
        if (!open) return;
        if (player == null) { close(); return; }

        for (int i = 0; i < SLOTS.length; i++) {
            Item eq = player.inventory.equipped(SLOTS[i]);
            setCellIcon(equipCells[i], eq != null ? regionFor(eq) : silhouetteRegionFor(SLOTS[i]),
                        eq == null);
        }
        // Build the visible bag list for the active tab. Sorted by ItemType ordinal
        // first, then by display name within type, so a stack of pears always reads
        // pear → silvery → conference → scrumptious → fish (or wherever those types
        // sit in their enum group).
        java.util.List<Integer> tabIndices = new java.util.ArrayList<>();
        for (int i = 0; i < player.inventory.bag.size(); i++) {
            Item it = player.inventory.bag.get(i);
            if (it == null || it.type == null) continue;
            if (categorize(it.type) == currentCategory) tabIndices.add(i);
        }
        tabIndices.sort((a, b) -> {
            Item ia = player.inventory.bag.get(a);
            Item ib = player.inventory.bag.get(b);
            int cmp = Integer.compare(ia.type.ordinal(), ib.type.ordinal());
            if (cmp != 0) return cmp;
            String na = ia.name == null ? "" : ia.name;
            String nb = ib.name == null ? "" : ib.name;
            return na.compareTo(nb);
        });

        for (int i = 0; i < currentTabBagIndices.length; i++) {
            int bagIdx = i < tabIndices.size() ? tabIndices.get(i) : -1;
            currentTabBagIndices[i] = bagIdx;
            Item it = bagIdx >= 0 ? player.inventory.bag.get(bagIdx) : null;
            setCellContents(bagCells[i], it != null ? regionFor(it) : null,
                    false, it != null ? it.count : 1);
        }
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setChecked(Category.values()[i] == currentCategory);
        }

        if (popupSlot >= 0) updatePopup();
    }

    /** Map an ItemType to one of the four bag tabs. New ItemTypes default to
     *  {@link Category#ITEMS} so they show up somewhere. */
    private static Category categorize(Item.ItemType type) {
        return switch (type) {
            case SWORD, DAGGER, SHIELD, SCALE_MAIL, AMULET_OF_LIGHT -> Category.GEAR;
            case PEAR, PEAR_SCRUMPTIOUS, PEAR_SILVERY, PEAR_CONFERENCE, FISH -> Category.FOOD;
            case GEM -> Category.GEMS;
            default -> Category.ITEMS;
        };
    }

    private void setCellIcon(Stack cell, TextureRegion region, boolean dim) {
        setCellContents(cell, region, dim, 1);
    }

    /** Set the icon and count badge in one call. {@code count} ≤ 1 hides the badge. */
    private void setCellContents(Stack cell, TextureRegion region, boolean dim, int count) {
        Image content = (Image) cell.getChildren().get(1);
        if (region == null) {
            content.setDrawable(null);
        } else {
            content.setDrawable(new TextureRegionDrawable(region));
            content.setColor(dim ? new Color(0.35f, 0.35f, 0.40f, 1f) : Color.WHITE);
        }
        // Third child is the count-badge container holding a Label.
        if (cell.getChildren().size > 2) {
            @SuppressWarnings("unchecked")
            Container<Label> wrap = (Container<Label>) cell.getChildren().get(2);
            Label countL = wrap.getActor();
            if (countL != null) {
                countL.setText(count > 1 ? Integer.toString(count) : "");
            }
        }
    }

    private void updatePopup() {
        Item it = itemAtSlot(popupSlot);
        if (it == null) { popupSlot = -1; popupFramed.setVisible(false); return; }
        popupName.setText(com.bjsp123.rl2.ui.Names.titleCase(
                it.name == null ? "(item)" : it.name));
        // Level shown as "+N" — kept compact and only when level > 0. Item.describe()
        // displays "+N" using level-1, but bare bag items use raw level. Match the
        // bag display: any level > 0 shows.
        popupLevel.setText(it.level > 0 ? ("Level +" + it.level) : "");
        TextureRegion icon = regionFor(it);
        popupIcon.setDrawable(icon != null ? new TextureRegionDrawable(icon) : null);
        popupDescription.setText(it.description == null ? "" : it.description);

        StringBuilder sb = new StringBuilder();
        for (String line : statLines(it)) sb.append(line).append('\n');
        popupStats.setText(sb.toString());

        popupEquip  .setDisabled(!canEquip(it));
        popupThrow  .setDisabled(!canThrow(it));
        popupUse    .setDisabled(!it.isUsable());
        popupCombine.setDisabled(!com.bjsp123.rl2.logic.RecipeSystem.isCraftable(it));
        popupUse.setText(it.isUsable() && it.useVerb != null && !it.useVerb.isEmpty()
                ? capitalize(it.useVerb) : "Use");
        // No checked-state highlighting on action buttons — every enabled choice
        // is visually equal; disabled buttons greyed out by their `disabled` style.

        popupFramed.setVisible(true);
    }

    /** Position the framed grid + popup centred against the stage at deterministic
     *  preferred sizes — the grid is a LARGE panel (fills most of the screen), the
     *  item-detail popup is MEDIUM. Sizes are independent of bag contents and of
     *  which category tab is active, so panels never resize as the player browses. */
    public void layoutForStage(Stage stage) {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();
        setBounds(0, 0, w, h);

        float gridW = com.bjsp123.rl2.ui.skin.PanelSize.widthFor(
                com.bjsp123.rl2.ui.skin.PanelSize.Kind.LARGE, w);
        float gridH = com.bjsp123.rl2.ui.skin.PanelSize.heightFor(
                com.bjsp123.rl2.ui.skin.PanelSize.Kind.LARGE, h);
        framed.setSize(gridW, gridH);
        framed.setPosition((w - gridW) / 2f, (h - gridH) / 2f);

        // Item-detail popup uses the centralised constants in UiTheme so the size
        // can be tweaked alongside the look popup without touching this file.
        // Width is fixed; height grows with content but caps at MAX_H so the popup
        // stays well clear of the screen edges on a small viewport.
        float popW = Math.min(UiTheme.INV_ITEM_POPUP_W, w - 20f);
        float popH = Math.min(UiTheme.INV_ITEM_POPUP_MAX_H, h - 20f);
        popupFramed.setSize(popW, popH);
        popupFramed.setPosition((w - popW) / 2f, (h - popH) / 2f);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (getStage() != null) layoutForStage(getStage());
        update();
    }

    private boolean handleKey(int keycode) {
        if (keycode == Input.Keys.I) {
            if (lookRenderer != null && lookRenderer.isOpen()) return false;
            toggle();
            return true;
        }
        if (!open) return false;

        if (popupSlot >= 0) return popupKey(keycode);
        switch (keycode) {
            case Input.Keys.ESCAPE -> { close(); return true; }
            default -> { return true; }
        }
    }

    private boolean popupKey(int keycode) {
        Item it = itemAtSlot(popupSlot);
        if (it == null) { popupSlot = -1; popupFramed.setVisible(false); return true; }
        switch (keycode) {
            case Input.Keys.ESCAPE -> { popupSlot = -1; popupFramed.setVisible(false); return true; }
            case Input.Keys.LEFT, Input.Keys.NUMPAD_4,
                 Input.Keys.UP,   Input.Keys.NUMPAD_8 -> {
                popupFocus = (popupFocus + 3) % 4; return true;
            }
            case Input.Keys.RIGHT, Input.Keys.NUMPAD_6,
                 Input.Keys.DOWN,  Input.Keys.NUMPAD_2 -> {
                popupFocus = (popupFocus + 1) % 4; return true;
            }
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER, Input.Keys.SPACE -> {
                switch (popupFocus) {
                    case 0 -> tryEquip();
                    case 1 -> tryThrow();
                    case 2 -> tryUse();
                    case 3 -> tryCombine();
                    default -> {}
                }
                return true;
            }
            case Input.Keys.E -> { tryEquip();   return true; }
            case Input.Keys.T -> { tryThrow();   return true; }
            case Input.Keys.U -> { tryUse();     return true; }
            case Input.Keys.C -> { tryCombine(); return true; }
            // NUM_1..NUM_6 only — the numpad keys overlap with the arrow-nav handler above.
            case Input.Keys.NUM_1 -> { bindPopupItemToActionSlot(0); return true; }
            case Input.Keys.NUM_2 -> { bindPopupItemToActionSlot(1); return true; }
            case Input.Keys.NUM_3 -> { bindPopupItemToActionSlot(2); return true; }
            case Input.Keys.NUM_4 -> { bindPopupItemToActionSlot(3); return true; }
            case Input.Keys.NUM_5 -> { bindPopupItemToActionSlot(4); return true; }
            case Input.Keys.NUM_6 -> { bindPopupItemToActionSlot(5); return true; }
            default -> { return true; }
        }
    }

    private void openDetail(int slot) {
        popupSlot = slot;
        Item it = itemAtSlot(slot);
        if (it == null) return;
        if (canEquip(it))      popupFocus = 0;
        else if (canThrow(it)) popupFocus = 1;
        else                   popupFocus = 2;
    }

    private void tryEquip() {
        Item it = itemAtSlot(popupSlot);
        if (it == null || !canEquip(it)) return;
        player.inventory.equip(it);
        popupSlot = -1;
        popupFramed.setVisible(false);
    }

    /** Pop the inventory and hand the item over to the crafting screen. The crafting
     *  screen is responsible for pre-loading the item into a free slot. */
    private void tryCombine() {
        Item it = itemAtSlot(popupSlot);
        if (it == null || !com.bjsp123.rl2.logic.RecipeSystem.isCraftable(it)) return;
        popupSlot = -1;
        popupFramed.setVisible(false);
        Mob user = player;
        close();
        if (onCombine != null) onCombine.accept(user, it);
    }

    private void tryThrow() {
        Item it = itemAtSlot(popupSlot);
        if (it == null || !canThrow(it)) return;
        popupSlot = -1;
        popupFramed.setVisible(false);
        close();
        if (onThrow != null) onThrow.accept(player, it);
    }

    private void tryUse() {
        Item it = itemAtSlot(popupSlot);
        if (it == null || !it.isUsable()) return;
        popupSlot = -1;
        popupFramed.setVisible(false);
        // Close the whole inventory FIRST, then fire the use callback. Any targeting overlay
        // or visual effect the use action triggers should appear over the world, not over a
        // still-open inventory grid.
        Mob user = player;
        close();
        if (onUse != null) onUse.accept(user, it);
    }

    /** Bind the popup's item to action-bar {@code slotIndex} and close the inventory.
     *  Companion to the long-press-on-quickslot flow ({@link #beginBinding}) — same end
     *  state, but driven from the item side rather than the slot side. */
    private void bindPopupItemToActionSlot(int slotIndex) {
        if (player == null || actionBar == null) return;
        if (slotIndex < 0 || slotIndex >= actionBar.size()) return;
        Item it = itemAtSlot(popupSlot);
        if (it == null) return;
        actionBar.set(slotIndex, it);
        popupSlot = -1;
        popupFramed.setVisible(false);
        close();
    }

    private Item itemAtSlot(int slot) {
        if (slot < 0 || player == null) return null;
        if (slot < SLOTS.length) return player.inventory.equipped(SLOTS[slot]);
        // Bag cells live in the visible-cell index space, not the raw bag-list index
        // space — tabs filter the displayed set. Resolve through {@link
        // #currentTabBagIndices}, populated each {@link #refresh()}.
        int cellIdx = slot - SLOTS.length;
        if (cellIdx < 0 || cellIdx >= currentTabBagIndices.length) return null;
        int bagIdx = currentTabBagIndices[cellIdx];
        if (bagIdx < 0 || bagIdx >= player.inventory.bag.size()) return null;
        return player.inventory.bag.get(bagIdx);
    }

    private boolean canEquip(Item it) {
        if (!it.isEquippable()) return false;
        // Iterate every ItemSlot (not just the gear-strip subset in SLOTS) so a popup on
        // an already-equipped gem in GEM1/2/3 disables the Equip button correctly.
        for (ItemSlot s : ItemSlot.values()) {
            if (player.inventory.equipped(s) == it) return false;
        }
        return true;
    }

    private boolean canThrow(Item it) { return it != null; }

    /** Inventory cells delegate icon lookup to {@link ItemSprites} so the world-floor
     *  renderer, the HUD action-bar, and the inventory all show the same art. Gems
     *  flow through {@link GemSprites} via {@link ItemSprites#regionFor(Item)}. */
    private TextureRegion regionFor(Item it) {
        return ItemSprites.regionFor(it);
    }

    /** Empty-equipment-slot silhouette: a faded archetype sprite for the slot type.
     *  Rings get the procedural ring outline; everything else uses the same canonical
     *  sprite as a representative item of that slot. */
    private TextureRegion silhouetteRegionFor(ItemSlot slot) {
        return switch (slot) {
            case WEAPON  -> ItemSprites.regionFor(Item.ItemType.SWORD);
            case OFFHAND -> ItemSprites.regionFor(Item.ItemType.SHIELD);
            case ARMOR   -> ItemSprites.regionFor(Item.ItemType.SCALE_MAIL);
            case RING1, RING2 -> new TextureRegion(ringSilhouetteTex);
            case AMULET  -> ItemSprites.regionFor(Item.ItemType.AMULET_OF_LIGHT);
            // Empty gem slots show no silhouette — the cell background alone signals
            // "drop a gem here." A procedural placeholder could be added later if the
            // empty state reads ambiguously.
            case GEM1, GEM2, GEM3 -> null;
        };
    }

    private static java.util.List<String> statLines(Item it) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (it.slot != null) out.add("Slot: " + slotLabel(it.slot));
        if (it.material != null) out.add("Material: " + it.material.name().toLowerCase());
        if (it.damageMax > 0) out.add("Damage: " + it.damageMin + "-" + it.damageMax);
        if (it.armorMax  > 0) out.add("Armor: "  + it.armorMin  + "-" + it.armorMax);
        if (it.lightRadius > 0) out.add("Light radius: " + (int) it.lightRadius);
        if (it.thrownBehavior != null && it.thrownBehavior != com.bjsp123.rl2.model.Item.ThrownBehavior.NOTHING) {
            out.add("Thrown: " + it.thrownBehavior.name().toLowerCase());
        }
        return out;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String slotLabel(ItemSlot s) {
        return switch (s) {
            case WEAPON  -> "weapon";
            case OFFHAND -> "off-hand";
            case ARMOR   -> "armor";
            case RING1, RING2 -> "ring";
            case AMULET  -> "amulet";
            case GEM1, GEM2, GEM3 -> "gem";
        };
    }

    private static Texture buildRingTexture() {
        int size = 16;
        Pixmap p = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0); p.fill();
        float cx = (size - 1) / 2f, cy = (size - 1) / 2f;
        float outer = 6f, inner = 3.5f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - cx, dy = y - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= outer && d >= inner) p.drawPixel(x, y, 0xFFFFFFFF);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    /** Source-compat shims with the old API. */
    public void create()  { }
    public void resize(int w, int h) { }
    public void render() { /* drawn by stage */ }
    public void dispose() {
        if (ringSilhouetteTex != null) ringSilhouetteTex.dispose();
        // The shared item textures live on {@link ItemSprites}; whoever owns the
        // application lifecycle calls {@link ItemSprites#disposeShared} on shutdown.
    }
}
