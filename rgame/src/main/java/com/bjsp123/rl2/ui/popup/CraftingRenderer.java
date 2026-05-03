package com.bjsp123.rl2.ui.popup;
import com.badlogic.gdx.Input;
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
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.RecipeSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.ItemSprites;

/**
 * Crafting / combining screen. Two tabs:
 * <ul>
 *   <li><b>Combine</b> — three input cells on the left, an arrow, and a result cell on the
 *       right. Tapping an input cell opens an inline gem picker filtered to the player's
 *       gems. When the three cells form a valid recipe (resolved through
 *       {@link RecipeSystem#tryMatch}), the arrow becomes clickable and the result cell
 *       previews the produced item with a pulsing highlight; clicking the arrow consumes
 *       the inputs and adds the result to the inventory.</li>
 *   <li><b>Recipes</b> — a scrollable list of every registered recipe, in declaration
 *       order. Used as a reference / discovery view.</li>
 * </ul>
 *
 * <p>Inputs sitting in crafting slots remain in the player's inventory until the recipe
 * is confirmed, so closing the screen mid-edit is non-destructive.
 */
public class CraftingRenderer extends Group {

    private static final float CELL = 48f;
    private static final int   N_INPUTS = 3;

    private final Skin skin;

    private Mob player;

    private final Container<Table> framed;
    private final Table content;
    private final TextButton tabCombineBtn, tabRecipesBtn;
    private final Table combineTab, recipesTab;

    /** The three crafting input cells. {@link #slots}[i] holds the Item placed in cell i,
     *  or {@code null} when empty. The Item remains in {@code player.inventory.bag}; the
     *  reference here is just a UI binding. */
    private final Item[] slots = new Item[N_INPUTS];
    private final Stack[] inputCells = new Stack[N_INPUTS];
    private final Stack resultCell;
    private final TextButton arrowBtn;

    /** Inline gem-picker that overlays the combine tab. {@code pickerSlot} is the input
     *  cell being filled, or -1 when not picking. */
    private Table pickerOverlay;
    private int pickerSlot = -1;

    private boolean open;
    private enum Tab { COMBINE, RECIPES }
    private Tab tab = Tab.COMBINE;

