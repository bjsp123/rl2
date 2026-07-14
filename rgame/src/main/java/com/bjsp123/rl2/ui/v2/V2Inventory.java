package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.InventorySystem;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.ui.hud.ActionBar;
import com.bjsp123.rl2.ui.skin.Settings;
import com.bjsp123.rl2.world.render.DefaultLevelRenderer;
import com.bjsp123.rl2.world.render.ItemSprites;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * V2 inventory popup - primitive-drawn modal window covering the bulk of the
 * viewport. Replaces the retired scene2d-based inventory renderer
 * for the in-game backpack experience.
 *
 * <p>Layout (top-down inside the outer window):
 * <ol>
 *   <li>Header label ("Backpack")</li>
 *   <li>Equipment row - five fixed cells: weapon, offhand, armor, amulet 0, amulet 1</li>
 *   <li>Tab strip - Gear / Food / Items / Gems</li>
 *   <li>Bag grid - 6 cols x N rows of free-form items in the current tab</li>
 * </ol>
 *
 * <p>Tapping any cell that holds an item opens a smaller item-detail popup
 * window centred over the inventory, with Use / Equip / Throw action buttons
 * and a close-on-outside-tap behaviour.
 *
 * <p>Render lifecycle: the screen calls {@link #render()} after the V2 HUD
 * pass so the popup overlays the HUD. Input is captured via
 * {@link #input()} - when {@link #isOpen()} returns false the processor
 * passes through without consuming.
 */
public final class V2Inventory implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private enum Tab { GEAR, CONSUMABLE, ITEMS, GEMS }

    /** Map each tab to a glyph in the shared UI icon sheet. The CONSUMABLE tab
     *  (food + potions) reuses the FOOD glyph. */
    private static com.bjsp123.rl2.world.render.IconSprites.Icon tabIcon(Tab t) {
        return switch (t) {
            case GEAR       -> com.bjsp123.rl2.world.render.IconSprites.Icon.EQUIPMENT;
            case CONSUMABLE -> com.bjsp123.rl2.world.render.IconSprites.Icon.FOOD;
            case ITEMS      -> com.bjsp123.rl2.world.render.IconSprites.Icon.ITEMS;
            case GEMS       -> com.bjsp123.rl2.world.render.IconSprites.Icon.GEMS;
        };
    }

    // -- State ---------------------------------------------------------------
    private final UiCtx ctx;
    private boolean open;
    private Mob player;
    private ActionBar actionBar;
    private Tab currentTab = Tab.GEAR;

    /** Item the user has tapped - non-null while the item-detail popup is up. */
    private Item selectedItem;

    // Hit rects for the main popup.
    private final Rect window         = new Rect();
    private final Rect headerRect     = new Rect();
    private final Rect[] equipRects   = new Rect[5];
    /** Shared tab-strip widget - layout, chrome, and press state. */
    private final TabStrip tabs       = new TabStrip(Tab.values().length);
    /** Bag grid cells - built each frame from the player's bag, filtered by tab. */
    private final List<BagCell> bagCells = new ArrayList<>();

    // Item-detail popup rects.
    private final Rect detailWindow   = new Rect();
    private final Rect detailIconRect = new Rect();
    private final Rect detailUseBtn   = new Rect();
    private final Rect detailEquipBtn = new Rect();
    private final Rect detailThrowBtn = new Rect();
    /** Encyclopedia "?" jump button - top-right of the detail window. */
    private final Rect detailInfoBtn  = new Rect();
    /** Pre-wrapped flavor / details lines for the current item-detail popup -
     *  populated in {@link #layoutRects()} so the shape pass (which paints
     *  the divider rule) and the text pass (which paints the lines) agree
     *  on the layout. */
    private final List<String> detailFlavorLines  = new ArrayList<>();
    private final List<String> detailDetailsLines = new ArrayList<>();
    private TextDraw.TextBlock detailNameBlock =
            TextDraw.block(null, "", 1f, 0, 0f);
    /** Y of the horizontal rule between flavor and details (virtual pixels)
     *  or {@code Float.NaN} when one half is empty and no rule is drawn. */
    private float detailDividerY = Float.NaN;
    /** Top of the body text region (the y of the first flavor line). */
    private float detailBodyTop;
    /** Quickslot-binding buttons - one numbered cell per quickslot
     *  ({@code Settings.quickslotCount()}) in the detail popup that
     *  bind / unbind the chosen item to / from each action-bar slot.
     *  Built only when an {@link ActionBar} has been wired. */
    private final Rect[] bindBtnRects = new Rect[ActionBar.SLOTS];

    // Pressed state.
    /** Index into {@link #bagCells} of the cell currently being held, or -1. */
    private int bagPressed = -1;
    /** Index into {@link #equipRects} (0..4) of the equipment cell being
     *  held, or -1. Equipment cells share the bag-cell click semantics -
     *  tap fills {@link #selectedItem} and opens the detail popup, where
     *  Use / Throw fire on the equipped item. */
    private int equipPressed = -1;
    private boolean detailUsePressed, detailEquipPressed, detailThrowPressed;
    private boolean detailInfoPressed;
    /** Index of the bind-button (0..5) currently held, or -1. */
    private int bindPressed = -1;

    /** Bag-grid scroll band - used when the player's filtered bag has more
     *  items than fit in the visible grid area. Touch drag on the grid
     *  scrolls; mouse wheel scrolls; tab switches reset to top. The band
     *  scissor-clips the cell drawing so partial rows render at the edges. */
    private final ScrollBand bagBand = new ScrollBand();

    // Callbacks (same surface the retired scene2d inventory renderer had).
    private BiConsumer<Mob, Item> onUse;
    private BiConsumer<Mob, Item> onThrow;
    private BiConsumer<Mob, Item> onDrop;
    /** The item a Drop press has been armed for. Drop is a two-tap confirm (the
     *  button shares the Throw slot, so throw muscle-memory would otherwise
     *  discard gear on one stray tap): the first tap arms this, the second drops.
     *  Keyed by the item so switching selection re-requires a fresh confirm. */
    private Item dropArmedFor;
    /** Optional jump target - when set, the item-detail popup grows a "?"
     *  button that closes the inventory and opens the encyclopaedia
     *  pre-selected to the chosen item. */
    private V2Encyclopedia encyclopedia;

    private com.bjsp123.rl2.audio.SoundManager sounds;

    /** Picker mode (e.g. an enchant scroll choosing its target). When
     *  {@link #pickEligible} is non-null the inventory opens as a chooser:
     *  eligible items stay lit and tap-to-pick, ineligible ones grey out and
     *  ignore taps, and the detail popup never opens. A pick fires
     *  {@link #onPick}; any other close fires {@link #onPickCancel}. */
    private java.util.function.Predicate<Item> pickEligible;
    private BiConsumer<Mob, Item> onPick;
    private Runnable onPickCancel;

    public V2Inventory(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < equipRects.length;  i++) equipRects[i]  = new Rect();
        for (int i = 0; i < bindBtnRects.length; i++) bindBtnRects[i] = new Rect();
    }

    // -- Public API ------------------------------------------------------------
    public void setPlayer(Mob p)                          { this.player = p; }
    public void setActionBar(ActionBar ab)                { this.actionBar = ab; }
    public void setOnUse(BiConsumer<Mob, Item> fn)        { this.onUse = fn; }
    public void setOnThrow(BiConsumer<Mob, Item> fn)      { this.onThrow = fn; }
    public void setOnDrop(BiConsumer<Mob, Item> fn)       { this.onDrop = fn; }
    public void setSounds(com.bjsp123.rl2.audio.SoundManager s) { this.sounds = s; }
    public void setEncyclopedia(V2Encyclopedia enc)       { this.encyclopedia = enc; }

    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else openInv(); }
    public void close() {
        // A close while a pick is still pending counts as a cancel - a
        // successful pick clears the picker via endPicker() first, so reaching
        // here with pickEligible still set means the chooser was dismissed.
        if (pickEligible != null) {
            Runnable cancel = onPickCancel;
            endPicker();
            open = false;
            selectedItem = null;
            dropArmedFor = null;
            if (cancel != null) cancel.run();
            return;
        }
        open = false;
        selectedItem = null;
        dropArmedFor = null;
    }
    private void openInv() {
        open = true;
        if (sounds != null) sounds.play("sfx.ui.popup.inventory");
    }

    /** Open the inventory with the item-detail popup (use / throw / drop)
     *  already showing {@code it} - the long-press gesture on a quickslot or
     *  bag cell jumps straight to the item's actions. No-op during a picker
     *  session (the chooser owns the popup then). */
    public void openDetail(Item it) {
        if (it == null || pickMode()) return;
        if (!open) openInv();
        selectedItem = it;
    }

    /** Open the inventory as a target chooser. {@code eligible} lights the
     *  tappable items (the rest grey out); {@code onPick} fires with the chosen
     *  item, {@code onCancel} fires if the chooser is dismissed without a pick. */
    public void openPicker(java.util.function.Predicate<Item> eligible,
                           BiConsumer<Mob, Item> onPick, Runnable onCancel) {
        this.pickEligible = eligible;
        this.onPick = onPick;
        this.onPickCancel = onCancel;
        this.selectedItem = null;
        this.currentTab = Tab.GEAR;   // enchant targets live in the gear bag
        bagBand.scroller.resetTop();
        open = true;
        if (sounds != null) sounds.play("sfx.ui.popup.inventory");
    }

    /** As {@link #openPicker} but landing on the Gems tab - the hearth's
     *  gem-conversion picker chooses among raw gems, not gear. */
    public void openGemPicker(java.util.function.Predicate<Item> eligible,
                              BiConsumer<Mob, Item> onPick, Runnable onCancel) {
        openPicker(eligible, onPick, onCancel);
        this.currentTab = Tab.GEMS;
    }

    private boolean pickMode() { return pickEligible != null; }

    private void endPicker() {
        pickEligible = null;
        onPick = null;
        onPickCancel = null;
    }

    /** Commit a pick on {@code it} if it's eligible; ineligible taps are
     *  swallowed so the chooser stays open. */
    private void tryPick(Item it) {
        if (it == null || pickEligible == null || !pickEligible.test(it)) return;
        BiConsumer<Mob, Item> cb = onPick;
        endPicker();
        close();
        if (cb != null && player != null) cb.accept(player, it);
    }

    /** {@link V2Popup#renderSelf} - renders the inventory body only.
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
        renderPickerDimPass();
    }

    /** Companion popup for the item-detail panel - open when
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

    // -- Layout ---------------------------------------------------------------
    private void layoutRects() {
        // Outer window - fills most of the viewport but leaves margin for the
        // HUD strip at the bottom and a clear gap at the top.
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(400f, vw - UIVars.PAD_MODAL);
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

        // Equipment row - 5 cells, sized to fit the content width.
        float cellSz = 44f * 1.43f * 1.1f;
        float cellGap = 6f;
        float equipRowW = 5 * cellSz + 4 * cellGap;
        float equipRowX = contentX + (contentW - equipRowW) * 0.5f;
        float equipRowY = headerRect.y - 8f - cellSz;
        for (int i = 0; i < 5; i++) {
            equipRects[i].set(equipRowX + i * (cellSz + cellGap), equipRowY, cellSz, cellSz);
        }

        // (Gem equip slots removed - gems/scrolls are ordinary bag items now.)

        // Tab strip - 4 tabs spanning the content width.
        float tabH = 32f * 1.2f;
        float tabGap = 4f;
        float tabRowY = equipRowY - 14f - tabH;
        tabs.layout(window, pad, tabRowY, tabH, tabGap);
        tabs.setActive(currentTab.ordinal());

        // Bag grid - N cols x M rows, fills the rest of the window down to
        // the bottom padding. Scrolls vertically when the filtered bag has
        // more rows than fit in the visible band. Empty grid cells are
        // included alongside filled ones so the grid reads as a uniform
        // lattice instead of a sparse list; cells with no item get a
        // {@code null} {@link BagCell#item} and render the slot chrome only.
        bagCells.clear();
        int cols = 5;
        float gridCellSz = 40f * 1.43f * 1.1f;
        float gridGap = 4f;
        float gridW = cols * gridCellSz + (cols - 1) * gridGap;
        float gridX = contentX + (contentW - gridW) * 0.5f;
        float gridTop = tabRowY - 8f;
        float gridBottom = winY + pad;
        List<Item> filtered = filteredBag();
        // Total cells = exactly the bag capacity for this tab - no more, no less.
        int tabCapacity = InventorySystem.bagLimitFor(tabCategory(currentTab));
        // Band scissor-clips the cell drawing, so partially-visible rows
        // render at the band edges instead of popping in/out whole rows.
        bagBand.set(gridX, gridBottom, gridW, gridTop - gridBottom);
        bagBand.update(bagContentH(gridCellSz, gridGap, cols, tabCapacity));
        for (int i = 0; i < tabCapacity; i++) {
            int r = i / cols;
            int c = i % cols;
            float cellTop = gridTop - r * (gridCellSz + gridGap)
                    + bagBand.scroller.scrollY();
            float cellY   = cellTop - gridCellSz;
            if (cellY >= gridTop)       continue;     // entirely above
            if (cellTop <= gridBottom)  break;        // entirely below (rest are too)
            BagCell cell = new BagCell();
            cell.item = i < filtered.size() ? filtered.get(i) : null;
            cell.rect.set(gridX + c * (gridCellSz + gridGap),
                    cellY, gridCellSz, gridCellSz);
            bagCells.add(cell);
        }

        // Item-detail popup - taller now that it carries the quickslot
        // bind row above the action buttons.
        if (selectedItem != null) {
            float dW = Math.min(300f, vw - 32f);
            float bodyWidth = dW - 2 * 14f;

            String name = com.bjsp123.rl2.logic.ItemNames.displayName(selectedItem, player);
            if (name.isEmpty()) name = selectedItem.type != null ? selectedItem.type : "";
            // Names are stored lowercase; the detail heading renders Title Case.
            detailNameBlock = TextDraw.block(ctx.fontHeader,
                    com.bjsp123.rl2.logic.TextCatalog.titleCase(name),
                    dW - 28f, 2, ctx.headerLineH());

            String flavor  = com.bjsp123.rl2.ui.ItemLore.describeFlavor(selectedItem);
            String details = com.bjsp123.rl2.ui.ItemLore.describeDetails(selectedItem, player);
            int wantedLines = wantedDetailLines(flavor, details, bodyWidth);

            float dH = Math.min(detailHeightFor(wantedLines),
                    Math.max(360f, vh - 32f));
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
            // Quickslot bind row - one cell per quickslot (count is the player
            // setting, 4..12), centred just above the action button row. Cells
            // are 32 px but shrink to fit the window width when there are many
            // (so 12 slots stay on one row). Only laid out when an action bar
            // has been wired; rendering + input both check {@link #actionBar} first.
            int bindN = Settings.quickslotCount();
            float bindGap = 4f;
            float bindAvailW = detailWindow.w - 28f;
            float bindSz = Math.min(32f, (bindAvailW - (bindN - 1) * bindGap) / bindN);
            if (bindSz < 18f) bindSz = 18f;
            float bindRowW = bindN * bindSz + (bindN - 1) * bindGap;
            float bindX = detailWindow.cx() - bindRowW * 0.5f;
            float bindY = btnY + btnH + 14f;
            for (int i = 0; i < bindN; i++) {
                bindBtnRects[i].set(bindX + i * (bindSz + bindGap),
                        bindY, bindSz, bindSz);
            }
            // "?" button at the top-right of the detail window - only laid
            // out when an encyclopedia is wired.
            float infoSz = 28f;
            detailInfoBtn.set(detailWindow.right() - 14f - infoSz,
                    detailWindow.top() - 14f - infoSz, infoSz, infoSz);

            // Pre-wrap the body - flavor (description + description2) above
            // the divider rule, mechanical details below. Lines and rule Y
            // are shared by the shape + text passes.
            float nameTop = detailIconRect.y - 12f;
            detailBodyTop = nameTop - detailNameBlock.height() - 8f;
            float bodyBottom = bindBtnRects[0].top() + 22f;
            int   maxLines   = Math.max(0,
                    (int) Math.floor((detailBodyTop - bodyBottom)
                            / ctx.lineH()));
            detailFlavorLines.clear();
            detailDetailsLines.clear();
            // Cap flavor at half the body so the mechanical details block
            // (damage / armor / accuracy / evasion / speed) is never
            // crowded out by a verbose description.
            int flavorBudget = (details != null && !details.isEmpty())
                    ? Math.max(2, maxLines / 2)
                    : maxLines;
            TextDraw.TextBlock flavorBlock = TextDraw.block(ctx.fontRegular,
                    flavor, bodyWidth, flavorBudget, ctx.lineH());
            detailFlavorLines.addAll(flavorBlock.lines);
            int leftLines = maxLines - detailFlavorLines.size();
            // Reserve two line-slots for the divider gap when both halves
            // have content.
            boolean hasRule = !detailFlavorLines.isEmpty()
                    && !details.isEmpty()
                    && leftLines > 2;
            int detailMax = Math.max(0, hasRule ? leftLines - 2 : leftLines);
            TextDraw.TextBlock detailsBlock = TextDraw.block(ctx.fontRegular,
                    details, bodyWidth, detailMax, ctx.lineH());
            detailDetailsLines.addAll(detailsBlock.lines);
            detailDividerY = hasRule
                    ? detailBodyTop - detailFlavorLines.size() * ctx.lineH() - 4f
                    : Float.NaN;
        }
    }

    private int wantedDetailLines(String flavor, String details, float bodyWidth) {
        int flavorLines = lineCountFor(flavor, bodyWidth);
        int detailsLines = lineCountFor(details, bodyWidth);
        int ruleSlots = flavorLines > 0 && detailsLines > 0 ? 2 : 0;
        return Math.min(24, flavorLines + detailsLines + ruleSlots);
    }

    private int lineCountFor(String text, float bodyWidth) {
        if (text == null || text.isEmpty()) return 0;
        TextDraw.TextBlock block = TextDraw.block(ctx.fontRegular, text,
                bodyWidth, Integer.MAX_VALUE, ctx.lineH());
        return block.lineCount();
    }

    private float detailHeightFor(int bodyLines) {
        // Top icon + title band, body text, divider breathing room, quickslot
        // row, and action buttons. The clamp keeps ordinary one-line items
        // from expanding into a huge mostly-empty panel.
        float fixedH = 220f + detailNameBlock.height();
        return Math.max(440f, fixedH + bodyLines * ctx.lineH());
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

    private List<Item> filteredBag() {
        List<Item> out = new ArrayList<>();
        if (player == null || player.inventory == null) return out;
        // Bag is appended-to on pickup, so iteration order = acquisition
        // order. Walk in reverse so the most-recently-acquired item appears
        // at the top-left of the grid - matches the user's expectation
        // that the freshest loot is the first thing they see.
        java.util.List<Item> raw = player.inventory.bag;
        for (int i = raw.size() - 1; i >= 0; i--) {
            Item it = raw.get(i);
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
            case FOOD, POTION                                -> Tab.CONSUMABLE;
            case BOMB, ORB, THROWN                           -> Tab.ITEMS;
        };
    }

    /** Total stacked height of the bag grid's rows - drives the scroll
     *  clamp and the scrollbar thumb size. */
    private static float bagContentH(float cellSz, float gap, int cols,
                                     int tabCapacity) {
        int totalRows = (tabCapacity + cols - 1) / cols;
        return totalRows * (cellSz + gap) - gap;
    }

    /** A representative {@link Item.InventoryCategory} for each tab - used to
     *  look up the bag-capacity limit that applies to items on that tab. */
    private static Item.InventoryCategory tabCategory(Tab tab) {
        return switch (tab) {
            case GEAR       -> Item.InventoryCategory.WEAPON;
            case CONSUMABLE -> Item.InventoryCategory.FOOD;
            case ITEMS      -> Item.InventoryCategory.BOMB;
            case GEMS       -> Item.InventoryCategory.GEM;
        };
    }

    // -- Render passes -------------------------------------------------------
    /** Inventory chrome only - modal dim + outer window + equipment / gem
     *  / tab / bag-cell shapes. Detail popup chrome is a separate pass
     *  that runs AFTER the inventory's text, so the popup cleanly covers
     *  the body. */
    private void renderInventoryShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // Modal dim - half-transparent black covering the whole viewport
        // so the world below reads as backgrounded.
        s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());

        // Outer window with full chrome.
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Equipment cells use the slot-style border.
        for (Rect r : equipRects) drawSlot(s, r);

        // Tab strip - active tab highlighted with the accent border.
        tabs.drawShapes(s);

        // Bag grid cells + charge bars, scissor-clipped to the visible band.
        bagBand.clip(ctx, () -> {
            for (int i = 0; i < bagCells.size(); i++) {
                BagCell c = bagCells.get(i);
                drawSlot(s, c.rect);
                if (i == bagPressed) {
                    // Pressed highlight overlay.
                    s.setColor(UIVars.BTN_PRESSED_BG);
                    s.rect(c.rect.x + 2, c.rect.y + 2, c.rect.w - 4, c.rect.h - 4);
                }
            }
            // Charge bars for any item that has charges (wands, frogs,
            // hooks, blinkstones).
            for (BagCell c : bagCells) {
                if (c.item != null && c.item.baseChargeMax > 0) {
                    drawWandChargeBar(s, c.rect, c.item, player);
                }
            }
        });
        // Shared scrollbar affordance on the right edge of the grid.
        bagBand.drawScrollbar(s, bagBand.scroller.maxScroll() + bagBand.height());
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

    /** Item-detail popup chrome - drawn AFTER the inventory body so the
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
        s.setColor(0f, 0f, 0f, UIVars.SUBPOPUP_DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());

        Window.drawShape(ctx,
                detailWindow.x, detailWindow.y, detailWindow.w, detailWindow.h);

        // Icon backdrop slot.
        drawSlot(s, detailIconRect);

        // Quickslot bind cells - paler SLOT_BG fill so they read as
        // image-bearing slots; the slot number paints over them in
        // the text pass. Currently-bound slot border swaps to ACCENT.
        if (actionBar != null) {
            for (int i = 0; i < Settings.quickslotCount(); i++) {
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
                // Shared vocabulary: the slot this item is bound to reads as
                // active (darker fill + accent border above); the rest as
                // inactive grey.
                s.setColor(pressed ? UIVars.BTN_PRESSED_BG
                        : (bound ? UIVars.BTN_BG : UIVars.BTN_DISABLED_BG));
                s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                        r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
            }
        }

        // Action buttons. Each greys out when it doesn't apply to the selected
        // item (e.g. Equip on a non-equippable blinkstone, Throw on a wand).
        drawBtn(s, detailUseBtn,   detailUsePressed,   canUseSelected());
        drawBtn(s, detailEquipBtn, detailEquipPressed, canEquipSelected());
        // Third slot is Throw (enabled when throwable) or Drop (always enabled).
        drawBtn(s, detailThrowBtn, detailThrowPressed, dropModeSelected() || canThrowSelected());
        if (encyclopedia != null) {
            drawBtn(s, detailInfoBtn, detailInfoPressed, true);
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

    /** Inventory slot - image-bearing cell, so paints with the paler
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
        int max = com.bjsp123.rl2.logic.ItemStats.effectiveMaxCharge(it,
                com.bjsp123.rl2.logic.ItemStats.effectiveLevel(it, player));
        ChargeBar.draw(max, it.charge, r.x, r.y, r.w,
                (c, x, y, w, h) -> { s.setColor(c); s.rect(x, y, w, h); });
    }

    private void drawBtn(ShapeRenderer s, Rect r, boolean pressed, boolean enabled) {
        ButtonChrome.shapeEnabled(ctx, r, pressed, enabled);
    }

    /** Whether each detail-popup action applies to the selected item. Drive
     *  both the greyed-out chrome/label and the press gate so a disabled button
     *  is fully inert. */
    private boolean canUseSelected()   { return selectedItem != null && selectedItem.isUsable(); }
    private boolean canEquipSelected() { return selectedItem != null && selectedItem.isEquippable(); }
    private boolean canThrowSelected() { return selectedItem != null && selectedItem.isThrowable(); }
    /** The third action button is a Drop button (always enabled) instead of Throw
     *  whenever the selected item can't be thrown. */
    private boolean dropModeSelected() { return selectedItem != null && !selectedItem.isThrowable(); }

    /** Inventory body text - header, equipment icons, tab icons, bag-grid
     *  icons + count badges. Drawn before the detail popup's chrome so the
     *  popup can paint cleanly over it. */
    private void renderInventoryTextPass() {
        ctx.batch.begin();

        // Header. In picker mode it prompts the choice instead of the title.
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get(pickMode() ? "ui.inventory.choose" : "ui.inventory.title"),
                headerRect.cx(), headerRect.y + headerRect.h * 0.75f);

        // Equipment cells - render the equipped item icons via SpriteBatch.
        if (player != null && player.inventory != null) {
            Inventory inv = player.inventory;
            drawCellIcon(equipRects[0], inv.weapon);
            drawCellIcon(equipRects[1], inv.offhand);
            drawCellIcon(equipRects[2], inv.armor);
            drawCellIcon(equipRects[3], inv.amulets[0]);
            drawCellIcon(equipRects[4], inv.amulets[1]);
        }

        // Tab icons - same source sheet as the Settings tab strip.
        Tab[] tabVals = Tab.values();
        var tabIcons = new TextureRegion[tabVals.length];
        for (int i = 0; i < tabVals.length; i++) {
            tabIcons[i] = com.bjsp123.rl2.world.render.IconSprites
                    .regionFor(tabIcon(tabVals[i]));
        }
        tabs.drawIcons(ctx, tabIcons);

        // Bag grid icons + counts, clipped to the same band as the chrome.
        bagBand.clip(ctx, () -> {
            for (BagCell c : bagCells) {
                drawCellIcon(c.rect, c.item);
            }
        });

        ctx.batch.end();
    }

    /** Picker-mode overlay: grey out every item cell whose item fails the
     *  eligibility test, drawn AFTER the icon pass so the dim sits on top of
     *  the sprite. Empty cells and eligible items are left bright. */
    private void renderPickerDimPass() {
        if (!pickMode() || player == null || player.inventory == null) return;
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        Inventory inv = player.inventory;
        Item[] equip = {inv.weapon, inv.offhand, inv.armor, inv.amulets[0], inv.amulets[1]};
        for (int i = 0; i < equipRects.length; i++) dimIfIneligible(s, equipRects[i], equip[i]);
        bagBand.clip(ctx, () -> {
            for (BagCell c : bagCells) dimIfIneligible(s, c.rect, c.item);
        });
        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Dim {@code r} when it holds an item that the active picker rejects. */
    private void dimIfIneligible(ShapeRenderer s, Rect r, Item it) {
        if (it == null) return;                       // empty cell: leave as-is
        if (pickEligible != null && pickEligible.test(it)) return;  // eligible: bright
        s.setColor(0f, 0f, 0f, UIVars.PICKER_DIM_ALPHA);
        s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
    }

    /** Detail-popup text - icon, name, body, action labels, bind labels.
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
            com.bjsp123.rl2.world.render.BrandFx.drawItemSparks(
                    ctx.batch, ctx.whitePixel,
                    detailIconRect.x, detailIconRect.y,
                    detailIconRect.w, detailIconRect.h,
                    selectedItem);
            TextDraw.wrappedCentre(ctx, ctx.fontHeader, UIVars.ACCENT,
                    detailNameBlock, detailWindow.cx(), detailIconRect.y - 12f);

            // Body - flavor (bright) on top, optional rule, then details
            // (dim) below. Lines + divider Y are pre-computed in
            // {@link #layoutRects()} so this pass and the shape pass agree.
            float left = detailWindow.x + 14f;
            int line = 0;
            for (String s : detailFlavorLines) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                        s, left, detailBodyTop - line * ctx.lineH());
                line++;
            }
            if (!Float.isNaN(detailDividerY)) line += 2;
            for (String s : detailDetailsLines) {
                TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        s, left, detailBodyTop - line * ctx.lineH());
                line++;
            }

            // Action button labels. The "Use" button takes its label from
            // the item's CSV {@code useVerb} when set ("eat", "drink",
            // "zap", "grapple", "absorb"); falls back to "Use" for items
            // that didn't bother to specify one.
            String useLabel = (selectedItem.useVerb != null
                    && !selectedItem.useVerb.isEmpty())
                    ? TextCatalog.capitalize(selectedItem.useVerb)
                    : TextCatalog.get("ui.inventory.use");
            TextDraw.centre(ctx, ctx.fontRegular,
                    detailUsePressed ? UIVars.ACCENT : (canUseSelected() ? UIVars.TEXT_BODY : UIVars.TEXT_DIM),
                    TextDraw.ellipsize(ctx.fontRegular, useLabel, detailUseBtn.w - 8f),
                    detailUseBtn.cx(), detailUseBtn.cy() + 6f);
            String equipLabel;
            if (selectedItem.isEquippable() && player != null
                    && com.bjsp123.rl2.logic.InventorySystem
                            .isEquipped(player.inventory, selectedItem)) {
                equipLabel = TextCatalog.get("ui.inventory.unequip");
            } else {
                equipLabel = TextCatalog.get("ui.inventory.equip");
            }
            TextDraw.centre(ctx, ctx.fontRegular,
                    detailEquipPressed ? UIVars.ACCENT : (canEquipSelected() ? UIVars.TEXT_BODY : UIVars.TEXT_DIM),
                    TextDraw.ellipsize(ctx.fontRegular, equipLabel, detailEquipBtn.w - 8f),
                    detailEquipBtn.cx(), detailEquipBtn.cy() + 6f);
            boolean dropMode = dropModeSelected();
            boolean thirdEnabled = dropMode || canThrowSelected();
            boolean dropArmed = dropMode && dropArmedFor == selectedItem;
            String thirdLabel = dropMode
                    ? TextCatalog.get(dropArmed ? "ui.inventory.dropConfirm" : "ui.inventory.drop")
                    : TextCatalog.get("ui.inventory.throw");
            TextDraw.centre(ctx, ctx.fontRegular,
                    detailThrowPressed ? UIVars.ACCENT : (thirdEnabled ? UIVars.TEXT_BODY : UIVars.TEXT_DIM),
                    TextDraw.ellipsize(ctx.fontRegular, thirdLabel, detailThrowBtn.w - 8f),
                    detailThrowBtn.cx(), detailThrowBtn.cy() + 6f);
            if (encyclopedia != null) {
                // Standard info-icon button - same INFO glyph the
                // perks-tab info buttons use, so info affordances read
                // identically across V2 surfaces.
                ButtonChrome.icon(ctx, detailInfoBtn,
                        com.bjsp123.rl2.world.render.IconSprites.regionFor(
                                com.bjsp123.rl2.world.render.IconSprites.Icon.INFO),
                        detailInfoPressed, false);
            }

            // Quickslot bind labels - one number per quickslot + "Quickslot" header.
            if (actionBar != null) {
                TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                        TextCatalog.get("ui.inventory.quickslot"),
                        detailWindow.cx(),
                        bindBtnRects[0].top() + 16f);
                for (int i = 0; i < Settings.quickslotCount(); i++) {
                    Rect r = bindBtnRects[i];
                    boolean bound = actionBar.get(i) == selectedItem;
                    // Hotkey label: 1-9, then 0 / A / B for slots 10-12, matching the HUD.
                    String key = com.bjsp123.rl2.ui.hud.ActionBar.slotLabel(i);
                    TextDraw.centre(ctx, ctx.fontRegular,
                            bound ? UIVars.ACCENT : UIVars.TEXT_BODY,
                            key,
                            r.cx(), r.cy() + 6f);
                }
            }
        }

        ctx.batch.end();
    }


    /** Draw {@code item}'s sprite icon centred inside {@code cell}. Caller
     *  is in the SpriteBatch pass. Items bound to a quickslot get a small
     *  "slot: X" badge in the cell's top-left corner. */
    private void drawCellIcon(Rect cell, Item item) {
        ItemCell.draw(ctx, item, player, cell.x, cell.y, cell.w, cell.h, true);
        if (actionBar != null && item != null) {
            int si = actionBar.indexOf(item);
            if (si >= 0 && si < Settings.quickslotCount()) drawSlotBadge(cell, si);
        }
    }

    /** Top-left "slot: X" badge marking an item that's bound to quickslot
     *  {@code slotIdx} (label per {@link ActionBar#slotLabel}). */
    private void drawSlotBadge(Rect cell, int slotIdx) {
        com.badlogic.gdx.graphics.g2d.BitmapFont f = ctx.fontRegular;
        float prev = f.getScaleX();
        f.getData().setScale(prev * 0.62f);
        f.setColor(UIVars.ACCENT);
        f.draw(ctx.batch, "slot: " + ActionBar.slotLabel(slotIdx),
                cell.x + 3f, cell.y + cell.h - 2f);
        f.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        f.getData().setScale(prev);
    }

    // -- Long-press help -------------------------------------------------------
    /** One long-pressed inventory slot: {@code item} is what's in it, or
     *  {@code null} for an empty slot (the caller shows generic bag help). */
    public static final class HelpHit {
        public final Item item;
        HelpHit(Item item) { this.item = item; }
    }

    /** Hit-test ({@code vx},{@code vy}) (virtual coords) against the
     *  equipment row + bag grid for the long-press help gesture. Returns
     *  {@code null} when the point misses every slot, or while the
     *  item-detail sub-popup / picker mode is up (those surfaces already
     *  show or gate item detail). */
    public HelpHit helpHitAt(float vx, float vy) {
        if (!open || selectedItem != null || pickMode()) return null;
        for (int i = 0; i < equipRects.length; i++) {
            if (equipRects[i].contains(vx, vy)) return new HelpHit(equippedItemAt(i));
        }
        for (BagCell c : bagCells) {
            if (c.rect.contains(vx, vy)) return new HelpHit(c.item);
        }
        return null;
    }

    /** Reset every held-cell highlight. Called when a long-press fires -
     *  the release is swallowed upstream, so without this the pressed
     *  visual would stick until the next touch. */
    public void clearPressed() {
        bagPressed = -1;
        equipPressed = -1;
        bindPressed = -1;
        tabs.clearPressed();
        detailUsePressed = detailEquipPressed = detailThrowPressed = detailInfoPressed = false;
    }

    // -- Input ---------------------------------------------------------------
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
                        for (int i = 0; i < Settings.quickslotCount(); i++) {
                            if (bindBtnRects[i].contains(vx, vy)) {
                                bindPressed = i;
                                return true;
                            }
                        }
                    }
                    // Only depress a button that actually applies - disabled
                    // (greyed) actions swallow the tap without reacting.
                    if (detailUseBtn.contains(vx, vy))   { if (canUseSelected())   detailUsePressed = true;   return true; }
                    if (detailEquipBtn.contains(vx, vy)) { if (canEquipSelected()) detailEquipPressed = true; return true; }
                    if (detailThrowBtn.contains(vx, vy)) { if (dropModeSelected() || canThrowSelected()) detailThrowPressed = true; return true; }
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
                bagBand.scroller.onTouchDown(vy);

                // Tabs.
                if (tabs.touchDown(vx, vy) >= 0) return true;
                // Equipment + gem cells - tappable iff currently equipped.
                for (int i = 0; i < equipRects.length; i++) {
                    if (equipRects[i].contains(vx, vy)
                            && equippedItemAt(i) != null) {
                        equipPressed = i;
                        return true;
                    }
                }
                // Bag grid cells.
                for (int i = 0; i < bagCells.size(); i++) {
                    if (bagCells.get(i).rect.contains(vx, vy)) {
                        bagPressed = i;
                        bagBand.scroller.onTouchDown(vy);
                        return true;
                    }
                }
                // Tap outside the window closes the inventory.
                if (!window.contains(vx, vy)) {
                    close();
                    return true;
                }
                // Touch landed inside the window but missed every cell -
                // the scroller armed above still covers a drag from here.
                return true;   // any tap inside the inventory frame is consumed
            }

            @Override
            public boolean touchDragged(int sx, int sy, int pointer) {
                if (!open || selectedItem != null) return false;
                float vy = ctx.unprojectY(sx, sy);
                if (bagBand.touchDragged(vy)) {
                    // Drag classified - cancel any pending tap so release
                    // doesn't fire a row click.
                    bagPressed = -1;
                    equipPressed = -1;
                    tabs.clearPressed();
                    return true;
                }
                return false;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (!open || selectedItem != null) return false;
                bagBand.scrolled(amountY);
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
                            && idx < Settings.quickslotCount()
                            && bindBtnRects[idx].contains(vx, vy)) {
                        // Toggle - tap again to unbind.
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
                        String itemName = it.name != null ? it.name : it.type;
                        String playerName = player.name != null ? player.name : "?";
                        if (com.bjsp123.rl2.logic.InventorySystem
                                .isEquipped(player.inventory, it)) {
                            com.bjsp123.rl2.logic.InventorySystem
                                    .unequip(player.inventory, it);
                            com.bjsp123.rl2.logic.EventLog.add(
                                    com.bjsp123.rl2.logic.Messages.itemUnequipped(playerName, itemName));
                        } else {
                            com.bjsp123.rl2.logic.InventorySystem
                                    .equip(player.inventory, it);
                            com.bjsp123.rl2.logic.EventLog.add(
                                    com.bjsp123.rl2.logic.Messages.itemEquipped(playerName, itemName));
                            if (sounds != null)
                                sounds.play("sfx.player.equip." + (it.inventoryCategory != null ? it.inventoryCategory.name().toLowerCase() : ""));
                        }
                        player.statsDirty = true;
                        selectedItem = null;
                        close();
                    }
                    return true;
                }
                if (detailThrowPressed) {
                    detailThrowPressed = false;
                    if (detailThrowBtn.contains(vx, vy)) {
                        Item it = selectedItem;
                        boolean drop = it != null && !it.isThrowable();
                        if (drop && dropArmedFor != it) {
                            // First tap on Drop: arm a confirm ("Drop?") instead of
                            // discarding immediately, so a stray tap (throw muscle
                            // memory) can't dump gear on the floor. Keep the popup open.
                            dropArmedFor = it;
                            return true;
                        }
                        dropArmedFor = null;
                        selectedItem = null;
                        close();
                        if (player != null && it != null) {
                            if (drop) {
                                if (onDrop != null) onDrop.accept(player, it);
                            } else if (onThrow != null) {
                                onThrow.accept(player, it);
                            }
                        }
                    }
                    return true;
                }

                // Tab switch.
                if (tabs.hasPressed()) {
                    int i = tabs.touchUp(vx, vy);
                    if (i >= 0 && currentTab != Tab.values()[i]) {
                        currentTab = Tab.values()[i];
                        bagBand.scroller.resetTop();
                    }
                    return true;
                }
                // Equipment-cell tap -> pick (chooser) or open item-detail popup.
                if (equipPressed >= 0) {
                    int idx = equipPressed;
                    equipPressed = -1;
                    if (idx < equipRects.length
                            && equipRects[idx].contains(vx, vy)) {
                        if (pickMode()) tryPick(equippedItemAt(idx));
                        else selectedItem = equippedItemAt(idx);
                    }
                    return true;
                }
                // Bag-cell tap -> pick (chooser) or open item-detail popup.
                if (bagPressed >= 0) {
                    int idx = bagPressed;
                    bagPressed = -1;
                    if (idx < bagCells.size()
                            && bagCells.get(idx).rect.contains(vx, vy)) {
                        if (pickMode()) tryPick(bagCells.get(idx).item);
                        else selectedItem = bagCells.get(idx).item;
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

    /** Local convenience holder - one cell's rect + which Item it represents. */
    private static final class BagCell {
        final Rect rect = new Rect();
        Item item;
    }
}
