package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.InventorySystem;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.ui.hud.ActionBar;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;
import com.bjsp123.rl2.world.render.ItemSprites;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * V2 inventory popup — primitive-drawn modal window covering the bulk of the
 * viewport. Replaces the scene2d-based {@link com.bjsp123.rl2.ui.popup.InventoryRenderer}
 * for the in-game backpack experience.
 *
 * <p>Layout (top-down inside the outer window):
 * <ol>
 *   <li>Header label ("Backpack")</li>
 *   <li>Equipment row — five fixed cells: weapon, offhand, armor, amulet 0, amulet 1</li>
 *   <li>Gems row — three fixed cells: gem 0, gem 1, gem 2</li>
 *   <li>Tab strip — Gear / Food / Items / Gems</li>
 *   <li>Bag grid — 6 cols × N rows of free-form items in the current tab</li>
 * </ol>
 *
 * <p>Tapping any cell that holds an item opens a smaller item-detail popup
 * window centred over the inventory, with Use / Equip / Throw action buttons
 * and a close-on-outside-tap behaviour.
 *
 * <p>Render lifecycle: the screen calls {@link #render()} after the V2 HUD
 * pass so the popup overlays the HUD. Input is captured via
 * {@link #input()} — when {@link #isOpen()} returns false the processor
 * passes through without consuming.
 */
public final class V2Inventory implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private enum Tab { GEAR, FOOD, ITEMS, GEMS }

    /** Map each tab to a glyph in the shared UI icon sheet. FOOD and GEMS
     *  have no dedicated icons yet — fall back to OTHER. */
    private static com.bjsp123.rl2.world.render.IconSprites.Icon tabIcon(Tab t) {
        return switch (t) {
            case GEAR  -> com.bjsp123.rl2.world.render.IconSprites.Icon.EQUIPMENT;
            case FOOD  -> com.bjsp123.rl2.world.render.IconSprites.Icon.FOOD;
            case ITEMS -> com.bjsp123.rl2.world.render.IconSprites.Icon.ITEMS;
            case GEMS  -> com.bjsp123.rl2.world.render.IconSprites.Icon.GEMS;
        };
    }

    // ── State ───────────────────────────────────────────────────────────────
    private final UiCtx ctx;
    private boolean open;
    private Mob player;
    private ActionBar actionBar;
    private Tab currentTab = Tab.GEAR;

    /** Item the user has tapped — non-null while the item-detail popup is up. */
    private Item selectedItem;

    // Hit rects for the main popup.
    private final Rect window         = new Rect();
    private final Rect headerRect     = new Rect();
    private final Rect[] equipRects   = new Rect[5];
    private final Rect[] gemRects     = new Rect[3];
    private final Rect[] tabRects     = new Rect[Tab.values().length];
    /** Bag grid cells — built each frame from the player's bag, filtered by tab. */
    private final List<BagCell> bagCells = new ArrayList<>();

    // Item-detail popup rects.
    private final Rect detailWindow   = new Rect();
    private final Rect detailIconRect = new Rect();
    private final Rect detailUseBtn   = new Rect();
    private final Rect detailEquipBtn = new Rect();
    private final Rect detailThrowBtn = new Rect();
    /** Encyclopedia "?" jump button — top-right of the detail window. */
    private final Rect detailInfoBtn  = new Rect();
    /** Pre-wrapped flavor / details lines for the current item-detail popup —
     *  populated in {@link #layoutRects()} so the shape pass (which paints
     *  the divider rule) and the text pass (which paints the lines) agree
     *  on the layout. */
    private final List<String> detailFlavorLines  = new ArrayList<>();
    private final List<String> detailDetailsLines = new ArrayList<>();
    /** Y of the horizontal rule between flavor and details (virtual pixels)
     *  or {@code Float.NaN} when one half is empty and no rule is drawn. */
    private float detailDividerY = Float.NaN;
    /** Top of the body text region (the y of the first flavor line). */
    private float detailBodyTop;
    private float detailLineH() { return ctx.lineH(); }
    /** Quickslot-binding buttons — six numbered cells in the detail popup
     *  that bind / unbind the chosen item to / from each action-bar slot.
     *  Built only when an {@link ActionBar} has been wired. */
    private final Rect[] bindBtnRects = new Rect[6];

    // Pressed state.
    private final boolean[] tabPressed = new boolean[Tab.values().length];
    /** Index into {@link #bagCells} of the cell currently being held, or -1. */
    private int bagPressed = -1;
    /** Index into {@link #equipRects} (0..4) of the equipment cell being
     *  held, or -1. Equipment cells share the bag-cell click semantics —
     *  tap fills {@link #selectedItem} and opens the detail popup, where
     *  Use / Throw fire on the equipped item. */
    private int equipPressed = -1;
    /** Index into {@link #gemRects} (0..2) similarly. */
    private int gemPressed = -1;
    private boolean detailUsePressed, detailEquipPressed, detailThrowPressed;
    private boolean detailInfoPressed;
    /** Index of the bind-button (0..5) currently held, or -1. */
    private int bindPressed = -1;

    /** Bag-grid scroller — used when the player's filtered bag has more
     *  items than fit in the visible grid area. Touch drag on the grid
     *  scrolls; mouse wheel scrolls; tab switches reset to top. */
    private final Scroller bagScroller = new Scroller();

    // Callbacks (mirror V1 InventoryRenderer surface).
    private BiConsumer<Mob, Item> onUse;
    private BiConsumer<Mob, Item> onThrow;
    private BiConsumer<Mob, Item> onCombine;
    /** Optional jump target — when set, the item-detail popup grows a "?"
     *  button that closes the inventory and opens the encyclopaedia
     *  pre-selected to the chosen item. */
    private V2Encyclopedia encyclopedia;

    public V2Inventory(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < equipRects.length;  i++) equipRects[i]  = new Rect();
        for (int i = 0; i < gemRects.length;    i++) gemRects[i]    = new Rect();
        for (int i = 0; i < tabRects.length;    i++) tabRects[i]    = new Rect();
        for (int i = 0; i < bindBtnRects.length; i++) bindBtnRects[i] = new Rect();
    }

    // ── Public API (mirrors V1 InventoryRenderer where applicable) ───────────
    public void setPlayer(Mob p)                          { this.player = p; }
    public void setActionBar(ActionBar ab)                { this.actionBar = ab; }
    public void setOnUse(BiConsumer<Mob, Item> fn)        { this.onUse = fn; }
    public void setOnThrow(BiConsumer<Mob, Item> fn)      { this.onThrow = fn; }
    public void setOnCombine(BiConsumer<Mob, Item> fn)    { this.onCombine = fn; }
    public void setEncyclopedia(V2Encyclopedia enc)       { this.encyclopedia = enc; }

    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else openInv(); }
    public void close() {
        open = false;
        selectedItem = null;
    }
    private void openInv() { open = true; }

    /** {@link V2Popup#renderSelf} — renders the inventory body only.
     *  The item-detail sub-popup is a SEPARATE popup actor placed on the
     *  V2 stage's {@code subPopupLayer} (one z-layer above this one), so
     *  its scrim cleanly hides the inventory's text when it's up. See
     *  {@link #detailPopup()} for the wrapper that PlayScreen registers. */
    @Override
    public void renderSelf() {
        if (!open) return;
        layoutRects();
        renderInventoryShapesPass();
        renderInventoryTextPass();
    }

    /** Companion popup for the item-detail panel — open when
     *  {@link #selectedItem} is non-null. Renders the detail chrome +
     *  text on top of the inventory popup. */
    public com.bjsp123.rl2.ui.v2.stage.V2Popup detailPopup() {
        return new com.bjsp123.rl2.ui.v2.stage.V2Popup() {
            @Override public boolean isOpen() {
                return open && selectedItem != null;
            }
            @Override public void renderSelf() {
                if (!isOpen()) return;
                // Inventory.layoutRects already runs before this in the
                // same frame (V2Inventory.renderSelf above), so the
                // detailWindow / button rects are already sized.
                renderDetailShapesPass();
                renderDetailTextPass();
            }
        };
    }

    // ── Layout ───────────────────────────────────────────────────────────────
    private void layoutRects() {
        // Outer window — fills most of the viewport but leaves margin for the
        // HUD strip at the bottom and a clear gap at the top.
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(360f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(580f, vh - 96f);
        float winX = (vw - winW) * 0.5f;
        float winY = (vh - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        float pad = 14f;
        float contentX = winX + pad;
        float contentW = winW - 2 * pad;

        // Header band at the top of the window.
        float headerH = 32f;
        headerRect.set(contentX, winY + winH - pad - headerH, contentW, headerH);

        // Equipment row — 5 cells, sized to fit the content width.
        float cellSz = 44f * 1.3f;
        float cellGap = 6f;
        float equipRowW = 5 * cellSz + 4 * cellGap;
        float equipRowX = contentX + (contentW - equipRowW) * 0.5f;
        float equipRowY = headerRect.y - 8f - cellSz;
        for (int i = 0; i < 5; i++) {
            equipRects[i].set(equipRowX + i * (cellSz + cellGap), equipRowY, cellSz, cellSz);
        }

        // Gems row — 3 cells.
        float gemRowW = 3 * cellSz + 2 * cellGap;
        float gemRowX = contentX + (contentW - gemRowW) * 0.5f;
        float gemRowY = equipRowY - 8f - cellSz;
        for (int i = 0; i < 3; i++) {
            gemRects[i].set(gemRowX + i * (cellSz + cellGap), gemRowY, cellSz, cellSz);
        }

        // Tab strip — 4 tabs spanning the content width.
        float tabH = 32f * 1.2f;
        float tabGap = 4f;
        float tabW = (contentW - (tabRects.length - 1) * tabGap) / tabRects.length;
        float tabRowY = gemRowY - 14f - tabH;
        for (int i = 0; i < tabRects.length; i++) {
            tabRects[i].set(contentX + i * (tabW + tabGap), tabRowY, tabW, tabH);
        }

        // Bag grid — 6 cols × N rows, fills the rest of the window down to
        // the bottom padding. Scrolls vertically when the filtered bag has
        // more rows than fit in the visible band. Empty grid cells are
        // included alongside filled ones so the grid reads as a uniform
        // 6-column lattice instead of a sparse list; cells with no item
        // get a {@code null} {@link BagCell#item} and render the slot
        // chrome only.
        bagCells.clear();
        int cols = 6;
        float gridCellSz = 36f * 1.3f;
        float gridGap = 4f;
        float gridW = cols * gridCellSz + (cols - 1) * gridGap;
        float gridX = contentX + (contentW - gridW) * 0.5f;
        float gridTop = tabRowY - 8f;
        float gridBottom = winY + pad;
        float visibleH = gridTop - gridBottom;
        List<Item> filtered = filteredBag();
        // Total cells = exactly the bag capacity for this tab — no more, no less.
        int tabCapacity = InventorySystem.bagLimitFor(tabCategory(currentTab));
        int totalRows   = (tabCapacity + cols - 1) / cols;
        float totalContentH = totalRows * (gridCellSz + gridGap) - gridGap;
        bagScroller.setMaxScroll(totalContentH - visibleH);
        for (int i = 0; i < tabCapacity; i++) {
            int r = i / cols;
            int c = i % cols;
            float cellTop = gridTop - r * (gridCellSz + gridGap)
                    + bagScroller.scrollY();
            float cellY   = cellTop - gridCellSz;
            if (cellY > gridTop)        continue;     // entirely above
            if (cellTop < gridBottom)   break;        // entirely below (rest are too)
            BagCell cell = new BagCell();
            cell.item = i < filtered.size() ? filtered.get(i) : null;
            cell.rect.set(gridX + c * (gridCellSz + gridGap),
                    cellY, gridCellSz, gridCellSz);
            bagCells.add(cell);
        }

        // Item-detail popup — taller now that it carries the quickslot
        // bind row above the action buttons.
        if (selectedItem != null) {
            float dW = Math.min(280f, vw - 32f);
            float dH = Math.min(440f, vh - 80f);
            detailWindow.set((vw - dW) * 0.5f, (vh - dH) * 0.5f, dW, dH);
            // Icon centred near the top of the detail window.
            float iconSz = 64f;
            detailIconRect.set(
                    detailWindow.cx() - iconSz * 0.5f,
                    detailWindow.top() - 16f - iconSz, iconSz, iconSz);
            // Three action buttons across the bottom row.
            float btnH = 38f;
            float btnGap = 6f;
            float btnW = (dW - 2 * 14f - 2 * btnGap) / 3f;
            float btnY = detailWindow.y + 14f;
            float btnX = detailWindow.x + 14f;
            detailUseBtn  .set(btnX,                              btnY, btnW, btnH);
            detailEquipBtn.set(btnX + (btnW + btnGap),            btnY, btnW, btnH);
            detailThrowBtn.set(btnX + 2 * (btnW + btnGap),        btnY, btnW, btnH);
            // Quickslot bind row — six 32×32 cells centred just above the
            // action button row. Only laid out when an action bar has been
            // wired; rendering + input both check {@link #actionBar} first.
            float bindSz = 32f;
            float bindGap = 4f;
            float bindRowW = bindBtnRects.length * bindSz
                    + (bindBtnRects.length - 1) * bindGap;
            float bindX = detailWindow.cx() - bindRowW * 0.5f;
            float bindY = btnY + btnH + 14f;
            for (int i = 0; i < bindBtnRects.length; i++) {
                bindBtnRects[i].set(bindX + i * (bindSz + bindGap),
                        bindY, bindSz, bindSz);
            }
            // "?" button at the top-right of the detail window — only laid
            // out when an encyclopedia is wired.
            float infoSz = 28f;
            detailInfoBtn.set(detailWindow.right() - 14f - infoSz,
                    detailWindow.top() - 14f - infoSz, infoSz, infoSz);

            // Pre-wrap the body — flavor (description + description2) above
            // the divider rule, mechanical details below. Lines and rule Y
            // are shared by the shape + text passes.
            detailBodyTop = detailIconRect.y - 36f;
            float bodyBottom = bindBtnRects[0].top() + 22f;
            int   maxLines   = Math.max(0,
                    (int) Math.floor((detailBodyTop - bodyBottom)
                            / detailLineH()));
            float bodyWidth  = detailWindow.w - 2 * 14f;
            String flavor  = com.bjsp123.rl2.ui.ItemLore.describeFlavor(selectedItem);
            String details = com.bjsp123.rl2.ui.ItemLore.describeDetails(selectedItem, player);
            detailFlavorLines.clear();
            detailDetailsLines.clear();
            // Cap flavor at half the body so the mechanical details block
            // (damage / armor / accuracy / evasion / speed) is never
            // crowded out by a verbose description.
            int flavorBudget = (details != null && !details.isEmpty())
                    ? Math.max(2, maxLines / 2)
                    : maxLines;
            TextDraw.wrap(ctx.fontRegular, flavor, bodyWidth, flavorBudget,
                    detailFlavorLines);
            int leftLines = maxLines - detailFlavorLines.size();
            // Reserve two line-slots for the divider gap when both halves
            // have content.
            boolean hasRule = !detailFlavorLines.isEmpty()
                    && !details.isEmpty()
                    && leftLines > 2;
            int detailMax = Math.max(0, hasRule ? leftLines - 2 : leftLines);
            TextDraw.wrap(ctx.fontRegular, details, bodyWidth, detailMax,
                    detailDetailsLines);
            detailDividerY = hasRule
                    ? detailBodyTop - detailFlavorLines.size() * detailLineH() - 4f
                    : Float.NaN;
        }
    }

    /** Item currently equipped at one of the 5 equipment slots
     *  (0..4 = weapon / offhand / armor / amulet[0] / amulet[1]); {@code null}
     *  for empty slots or out-of-range indices. */
    private Item equippedItemAt(int slot) {
        if (player == null || player.inventory == null) return null;
        return switch (slot) {
            case 0 -> player.inventory.weapon;
            case 1 -> player.inventory.offhand;
            case 2 -> player.inventory.armor;
            case 3 -> player.inventory.amulets[0];
            case 4 -> player.inventory.amulets[1];
            default -> null;
        };
    }

    /** Gem in the i-th gem slot (0..2), or {@code null}. */
    private Item gemAt(int i) {
        if (player == null || player.inventory == null) return null;
        if (i < 0 || i >= player.inventory.gems.length) return null;
        return player.inventory.gems[i];
    }

    private List<Item> filteredBag() {
        List<Item> out = new ArrayList<>();
        if (player == null || player.inventory == null) return out;
        for (Item it : player.inventory.bag) {
            if (it != null && categorize(it) == currentTab) out.add(it);
        }
        return out;
    }

    /** Maps an item to one of the four tabs. */
    private static Tab categorize(Item it) {
        if (it.isGem()) return Tab.GEMS;
        if (it.inventoryCategory == null) return Tab.ITEMS;
        return switch (it.inventoryCategory) {
            case WEAPON, OFFHAND, ARMOR, AMULET, WAND, ITEM, TOOL -> Tab.GEAR;
            case GEM                                         -> Tab.GEMS;
            case FOOD                                        -> Tab.FOOD;
            case POTION, BOMB, ORB                           -> Tab.ITEMS;
        };
    }

    /** A representative {@link Item.InventoryCategory} for each tab — used to
     *  look up the bag-capacity limit that applies to items on that tab. */
    private static Item.InventoryCategory tabCategory(Tab tab) {
        return switch (tab) {
            case GEAR  -> Item.InventoryCategory.WEAPON;
            case FOOD  -> Item.InventoryCategory.FOOD;
            case ITEMS -> Item.InventoryCategory.POTION;
            case GEMS  -> Item.InventoryCategory.GEM;
        };
    }

    // ── Render passes ───────────────────────────────────────────────────────
    /** Inventory chrome only — modal dim + outer window + equipment / gem
     *  / tab / bag-cell shapes. Detail popup chrome is a separate pass
     *  that runs AFTER the inventory's text, so the popup cleanly covers
     *  the body. */
    private void renderInventoryShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // Modal dim — half-transparent black covering the whole viewport
        // so the world below reads as backgrounded.
        s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());

        // Outer window with full chrome.
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Equipment + gem cells — all use the slot-style border.
        for (Rect r : equipRects) drawSlot(s, r);
        for (Rect r : gemRects)   drawSlot(s, r);

        // Tab strip — active tab highlighted with the accent border.
        for (int i = 0; i < tabRects.length; i++) {
            boolean active  = Tab.values()[i] == currentTab;
            boolean pressed = tabPressed[i];
            drawTab(s, tabRects[i], active, pressed);
        }

        // Bag grid cells.
        for (int i = 0; i < bagCells.size(); i++) {
            BagCell c = bagCells.get(i);
            drawSlot(s, c.rect);
            if (i == bagPressed) {
                // Pressed highlight overlay.
                s.setColor(UIVars.BTN_PRESSED_BG);
                s.rect(c.rect.x + 2, c.rect.y + 2, c.rect.w - 4, c.rect.h - 4);
            }
        }

        // Charge bars for any item that has charges (wands, frogs, hooks, blinkstones).
        for (BagCell c : bagCells) {
            if (c.item != null && c.item.baseChargeMax > 0) {
                drawWandChargeBar(s, c.rect, c.item, player);
            }
        }
        if (player != null && player.inventory != null) {
            Item[] equip = {player.inventory.weapon, player.inventory.offhand};
            Rect[] rects = {equipRects[0], equipRects[1]};
            for (int i = 0; i < equip.length; i++) {
                if (equip[i] != null && equip[i].baseChargeMax > 0) {
                    drawWandChargeBar(s, rects[i], equip[i], player);
                }
            }
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Item-detail popup chrome — drawn AFTER the inventory body so the
     *  popup's dim layer + window chrome cover any inventory text below
     *  it cleanly. Bind cells, action buttons, and the flavor / details
     *  divider also live here. */
    private void renderDetailShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // Extra dim layer on top of the inventory so the detail popup
        // reads as a true overlay, not a sibling.
        s.setColor(0f, 0f, 0f, 0.35f);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());

        Window.drawShape(ctx,
                detailWindow.x, detailWindow.y, detailWindow.w, detailWindow.h);

        // Icon backdrop slot.
        drawSlot(s, detailIconRect);

        // Quickslot bind cells — paler SLOT_BG fill so they read as
        // image-bearing slots; the slot number paints over them in
        // the text pass. Currently-bound slot border swaps to ACCENT.
        if (actionBar != null) {
            for (int i = 0; i < bindBtnRects.length; i++) {
                Rect r = bindBtnRects[i];
                boolean bound = actionBar.get(i) == selectedItem;
                boolean pressed = i == bindPressed;
                if (bound) {
                    Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W,
                            UIVars.ACCENT, UIVars.BORDER_MID,
                            UIVars.BORDER_INNER);
                } else {
                    Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
                }
                s.setColor(pressed ? UIVars.BTN_PRESSED_BG
                        : UIVars.SLOT_BG);
                s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                        r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
            }
        }

        // Action buttons.
        drawBtn(s, detailUseBtn,   detailUsePressed);
        drawBtn(s, detailEquipBtn, detailEquipPressed);
        drawBtn(s, detailThrowBtn, detailThrowPressed);
        if (encyclopedia != null) {
            drawBtn(s, detailInfoBtn, detailInfoPressed);
        }
        // Horizontal rule between the bright flavor blurb and the
        // dim mechanical-details body.
        if (!Float.isNaN(detailDividerY)) {
            s.setColor(UIVars.BORDER_MID);
            s.rect(detailWindow.x + 14f, detailDividerY,
                    detailWindow.w - 28f, 1f);
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Inventory slot — image-bearing cell, so paints with the paler
     *  SLOT_BG fill. Tri-line border keeps it visually consistent with
     *  the rest of the V2 chrome. */
    private void drawSlot(ShapeRenderer s, Rect r) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
        s.setColor(UIVars.SLOT_BG);
        s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
    }

    private static void drawWandChargeBar(ShapeRenderer s, Rect r, Item it,
                                          com.bjsp123.rl2.model.Mob player) {
        int max = com.bjsp123.rl2.logic.ItemSystem.effectiveMaxCharge(it, player);
        float pad = 4f, barH = 3f;
        float barW = r.w - 2 * pad;
        float bx = r.x + pad, by = r.y + 4f;
        s.setColor(0f, 0f, 0f, 0.85f);
        s.rect(bx - 1, by - 1, barW + 2, barH + 2);
        if (max <= 1) {
            s.setColor(0.25f, 0.25f, 0.25f, 1f);
            s.rect(bx, by, barW, barH);
            if (it.charge >= 1f) {
                s.setColor(0.2f, 0.85f, 0.3f, 1f);
                s.rect(bx, by, barW, barH);
            } else if (it.charge > 0f) {
                s.setColor(0.1f, 0.5f, 0.15f, 1f);
                s.rect(bx, by, barW * it.charge, barH);
            }
        } else {
            float slotW = (barW - (max - 1)) / max;
            for (int i = 0; i < max; i++) {
                float sx = bx + i * (slotW + 1f);
                float filled = Math.min(1f, Math.max(0f, it.charge - i));
                s.setColor(0.25f, 0.25f, 0.25f, 1f);
                s.rect(sx, by, slotW, barH);
                if (filled >= 1f) {
                    s.setColor(0.2f, 0.85f, 0.3f, 1f);
                    s.rect(sx, by, slotW, barH);
                } else if (filled > 0f) {
                    s.setColor(0.1f, 0.5f, 0.15f, 1f);
                    s.rect(sx, by, slotW * filled, barH);
                }
            }
        }
    }

    private void drawTab(ShapeRenderer s, Rect r, boolean active, boolean pressed) {
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

    private void drawBtn(ShapeRenderer s, Rect r, boolean pressed) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
        s.setColor(pressed ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
        s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
    }

    /** Inventory body text — header, equipment icons, tab icons, bag-grid
     *  icons + count badges. Drawn before the detail popup's chrome so the
     *  popup can paint cleanly over it. */
    private void renderInventoryTextPass() {
        ctx.batch.begin();

        // Header.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Backpack",
                headerRect.cx(), headerRect.y + headerRect.h * 0.75f);

        // Equipment cells — render the equipped item icons via SpriteBatch.
        if (player != null && player.inventory != null) {
            Inventory inv = player.inventory;
            drawCellIcon(equipRects[0], inv.weapon);
            drawCellIcon(equipRects[1], inv.offhand);
            drawCellIcon(equipRects[2], inv.armor);
            drawCellIcon(equipRects[3], inv.amulets[0]);
            drawCellIcon(equipRects[4], inv.amulets[1]);
            for (int i = 0; i < gemRects.length; i++) {
                drawCellIcon(gemRects[i], inv.gems[i]);
            }
        }

        // Tab icons — same source sheet as the Settings tab strip. FOOD
        // and GEMS lack dedicated icons; both fall back to OTHER for now.
        for (int i = 0; i < tabRects.length; i++) {
            boolean active = Tab.values()[i] == currentTab;
            Rect r = tabRects[i];
            var region = com.bjsp123.rl2.world.render.IconSprites
                    .regionFor(tabIcon(Tab.values()[i]));
            if (region == null) continue;
            ctx.batch.setColor(active ? UIVars.ACCENT : UIVars.TEXT_BODY);
            float sz = Math.min(r.w, r.h) * 0.65f;
            ctx.batch.draw(region,
                    r.cx() - sz * 0.5f, r.cy() - sz * 0.5f, sz, sz);
            ctx.batch.setColor(1f, 1f, 1f, 1f);
        }

        // Bag grid icons + counts.
        for (BagCell c : bagCells) {
            drawCellIcon(c.rect, c.item);
        }

        // Brand sparks — additive pass over all slots with branded items.
        if (player != null && player.inventory != null) {
            Item[] equips = {
                player.inventory.weapon, player.inventory.offhand,
                player.inventory.armor,
                player.inventory.amulets[0], player.inventory.amulets[1]
            };
            Rect[] eRects = {
                equipRects[0], equipRects[1],
                equipRects[2],
                equipRects[3], equipRects[4]
            };
            for (int i = 0; i < equips.length; i++) {
                if (equips[i] != null && equips[i].brand != null) {
                    com.bjsp123.rl2.world.render.BrandFx.drawSparks(
                            ctx.batch, ctx.whitePixel,
                            eRects[i].x, eRects[i].y, eRects[i].w, eRects[i].h,
                            equips[i].brand,
                            com.bjsp123.rl2.world.render.BrandFx.phaseFor(equips[i]));
                }
            }
        }
        for (BagCell c : bagCells) {
            if (c.item != null && c.item.brand != null) {
                com.bjsp123.rl2.world.render.BrandFx.drawSparks(
                        ctx.batch, ctx.whitePixel,
                        c.rect.x, c.rect.y, c.rect.w, c.rect.h,
                        c.item.brand,
                        com.bjsp123.rl2.world.render.BrandFx.phaseFor(c.item));
            }
        }

        ctx.batch.end();
    }

    /** Detail-popup text — icon, name, body, action labels, bind labels.
     *  Caller has already drawn the detail popup's chrome shapes, so this
     *  pass just adds the labels on top. */
    private void renderDetailTextPass() {
        ctx.batch.begin();
        if (selectedItem != null) {
            // Icon.
            TextureRegion region = ItemSprites.regionFor(selectedItem);
            if (region != null) {
                ctx.batch.draw(region,
                        detailIconRect.x + 4, detailIconRect.y + 4,
                        detailIconRect.w - 8, detailIconRect.h - 8);
            }
            if (selectedItem.brand != null) {
                com.bjsp123.rl2.world.render.BrandFx.drawSparks(
                        ctx.batch, ctx.whitePixel,
                        detailIconRect.x, detailIconRect.y,
                        detailIconRect.w, detailIconRect.h,
                        selectedItem.brand,
                        com.bjsp123.rl2.world.render.BrandFx.phaseFor(selectedItem));
            }
            // Name — up to 2 wrapped lines, centred, in header font.
            String name = com.bjsp123.rl2.logic.ItemSystem.displayName(selectedItem, player);
            if (name.isEmpty()) name = selectedItem.type != null ? selectedItem.type : "";
            java.util.List<String> nameLines = new java.util.ArrayList<>();
            TextDraw.wrap(ctx.fontHeader, name, detailWindow.w - 28f, 2, nameLines);
            float nameBaseY = detailIconRect.y - 12f;
            for (int ni = 0; ni < nameLines.size(); ni++) {
                TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, nameLines.get(ni),
                        detailWindow.cx(), nameBaseY - ni * 18f);
            }

            // Body — flavor (bright) on top, optional rule, then details
            // (dim) below. Lines + divider Y are pre-computed in
            // {@link #layoutRects()} so this pass and the shape pass agree.
            float left = detailWindow.x + 14f;
            int line = 0;
            for (String s : detailFlavorLines) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        s, left, detailBodyTop - line * detailLineH());
                line++;
            }
            if (!Float.isNaN(detailDividerY)) line += 2;
            for (String s : detailDetailsLines) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        s, left, detailBodyTop - line * detailLineH());
                line++;
            }

            // Action button labels. The "Use" button takes its label from
            // the item's CSV {@code useVerb} when set ("eat", "drink",
            // "zap", "grapple", "absorb"); falls back to "Use" for items
            // that didn't bother to specify one.
            String useLabel = (selectedItem.useVerb != null
                    && !selectedItem.useVerb.isEmpty())
                    ? capitalize(selectedItem.useVerb) : "Use";
            TextDraw.centre(ctx, ctx.fontRegular,
                    detailUsePressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    useLabel, detailUseBtn.cx(), detailUseBtn.cy() + 6f);
            String equipLabel;
            if (selectedItem.isEquippable() && player != null
                    && com.bjsp123.rl2.logic.InventorySystem
                            .isEquipped(player.inventory, selectedItem)) {
                equipLabel = "Unequip";
            } else {
                equipLabel = "Equip";
            }
            TextDraw.centre(ctx, ctx.fontRegular,
                    detailEquipPressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    equipLabel, detailEquipBtn.cx(), detailEquipBtn.cy() + 6f);
            TextDraw.centre(ctx, ctx.fontRegular,
                    detailThrowPressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    "Throw", detailThrowBtn.cx(), detailThrowBtn.cy() + 6f);
            if (encyclopedia != null) {
                // Standard info-icon button — same INFO glyph the
                // perks-tab info buttons use, so info affordances read
                // identically across V2 surfaces.
                var iregion = com.bjsp123.rl2.world.render.IconSprites
                        .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.INFO);
                if (iregion != null) {
                    ctx.batch.setColor(detailInfoPressed
                            ? UIVars.ACCENT : UIVars.TEXT_BODY);
                    float isz = Math.min(detailInfoBtn.w, detailInfoBtn.h) * 0.6f;
                    ctx.batch.draw(iregion,
                            detailInfoBtn.cx() - isz * 0.5f,
                            detailInfoBtn.cy() - isz * 0.5f, isz, isz);
                    ctx.batch.setColor(1f, 1f, 1f, 1f);
                }
            }

            // Quickslot bind labels — slot number 1..6 + "Quickslot" header.
            if (actionBar != null) {
                TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        "Quickslot",
                        detailWindow.cx(),
                        bindBtnRects[0].top() + 16f);
                for (int i = 0; i < bindBtnRects.length; i++) {
                    Rect r = bindBtnRects[i];
                    boolean bound = actionBar.get(i) == selectedItem;
                    TextDraw.centre(ctx, ctx.fontRegular,
                            bound ? UIVars.ACCENT : UIVars.TEXT_BODY,
                            Integer.toString(i + 1),
                            r.cx(), r.cy() + 6f);
                }
            }
        }

        ctx.batch.end();
    }


    /** Draw {@code item}'s sprite icon centred inside {@code cell}. Caller
     *  is in the SpriteBatch pass. */
    /** Title-case the first letter of {@code s} for use as a button label
     *  ("eat" → "Eat", "zap" → "Zap"). Returns the input unchanged when
     *  it's null or empty. */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void drawCellIcon(Rect cell, Item item) {
        ItemCell.draw(ctx, item, player, cell.x, cell.y, cell.w, cell.h, true);
    }

    // ── Input ───────────────────────────────────────────────────────────────
    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Item-detail popup intercepts everything when up.
                if (selectedItem != null) {
                    if (encyclopedia != null
                            && detailInfoBtn.contains(vx, vy)) {
                        detailInfoPressed = true;
                        return true;
                    }
                    if (actionBar != null) {
                        for (int i = 0; i < bindBtnRects.length; i++) {
                            if (bindBtnRects[i].contains(vx, vy)) {
                                bindPressed = i;
                                return true;
                            }
                        }
                    }
                    if (detailUseBtn.contains(vx, vy))   { detailUsePressed = true;   return true; }
                    if (detailEquipBtn.contains(vx, vy)) { detailEquipPressed = true; return true; }
                    if (detailThrowBtn.contains(vx, vy)) { detailThrowPressed = true; return true; }
                    // Tap outside the detail window closes it without firing anything.
                    if (!detailWindow.contains(vx, vy)) {
                        selectedItem = null;
                        return true;
                    }
                    return true;   // any tap inside the detail window is consumed
                }

                // Arm the scroller so any subsequent touchDragged knows the
                // starting Y, preventing stale dragLastY=0 from making the
                // first drag-check trip on a clean tap (Android always fires
                // touchDragged even for stationary finger presses).
                bagScroller.onTouchDown(vy);

                // Tabs.
                for (int i = 0; i < tabRects.length; i++) {
                    if (tabRects[i].contains(vx, vy)) {
                        tabPressed[i] = true;
                        return true;
                    }
                }
                // Equipment + gem cells — tappable iff currently equipped.
                for (int i = 0; i < equipRects.length; i++) {
                    if (equipRects[i].contains(vx, vy)
                            && equippedItemAt(i) != null) {
                        equipPressed = i;
                        return true;
                    }
                }
                for (int i = 0; i < gemRects.length; i++) {
                    if (gemRects[i].contains(vx, vy)
                            && gemAt(i) != null) {
                        gemPressed = i;
                        return true;
                    }
                }
                // Bag grid cells.
                for (int i = 0; i < bagCells.size(); i++) {
                    if (bagCells.get(i).rect.contains(vx, vy)) {
                        bagPressed = i;
                        bagScroller.onTouchDown(vy);
                        return true;
                    }
                }
                // Tap outside the window closes the inventory.
                if (!window.contains(vx, vy)) {
                    close();
                    return true;
                }
                // Touch landed inside the window but missed every cell —
                // still arm the scroller so a drag from this point scrolls
                // the bag grid.
                bagScroller.onTouchDown(vy);
                return true;   // any tap inside the inventory frame is consumed
            }

            @Override
            public boolean touchDragged(int sx, int sy, int pointer) {
                if (!open || selectedItem != null) return false;
                float vy = ctx.unprojectY(sx, sy);
                if (bagScroller.onTouchDragged(vy)) {
                    // Drag classified — cancel any pending tap so release
                    // doesn't fire a row click.
                    bagPressed = -1;
                    equipPressed = -1;
                    gemPressed = -1;
                    for (int i = 0; i < tabPressed.length; i++) tabPressed[i] = false;
                    return true;
                }
                return false;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (!open || selectedItem != null) return false;
                bagScroller.onScrolled(amountY, 40f);
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Quickslot bind / unbind.
                if (bindPressed >= 0) {
                    int idx = bindPressed;
                    bindPressed = -1;
                    if (actionBar != null
                            && idx < bindBtnRects.length
                            && bindBtnRects[idx].contains(vx, vy)) {
                        // Toggle — tap again to unbind.
                        if (actionBar.get(idx) == selectedItem) {
                            actionBar.set(idx, null);
                        } else {
                            actionBar.set(idx, selectedItem);
                        }
                    }
                    return true;
                }

                // Encyclopedia "?" jump. Closes the inventory, opens the
                // encyclopaedia at this item's page, and registers a
                // back-stack callback so when the encyclopaedia closes the
                // inventory re-opens with the same item-detail panel up.
                if (detailInfoPressed) {
                    detailInfoPressed = false;
                    if (encyclopedia != null
                            && detailInfoBtn.contains(vx, vy)
                            && selectedItem != null) {
                        Object id = selectedItem.type;
                        Item rememberItem = selectedItem;
                        selectedItem = null;
                        close();
                        encyclopedia.openTo(id, () -> {
                            open = true;
                            selectedItem = rememberItem;
                        });
                    }
                    return true;
                }

                // Item-detail popup actions. Each commits the action AND
                // closes the inventory entirely so the player drops back
                // to the world view to see the consequence (item used,
                // throw target picker up, etc.).
                if (detailUsePressed) {
                    detailUsePressed = false;
                    if (detailUseBtn.contains(vx, vy)) {
                        Item it = selectedItem;
                        selectedItem = null;
                        close();
                        if (onUse != null && player != null && it != null) {
                            onUse.accept(player, it);
                        }
                    }
                    return true;
                }
                if (detailEquipPressed) {
                    detailEquipPressed = false;
                    if (detailEquipBtn.contains(vx, vy)
                            && player != null && selectedItem != null
                            && selectedItem.isEquippable()) {
                        Item it = selectedItem;
                        if (com.bjsp123.rl2.logic.InventorySystem
                                .isEquipped(player.inventory, it)) {
                            com.bjsp123.rl2.logic.InventorySystem
                                    .unequip(player.inventory, it);
                        } else {
                            com.bjsp123.rl2.logic.InventorySystem
                                    .equip(player.inventory, it);
                        }
                        selectedItem = null;
                        close();
                    }
                    return true;
                }
                if (detailThrowPressed) {
                    detailThrowPressed = false;
                    if (detailThrowBtn.contains(vx, vy)) {
                        Item it = selectedItem;
                        selectedItem = null;
                        close();
                        if (onThrow != null && player != null && it != null) {
                            onThrow.accept(player, it);
                        }
                    }
                    return true;
                }

                // Tab switch.
                for (int i = 0; i < tabRects.length; i++) {
                    if (tabPressed[i]) {
                        tabPressed[i] = false;
                        if (tabRects[i].contains(vx, vy)
                                && currentTab != Tab.values()[i]) {
                            currentTab = Tab.values()[i];
                            bagScroller.resetTop();
                        }
                        return true;
                    }
                }
                // Equipment-cell tap → open item-detail popup.
                if (equipPressed >= 0) {
                    int idx = equipPressed;
                    equipPressed = -1;
                    if (idx < equipRects.length
                            && equipRects[idx].contains(vx, vy)) {
                        selectedItem = equippedItemAt(idx);
                    }
                    return true;
                }
                // Gem-cell tap → open item-detail popup.
                if (gemPressed >= 0) {
                    int idx = gemPressed;
                    gemPressed = -1;
                    if (idx < gemRects.length
                            && gemRects[idx].contains(vx, vy)) {
                        selectedItem = gemAt(idx);
                    }
                    return true;
                }
                // Bag-cell tap → open item-detail popup.
                if (bagPressed >= 0) {
                    int idx = bagPressed;
                    bagPressed = -1;
                    if (idx < bagCells.size()
                            && bagCells.get(idx).rect.contains(vx, vy)) {
                        selectedItem = bagCells.get(idx).item;
                    }
                    return true;
                }
                // Any other release inside the inventory frame is consumed
                // so taps don't leak to the world below.
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    if (selectedItem != null) selectedItem = null;
                    else close();
                    return true;
                }
                return false;
            }
        };
    }

    /** Local convenience holder — one cell's rect + which Item it represents. */
    private static final class BagCell {
        final Rect rect = new Rect();
        Item item;
    }
}