    public CraftingRenderer(Skin skin) {
        this.skin = skin;

        content = new Table();
        // Inset calibrated for Shattered's thin frame (2-px border + 2-px chamfer);
        // ~6 px of breathing room inside, no more.
        content.pad(8).defaults().pad(4);

        // ── Tab strip ─────────────────────────────────────────────────────
        // Built up here, but added to the layout BELOW inside the panel-bracketed
        // tab section so the active tab can visually overlap the panel's top border.
        Table tabRow = new Table();
        tabRow.defaults().pad(0);
        tabCombineBtn = new TextButton("Combine", skin, "tab");
        tabRecipesBtn = new TextButton("Recipes", skin, "tab");
        tabCombineBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { setTab(Tab.COMBINE); }
        });
        tabRecipesBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { setTab(Tab.RECIPES); }
        });
        tabRow.add(tabCombineBtn).minWidth(96).height(26);
        tabRow.add(tabRecipesBtn).minWidth(96).height(26);

        // ── Combine tab ───────────────────────────────────────────────────
        combineTab = new Table();
        combineTab.defaults().pad(4);
        Table cellRow = new Table();
        for (int i = 0; i < N_INPUTS; i++) {
            final int idx = i;
            inputCells[i] = makeCell();
            inputCells[i].addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    openPicker(idx);
                }
            });
            cellRow.add(inputCells[i]).size(CELL).pad(4);
        }
        Label arrowL = new Label(" → ", skin, "title");
        cellRow.add(arrowL).pad(8);
        resultCell = makeCell();
        cellRow.add(resultCell).size(CELL).pad(4);
        combineTab.add(cellRow).row();

        arrowBtn = new TextButton("Combine!", skin);
        arrowBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { confirmCombine(); }
        });
        combineTab.add(arrowBtn).width(140).height(32).padTop(8).row();

        Label hintL = new Label("Tap a slot to choose a gem.  [Esc] close", skin, "dim");
        combineTab.add(hintL).padTop(6).row();

        // ── Recipes tab ───────────────────────────────────────────────────
        recipesTab = new Table();
        recipesTab.defaults().left().pad(2);
        Table list = new Table();
        list.defaults().left().pad(4);
        for (RecipeSystem.Recipe r : RecipeSystem.ALL) {
            list.add(new Label("• " + r.describe(), skin, "default")).left().row();
        }
        // No-arg ScrollPane avoids looking up a "default" ScrollPaneStyle in the skin —
        // the project's stonebase skin doesn't ship one. We don't need a styled scroll
        // bar; the recipes list is short enough that scrolling is a nice-to-have.
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFadeScrollBars(false);
        recipesTab.add(scroll).expand().fill().left();

        // Tab content goes in a Stack — both tabs occupy the same body area; only
        // the visible one renders. Switching tabs is a setVisible() toggle (see
        // setTab), so the body's allocated area is constant regardless of which
        // sub-tab's content is showing.
        com.badlogic.gdx.scenes.scene2d.ui.Stack tabBody =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        tabBody.add(combineTab);
        tabBody.add(recipesTab);

        // ── Tab section: panel-around-content with tabs joined to its top ─────
        // Same pattern as the inventory backpack section: a Stack with the inner
        // content panel chrome BELOW the tab strip, and the tabs OVERLAPPING only
        // the panel's 2-px top border. The active tab's open bottom + matching
        // panel-fill colour let the panel's interior bleed up into the selected tab.
        final int TAB_H = 26;
        com.badlogic.gdx.scenes.scene2d.ui.Stack tabSection =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();

        Image tabSectionBg = new Image(skin.getDrawable("panel"));
        tabSectionBg.setScaling(com.badlogic.gdx.utils.Scaling.stretch);
        Table tabSectionBgLayer = new Table();
        tabSectionBgLayer.top();
        tabSectionBgLayer.add().height(TAB_H - 2).row();
        tabSectionBgLayer.add(tabSectionBg).expand().fill();
        tabSection.add(tabSectionBgLayer);

        Table tabSectionContent = new Table();
        tabSectionContent.top();
        tabSectionContent.add(tabRow).left().fillX().height(TAB_H).row();
        tabSectionContent.add(tabBody).expand().fill().pad(8);
        tabSection.add(tabSectionContent);

        content.add(tabSection).expand().fill().row();
        content.setBackground(skin.getDrawable("simple-panel"));

        framed = new Container<>(content);
        framed.fill();
        addActor(framed);

        // Inline picker overlay — built once, reattached as needed.
        pickerOverlay = new Table();
        pickerOverlay.setBackground(skin.getDrawable("simple-panel"));
        pickerOverlay.setVisible(false);
        addActor(pickerOverlay);

        setTouchable(Touchable.enabled);
        setVisible(false);

        // Outside-click dismissal: clicks not on the panel close the screen.
        addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                Actor target = event.getTarget();
                if (pickerSlot >= 0) {
                    if (!isDescendant(target, pickerOverlay)) closePicker();
                    return true;
                }
                if (!isDescendant(target, framed)) {
                    setOpen(false);
                    return true;
                }
                return true;
            }

            @Override
            public boolean keyDown(InputEvent event, int keycode) { return handleKey(keycode); }
        });
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public void setPlayer(Mob p) { this.player = p; }

    public boolean isOpen() { return open; }

    public void toggle() { setOpen(!open); }

    public void setOpen(boolean v) {
        if (open == v) return;
        open = v;
        setVisible(open);
        if (!open) {
            // Closing without confirming clears slot bindings — the items are still in
            // inventory.bag, so this is just dropping the UI state.
            for (int i = 0; i < N_INPUTS; i++) slots[i] = null;
            closePicker();
        } else {
            setTab(tab);
            if (getStage() != null) getStage().setKeyboardFocus(this);
        }
        refresh();
    }

    /** Open the screen with {@code preload} dropped into the first empty input cell. */
    public void openWith(Item preload) {
        setOpen(true);
        if (preload != null) {
            for (int i = 0; i < N_INPUTS; i++) {
                if (slots[i] == null) { slots[i] = preload; break; }
            }
        }
        setTab(Tab.COMBINE);
        refresh();
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    /** Position the framed combine/recipes panel centred against the stage at a
     *  deterministic SMALL preferred size — the panel is "smaller" per the user spec
     *  ("look and combine fill a bit less"). Tab content sits in a Stack inside, so
     *  switching Combine ↔ Recipes never changes the panel's size. */
    public void layoutForStage(Stage stage) {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();
        setBounds(0, 0, w, h);
        float panelW = com.bjsp123.rl2.ui.skin.PanelSize.widthFor(
                com.bjsp123.rl2.ui.skin.PanelSize.Kind.SMALL, w);
        float panelH = com.bjsp123.rl2.ui.skin.PanelSize.heightFor(
                com.bjsp123.rl2.ui.skin.PanelSize.Kind.SMALL, h);
        framed.setSize(panelW, panelH);
        framed.setPosition((w - panelW) / 2f, (h - panelH) / 2f);

        // Picker overlay is built dynamically (gem-pick grid sized to inventory contents)
        // — leave it on its packed preferred size and just clamp / center within the
        // viewport edge margin.
        pickerOverlay.pack();
        float pw = Math.min(pickerOverlay.getWidth(),
                w - com.bjsp123.rl2.ui.skin.PanelSize.EDGE_MARGIN_PX);
        float ph = Math.min(pickerOverlay.getHeight(),
                h - com.bjsp123.rl2.ui.skin.PanelSize.EDGE_MARGIN_PX);
        pickerOverlay.setSize(pw, ph);
        pickerOverlay.setPosition((w - pw) / 2f, (h - ph) / 2f);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (getStage() != null) layoutForStage(getStage());
        refresh();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void setTab(Tab t) {
        tab = t;
        tabCombineBtn.setChecked(t == Tab.COMBINE);
        tabRecipesBtn.setChecked(t == Tab.RECIPES);
        // Both tabs live in a Stack inside the body cell; only one is ever visible
        // at a time. Switching is just a visibility toggle — the body's allocated
        // area stays at whatever {@link #layoutForStage} chose, regardless of which
        // sub-tab's content is showing.
        combineTab.setVisible(t == Tab.COMBINE);
        recipesTab.setVisible(t == Tab.RECIPES);
    }

    private Stack makeCell() {
        Stack s = new Stack();
        Image bg = new Image(skin.getDrawable("simple-panel"));
        bg.setColor(0.18f, 0.18f, 0.20f, 1f);
        Image content = new Image();
        s.add(bg);
        s.add(content);
        s.setTouchable(Touchable.enabled);
        return s;
    }

    private void setCellIcon(Stack cell, TextureRegion region) {
        Image content = (Image) cell.getChildren().get(1);
        content.setDrawable(region != null ? new TextureRegionDrawable(region) : null);
    }

    /** Refresh per-frame: cell icons, arrow enabled state, result preview. */
    private void refresh() {
        if (!open) return;
        for (int i = 0; i < N_INPUTS; i++) {
            Item it = slots[i];
            setCellIcon(inputCells[i], it == null ? null : ItemSprites.regionFor(it));
        }
        Item result = RecipeSystem.tryMatch(slots[0], slots[1], slots[2]);
        setCellIcon(resultCell, result == null ? null : ItemSprites.regionFor(result));
        arrowBtn.setDisabled(result == null);
        // Pulse the result cell when a match is live, so the player sees it spark.
        Image resultBg = (Image) resultCell.getChildren().get(0);
        if (result != null) {
            float t = (float) ((System.nanoTime() / 1_000_000L) % 1_000_000L) / 1000f;
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 4.0);
            resultBg.setColor(0.6f + 0.4f * pulse, 0.6f + 0.4f * pulse, 0.3f, 1f);
        } else {
            resultBg.setColor(0.18f, 0.18f, 0.20f, 1f);
        }
    }

    private void confirmCombine() {
        if (player == null) return;
        Item result = RecipeSystem.tryMatch(slots[0], slots[1], slots[2]);
        if (result == null) return;
        // Consume one unit from each filled crafting slot. {@code removeOneFromBag}
        // decrements stack count when the input was a stack of N > 1, only deleting
        // the bag entry once the count hits 0.
        for (int i = 0; i < N_INPUTS; i++) {
            Item s = slots[i];
            if (s != null) player.inventory.removeOneFromBag(s);
            slots[i] = null;
        }
        // Result merges into an existing matching stack if one exists.
        player.inventory.addToBag(result);
        EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                "Combined: " + ItemSystem.displayName(result),
                com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH,
                true));
        refresh();
    }

    // ── Picker overlay ──────────────────────────────────────────────────────

    private void openPicker(int slotIdx) {
        pickerSlot = slotIdx;
        pickerOverlay.clearChildren();
        pickerOverlay.pad(12);
        pickerOverlay.add(new Label("Choose a gem", skin, "title")).left().padBottom(8).row();

        if (player == null || player.inventory.bag.isEmpty()) {
            pickerOverlay.add(new Label("(no gems in bag)", skin, "dim")).row();
        } else {
            Table grid = new Table();
            grid.defaults().pad(4);
            int col = 0;
            for (Item it : player.inventory.bag) {
                if (!it.isGem()) continue;
                // A stack with count N is selectable up to N times across the three
                // crafting slots — hide the entry only when it's already bound to as
                // many slots as the stack can supply.
                if (countInSlots(it) >= it.count) continue;
                final Item bound = it;
                Stack cell = makeCell();
                setCellIcon(cell, ItemSprites.regionFor(it));
                cell.addListener(new ClickListener() {
                    @Override public void clicked(InputEvent e, float x, float y) {
                        slots[pickerSlot] = bound;
                        closePicker();
                    }
                });
                grid.add(cell).size(CELL);
                String label = ItemSystem.displayName(it)
                        + (it.count > 1 ? " (x" + it.count + ")" : "");
                grid.add(new Label(label, skin, "default")).left().padRight(12);
                col++;
                if (col % 2 == 0) grid.row();
            }
            pickerOverlay.add(grid).row();
        }

        TextButton clear = new TextButton("Clear slot", skin);
        clear.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                slots[pickerSlot] = null;
                closePicker();
            }
        });
        TextButton cancel = new TextButton("Cancel", skin);
        cancel.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { closePicker(); }
        });
        Table btns = new Table();
        btns.add(clear ).width(96).height(28).pad(4);
        btns.add(cancel).width(96).height(28).pad(4);
        pickerOverlay.add(btns).padTop(8).row();
        pickerOverlay.setVisible(true);
    }

    private void closePicker() {
        pickerSlot = -1;
        pickerOverlay.setVisible(false);
        refresh();
    }

    /** How many crafting slots currently reference {@code it} — used to gate stacks
     *  in the picker. A stack of 3 is pickable until 3 slots already hold it. */
    private int countInSlots(Item it) {
        int n = 0;
        for (int i = 0; i < N_INPUTS; i++) if (slots[i] == it) n++;
        return n;
    }

    private boolean handleKey(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            if (pickerSlot >= 0) closePicker();
            else                 setOpen(false);
            return true;
        }
        return false;
    }

    private static boolean isDescendant(Actor target, Actor ancestor) {
        Actor t = target;
        while (t != null) {
            if (t == ancestor) return true;
            t = t.getParent();
        }
        return false;
    }
}
